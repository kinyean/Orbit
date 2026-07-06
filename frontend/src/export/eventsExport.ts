/**
 * Scenario-events JSON/CSV export (Phase 11B, US-IO-07 — SRS §4.2.2).
 *
 * Entirely client-side: every event class (sensor AOS/LOS, eclipse
 * ingress/egress, intra-scenario conjunctions, constraint violations, per-deputy
 * closest approach) already arrives in the `scenario-relative` envelope and sits
 * in the stream buffer — the same data the timeline draws. The builders are pure
 * (data in → document/CSV out); download wrappers sit on top.
 *
 * Not events, so not here: link-budget SNR *series* (a sampled curve — see the
 * timeline band) and catalog-screening results (their own CSV in the
 * Environment panel, UC-7).
 */

import type { components } from '../api/schema';
import type { RelativeFrameData } from '../stream/relativeBuffer';
import { downloadBlob, slugify, timeStamp } from './download';

type ScenarioBody = components['schemas']['ScenarioBody'];

export interface ScenarioEventRecord {
  kind: 'sensor' | 'eclipse' | 'conjunction' | 'constraint' | 'closest-approach';
  /** Event subtype: acquisition | los | umbra-ingress | … | tca | violation-start | violation-end. */
  type: string;
  epoch: string; // ISO 8601 UTC
  primaryId: number | null;
  primaryName: string | null;
  secondaryId: number | null;
  secondaryName: string | null;
  sensorId: string | null;
  sensorName: string | null;
  value: number | null;
  units: string | null;
  detail: string | null;
}

export interface ScenarioEventsDocument {
  schema: 'orbit.scenario-events.v1';
  scenario: {
    id: string;
    name: string;
    start: string | null;
    end: string | null;
    fidelity: string | null;
  };
  /** Distances are chief-relative ranges in the chief-LVLH frame, metres. */
  frame: string;
  events: ScenarioEventRecord[];
}

/** noradId → display name, from the scenario body + the stream's deputy names. */
function buildNameMap(body: ScenarioBody, data: RelativeFrameData): Map<number, string> {
  const names = new Map<number, string>();
  const put = (id: number | undefined, name: string | undefined) => {
    if (typeof id === 'number' && name) names.set(id, name);
  };
  put(body.chief?.noradId, body.chief?.name);
  for (const d of body.deputies ?? []) put(d.noradId, d.name);
  for (const d of data.deputies) put(d.noradId, d.name);
  return names;
}

/** sensorId → sensor display name, from the stream's sensor descriptors. */
function buildSensorNameMap(data: RelativeFrameData): Map<string, string> {
  const out = new Map<string, string>();
  for (const s of data.chief?.sensors ?? []) out.set(s.id, s.name);
  for (const d of data.deputies) for (const s of d.sensors) out.set(s.id, s.name);
  return out;
}

/** Assemble the full events document from the loaded scenario + stream buffer (pure). */
export function buildEventsDocument(
  scenario: { id: string; name: string; body: ScenarioBody },
  data: RelativeFrameData,
): ScenarioEventsDocument {
  const names = buildNameMap(scenario.body, data);
  const sensorNames = buildSensorNameMap(data);
  const nameOf = (id: number | null): string | null => (id === null ? null : names.get(id) ?? null);
  const iso = (ms: number) => new Date(ms).toISOString();

  const events: ScenarioEventRecord[] = [];

  for (const e of data.events) {
    events.push({
      kind: 'sensor',
      type: e.type,
      epoch: iso(e.epochMs),
      primaryId: e.hostId,
      primaryName: nameOf(e.hostId),
      secondaryId: e.targetId,
      secondaryName: nameOf(e.targetId),
      sensorId: e.sensorId,
      sensorName: sensorNames.get(e.sensorId) ?? null,
      value: e.rangeM,
      units: 'm',
      detail: null,
    });
  }
  for (const e of data.eclipses) {
    events.push({
      kind: 'eclipse',
      type: e.type,
      epoch: iso(e.epochMs),
      primaryId: e.noradId,
      primaryName: nameOf(e.noradId),
      secondaryId: null,
      secondaryName: null,
      sensorId: null,
      sensorName: null,
      value: null,
      units: null,
      detail: null,
    });
  }
  for (const c of data.conjunctions) {
    events.push({
      kind: 'conjunction',
      type: 'tca',
      epoch: iso(c.tcaEpochMs),
      primaryId: c.aNoradId,
      primaryName: nameOf(c.aNoradId),
      secondaryId: c.bNoradId,
      secondaryName: nameOf(c.bNoradId),
      sensorId: null,
      sensorName: null,
      value: c.missDistanceM,
      units: 'm',
      detail: null,
    });
  }
  for (const v of data.violations) {
    events.push({
      kind: 'constraint',
      type: v.type,
      epoch: iso(v.epochMs),
      primaryId: v.hostId,
      primaryName: nameOf(v.hostId),
      secondaryId: v.targetId,
      secondaryName: nameOf(v.targetId),
      sensorId: v.sensorId,
      sensorName: v.sensorId ? sensorNames.get(v.sensorId) ?? null : null,
      value: v.valueDeg,
      units: 'deg',
      detail: `${v.kind}; limit=${v.limitDeg}deg; constraint=${v.constraintId}`,
    });
  }
  for (const d of data.deputies) {
    if (d.tcaEpochMs === null) continue;
    events.push({
      kind: 'closest-approach',
      type: 'tca',
      epoch: iso(d.tcaEpochMs),
      primaryId: d.noradId,
      primaryName: nameOf(d.noradId),
      secondaryId: data.chiefId >= 0 ? data.chiefId : null,
      secondaryName: nameOf(data.chiefId >= 0 ? data.chiefId : null),
      sensorId: null,
      sensorName: null,
      value: d.tcaDistanceM,
      units: 'm',
      detail: 'chief-relative closest approach',
    });
  }

  events.sort((a, b) => a.epoch.localeCompare(b.epoch) || a.kind.localeCompare(b.kind));

  return {
    schema: 'orbit.scenario-events.v1',
    scenario: {
      id: scenario.id,
      name: scenario.name,
      start: scenario.body.timeRange?.start ?? null,
      end: scenario.body.timeRange?.end ?? null,
      fidelity: scenario.body.fidelity ?? null,
    },
    frame: 'chief-LVLH (distances = chief-relative range, metres)',
    events,
  };
}

/** Flatten the document to CSV — one row per event (pure). */
export function eventsToCsv(doc: ScenarioEventsDocument): string {
  const header =
    'kind,type,epoch,primaryId,primaryName,secondaryId,secondaryName,sensorId,sensorName,value,units,detail';
  const q = (s: string | null) => (s === null ? '' : JSON.stringify(s));
  const n = (v: number | null) => (v === null ? '' : String(v));
  const rows = doc.events.map((e) =>
    [
      e.kind,
      e.type,
      e.epoch,
      n(e.primaryId),
      q(e.primaryName),
      n(e.secondaryId),
      q(e.secondaryName),
      q(e.sensorId),
      q(e.sensorName),
      n(e.value),
      q(e.units),
      q(e.detail),
    ].join(','),
  );
  return [header, ...rows].join('\n');
}

export function exportEventsJson(
  scenario: { id: string; name: string; body: ScenarioBody },
  data: RelativeFrameData,
): void {
  const doc = buildEventsDocument(scenario, data);
  downloadBlob(
    new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json' }),
    `orbit-${slugify(scenario.name)}-events-${timeStamp()}.json`,
  );
}

export function exportEventsCsv(
  scenario: { id: string; name: string; body: ScenarioBody },
  data: RelativeFrameData,
): void {
  const csv = eventsToCsv(buildEventsDocument(scenario, data));
  downloadBlob(
    new Blob([csv], { type: 'text/csv' }),
    `orbit-${slugify(scenario.name)}-events-${timeStamp()}.csv`,
  );
}
