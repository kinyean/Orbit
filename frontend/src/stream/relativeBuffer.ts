// Relative-state buffer for the proximity view (Phase 4B).
//
// Per Decision 5, high-frequency ephemeris lives OUTSIDE Zustand. The single
// per-scenario WebSocket (owned by Globe) fills this module singleton via the
// stream's `onRelative` callback; ProximityView reads it each frame. One stream
// serves both viewports — never open a second socket.
//
// LVLH samples are flat `[t,R,I,C, ...]` (stride 4) or `[t,R,I,C,vR,vI,vC, ...]`
// (stride 7), `t` seconds since `epoch`. R/I/C metres; the proximity view maps
// R→+X, I→+Y, C→+Z (right-handed), 1 unit = 1 m.

import type { ScenarioRelativeMessage } from './ScenarioStreamClient';

/** Attitude sample stride: [t, qx, qy, qz, qw] (Phase 7). */
export const ATT_STRIDE = 5;

/** A body-fixed sensor descriptor (Phase 7); the proximity view builds FOV geometry from it. */
export interface SensorDef {
  id: string;
  kind: string;
  name: string;
  fov: { type: string; halfAngleDeg: number; hDeg: number; vDeg: number };
  minRangeM: number;
  maxRangeM: number;
  mount: { boresightBody: [number, number, number]; clockDeg: number };
}

export interface DeputyRelative {
  noradId: number;
  name: string;
  stride: number; // 4 or 7
  hasVelocity: boolean;
  samples: Float64Array; // [t,R,I,C,(vR,vI,vC), ...]
  tcaEpochMs: number | null; // closest approach to the chief (Phase 5A); null if absent
  tcaDistanceM: number | null; // chief-relative range at TCA, metres
  // Phase 7: modeled body-orientation samples in the chief-LVLH scene frame,
  // [t,qx,qy,qz,qw, ...] (three.js convention), and this deputy's sensors. Both
  // optional — absent on older backends (the view falls back to a derived estimate).
  attitude: Float64Array | null;
  sensors: SensorDef[];
}

/** The chief block (Phase 7): the LVLH origin's attitude + sensors so its FOV renders. */
export interface ChiefRelative {
  noradId: number;
  attitude: Float64Array | null;
  sensors: SensorDef[];
}

/** A sensor acquisition / loss-of-sight event (Phase 7, US-EVT-01). */
export interface SensorEvent {
  type: 'acquisition' | 'los';
  hostId: number;
  sensorId: string;
  targetId: number;
  epochMs: number;
  rangeM: number;
}

export interface RelativeFrameData {
  epochMs: number; // Date.parse(envelope.epoch) — the t=0 reference
  stepSeconds: number;
  frame: string; // "LVLH"
  chiefId: number;
  deputies: DeputyRelative[];
  // CW validity hints (Phase 5C): the client warns when a "cw" scenario exceeds
  // the linearization's small-separation / near-circular envelope.
  fidelity: string;
  maxSeparationM: number;
  chiefEccentricity: number;
  // Chief geocentric radius (metres, Phase 6 / US-PROX-05): the proximity view
  // places the Earth backdrop at (−chiefRadiusM, 0, 0) in the LVLH scene. 0 when
  // absent (older backend) → the view falls back to a representative LEO radius.
  chiefRadiusM: number;
  // Chief attitude + sensors (Phase 7); null when absent (older backend).
  chief: ChiefRelative | null;
  // Acquisition / loss-of-sight events over the window (Phase 7); empty when absent.
  events: SensorEvent[];
}

let current: RelativeFrameData | null = null;
let version = 0; // bumped on set/clear so ProximityView can rebuild its meshes

// Subscribers (e.g. RelativeReadout, Timeline) — these live outside Zustand
// (Decision 5), so they get a tiny store-shaped subscribe/getSnapshot pair to
// drive React's useSyncExternalStore when the deputy set changes.
const listeners = new Set<() => void>();

function emit(): void {
  for (const l of listeners) l();
}

export function setRelativeData(data: RelativeFrameData): void {
  current = data;
  version++;
  emit();
}

export function clearRelativeData(): void {
  if (current === null) return;
  current = null;
  version++;
  emit();
}

export function getRelativeData(): RelativeFrameData | null {
  return current;
}

/** Bumped whenever the data is replaced/cleared — a cheap "rebuild meshes?" signal. */
export function getRelativeVersion(): number {
  return version;
}

/** Subscribe to data replacement/clear (for useSyncExternalStore). */
export function subscribeRelative(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Seconds since the frame epoch for a sim time. */
export function simTimeToT(epochMs: number, currentTime: Date): number {
  return (currentTime.getTime() - epochMs) / 1000;
}

/**
 * Write the deputy's [R, I, C] (metres) at time `t` (seconds since epoch) into
 * `out`. Linear interpolation between the bracketing samples; HOLD-clamps at the
 * ends (matching the CZML forward/backward HOLD so both views agree at edges).
 * Allocation-free — runs per deputy per frame.
 */
export function deputyPositionAt(
  samples: Float64Array,
  stride: number,
  t: number,
  out: [number, number, number],
): void {
  const n = Math.floor(samples.length / stride);
  if (n === 0) {
    out[0] = out[1] = out[2] = 0;
    return;
  }
  const tFirst = samples[0];
  const tLast = samples[(n - 1) * stride];
  if (t <= tFirst) {
    out[0] = samples[1];
    out[1] = samples[2];
    out[2] = samples[3];
    return;
  }
  if (t >= tLast) {
    const b = (n - 1) * stride;
    out[0] = samples[b + 1];
    out[1] = samples[b + 2];
    out[2] = samples[b + 3];
    return;
  }
  // Binary search for the last sample with time <= t.
  let lo = 0;
  let hi = n - 1;
  while (lo + 1 < hi) {
    const mid = (lo + hi) >> 1;
    if (samples[mid * stride] <= t) lo = mid;
    else hi = mid;
  }
  const ba = lo * stride;
  const bb = hi * stride;
  const ta = samples[ba];
  const tb = samples[bb];
  const f = tb > ta ? (t - ta) / (tb - ta) : 0;
  out[0] = samples[ba + 1] + (samples[bb + 1] - samples[ba + 1]) * f;
  out[1] = samples[ba + 2] + (samples[bb + 2] - samples[ba + 2]) * f;
  out[2] = samples[ba + 3] + (samples[bb + 3] - samples[ba + 3]) * f;
}

/**
 * Write the deputy's full relative state at time `t` into `out6`:
 * `[R, I, C, vR, vI, vC]` (metres, m/s). Velocities are 0 when the stride is 4.
 * Linear interpolation with end HOLD-clamp, mirroring {@link deputyPositionAt}.
 * Allocation-free — safe to call per deputy per frame.
 */
export function deputyStateAt(
  samples: Float64Array,
  stride: number,
  hasVelocity: boolean,
  t: number,
  out6: Float64Array | number[],
): void {
  out6[3] = out6[4] = out6[5] = 0;
  const n = Math.floor(samples.length / stride);
  if (n === 0) {
    out6[0] = out6[1] = out6[2] = 0;
    return;
  }
  const tFirst = samples[0];
  const tLast = samples[(n - 1) * stride];
  if (t <= tFirst || t >= tLast || n === 1) {
    const b = t <= tFirst ? 0 : (n - 1) * stride;
    out6[0] = samples[b + 1];
    out6[1] = samples[b + 2];
    out6[2] = samples[b + 3];
    if (hasVelocity && stride >= 7) {
      out6[3] = samples[b + 4];
      out6[4] = samples[b + 5];
      out6[5] = samples[b + 6];
    }
    return;
  }
  let lo = 0;
  let hi = n - 1;
  while (lo + 1 < hi) {
    const mid = (lo + hi) >> 1;
    if (samples[mid * stride] <= t) lo = mid;
    else hi = mid;
  }
  const ba = lo * stride;
  const bb = hi * stride;
  const ta = samples[ba];
  const tb = samples[bb];
  const f = tb > ta ? (t - ta) / (tb - ta) : 0;
  out6[0] = samples[ba + 1] + (samples[bb + 1] - samples[ba + 1]) * f;
  out6[1] = samples[ba + 2] + (samples[bb + 2] - samples[ba + 2]) * f;
  out6[2] = samples[ba + 3] + (samples[bb + 3] - samples[ba + 3]) * f;
  if (hasVelocity && stride >= 7) {
    out6[3] = samples[ba + 4] + (samples[bb + 4] - samples[ba + 4]) * f;
    out6[4] = samples[ba + 5] + (samples[bb + 5] - samples[ba + 5]) * f;
    out6[5] = samples[ba + 6] + (samples[bb + 6] - samples[ba + 6]) * f;
  }
}

/**
 * Write the body-orientation quaternion at time `t` (seconds since epoch) into
 * `out4 = [x, y, z, w]` (three.js convention). SLERP between the bracketing
 * samples; HOLD-clamps at the ends. Falls back to identity for an empty array.
 * Allocation-free — safe per craft per frame.
 */
export function deputyAttitudeAt(att: Float64Array, t: number, out4: number[] | Float64Array): void {
  const n = Math.floor(att.length / ATT_STRIDE);
  if (n === 0) {
    out4[0] = out4[1] = out4[2] = 0;
    out4[3] = 1;
    return;
  }
  const tFirst = att[0];
  const tLast = att[(n - 1) * ATT_STRIDE];
  if (t <= tFirst || n === 1) {
    copyQuat(att, 0, out4);
    return;
  }
  if (t >= tLast) {
    copyQuat(att, (n - 1) * ATT_STRIDE, out4);
    return;
  }
  let lo = 0;
  let hi = n - 1;
  while (lo + 1 < hi) {
    const mid = (lo + hi) >> 1;
    if (att[mid * ATT_STRIDE] <= t) lo = mid;
    else hi = mid;
  }
  const ba = lo * ATT_STRIDE;
  const bb = hi * ATT_STRIDE;
  const ta = att[ba];
  const tb = att[bb];
  const f = tb > ta ? (t - ta) / (tb - ta) : 0;
  slerp(att, ba, att, bb, f, out4);
}

function copyQuat(src: Float64Array, base: number, out4: number[] | Float64Array): void {
  out4[0] = src[base + 1];
  out4[1] = src[base + 2];
  out4[2] = src[base + 3];
  out4[3] = src[base + 4];
}

/** Spherical-linear interpolation between two stride-5 quaternion samples into `out4`. */
function slerp(
  a: Float64Array,
  ba: number,
  b: Float64Array,
  bb: number,
  t: number,
  out4: number[] | Float64Array,
): void {
  const ax = a[ba + 1];
  const ay = a[ba + 2];
  const az = a[ba + 3];
  const aw = a[ba + 4];
  let bx = b[bb + 1];
  let by = b[bb + 2];
  let bz = b[bb + 3];
  let bw = b[bb + 4];
  let cos = ax * bx + ay * by + az * bz + aw * bw;
  if (cos < 0) {
    // shorter arc (quaternion double-cover)
    bx = -bx;
    by = -by;
    bz = -bz;
    bw = -bw;
    cos = -cos;
  }
  let s0: number;
  let s1: number;
  if (cos > 0.9995) {
    s0 = 1 - t;
    s1 = t;
  } else {
    const theta = Math.acos(cos);
    const sin = Math.sin(theta);
    s0 = Math.sin((1 - t) * theta) / sin;
    s1 = Math.sin(t * theta) / sin;
  }
  const x = s0 * ax + s1 * bx;
  const y = s0 * ay + s1 * by;
  const z = s0 * az + s1 * bz;
  const w = s0 * aw + s1 * bw;
  const norm = Math.hypot(x, y, z, w) || 1;
  out4[0] = x / norm;
  out4[1] = y / norm;
  out4[2] = z / norm;
  out4[3] = w / norm;
}

/** Parse a `scenario-relative` envelope into buffer data; null if malformed. */
export function parseRelativeMessage(msg: ScenarioRelativeMessage): RelativeFrameData | null {
  const epochMs = Date.parse(String(msg.epoch));
  if (Number.isNaN(epochMs)) return null;
  const stride = typeof msg.stride === 'number' ? msg.stride : 4;
  const rawDeputies = Array.isArray(msg.deputies) ? msg.deputies : [];

  const deputies: DeputyRelative[] = [];
  for (const raw of rawDeputies) {
    const d = raw as {
      noradId?: unknown;
      name?: unknown;
      samples?: unknown;
      tcaEpoch?: unknown;
      tcaDistanceM?: unknown;
      att?: unknown;
      sensors?: unknown;
    };
    if (typeof d.noradId !== 'number' || !Array.isArray(d.samples)) continue;
    const tcaMs = typeof d.tcaEpoch === 'string' ? Date.parse(d.tcaEpoch) : NaN;
    deputies.push({
      noradId: d.noradId,
      name: typeof d.name === 'string' ? d.name : `NORAD ${d.noradId}`,
      stride,
      hasVelocity: stride >= 7,
      samples: Float64Array.from(d.samples as number[]),
      tcaEpochMs: Number.isNaN(tcaMs) ? null : tcaMs,
      tcaDistanceM: typeof d.tcaDistanceM === 'number' ? d.tcaDistanceM : null,
      attitude: parseAttitude(d.att),
      sensors: parseSensors(d.sensors),
    });
  }

  return {
    epochMs,
    stepSeconds: typeof msg.stepSeconds === 'number' ? msg.stepSeconds : 30,
    frame: typeof msg.frame === 'string' ? msg.frame : 'LVLH',
    chiefId: typeof msg.chiefId === 'number' ? msg.chiefId : -1,
    deputies,
    fidelity: typeof msg.fidelity === 'string' ? msg.fidelity : 'sgp4',
    maxSeparationM: typeof msg.maxSeparationM === 'number' ? msg.maxSeparationM : 0,
    chiefEccentricity: typeof msg.chiefEccentricity === 'number' ? msg.chiefEccentricity : 0,
    chiefRadiusM: typeof msg.chiefRadiusM === 'number' ? msg.chiefRadiusM : 0,
    chief: parseChief(msg.chief),
    events: parseEvents(msg.events),
  };
}

/** Parse the optional top-level `events` array (Phase 7); empty when absent. */
function parseEvents(raw: unknown): SensorEvent[] {
  if (!Array.isArray(raw)) return [];
  const out: SensorEvent[] = [];
  for (const item of raw) {
    const e = item as {
      type?: unknown;
      hostId?: unknown;
      sensorId?: unknown;
      targetId?: unknown;
      epoch?: unknown;
      rangeM?: unknown;
    };
    const epochMs = typeof e.epoch === 'string' ? Date.parse(e.epoch) : NaN;
    if ((e.type !== 'acquisition' && e.type !== 'los') || Number.isNaN(epochMs)) continue;
    out.push({
      type: e.type,
      hostId: num(e.hostId, -1),
      sensorId: typeof e.sensorId === 'string' ? e.sensorId : '',
      targetId: num(e.targetId, -1),
      epochMs,
      rangeM: num(e.rangeM, 0),
    });
  }
  return out;
}

/** Parse the optional `att` array into a Float64Array (stride 5); null when absent. */
function parseAttitude(raw: unknown): Float64Array | null {
  return Array.isArray(raw) && raw.length >= ATT_STRIDE ? Float64Array.from(raw as number[]) : null;
}

/** Parse the optional `sensors` descriptor list; empty when absent/malformed. */
function parseSensors(raw: unknown): SensorDef[] {
  if (!Array.isArray(raw)) return [];
  const out: SensorDef[] = [];
  for (const item of raw) {
    const s = item as {
      id?: unknown;
      kind?: unknown;
      name?: unknown;
      fov?: { type?: unknown; halfAngleDeg?: unknown; hDeg?: unknown; vDeg?: unknown };
      minRangeM?: unknown;
      maxRangeM?: unknown;
      mount?: { boresightBody?: unknown; clockDeg?: unknown };
    };
    if (typeof s.id !== 'string') continue;
    const b = Array.isArray(s.mount?.boresightBody) ? (s.mount!.boresightBody as number[]) : [1, 0, 0];
    out.push({
      id: s.id,
      kind: typeof s.kind === 'string' ? s.kind : 'optical',
      name: typeof s.name === 'string' ? s.name : 'sensor',
      fov: {
        type: typeof s.fov?.type === 'string' ? s.fov.type : 'cone',
        halfAngleDeg: num(s.fov?.halfAngleDeg, 10),
        hDeg: num(s.fov?.hDeg, 0),
        vDeg: num(s.fov?.vDeg, 0),
      },
      minRangeM: num(s.minRangeM, 0),
      maxRangeM: num(s.maxRangeM, 1000),
      mount: { boresightBody: [num(b[0], 1), num(b[1], 0), num(b[2], 0)], clockDeg: num(s.mount?.clockDeg, 0) },
    });
  }
  return out;
}

/** Parse the optional top-level `chief` block (attitude + sensors); null when absent. */
function parseChief(raw: unknown): ChiefRelative | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const c = raw as { noradId?: unknown; att?: unknown; sensors?: unknown };
  return {
    noradId: typeof c.noradId === 'number' ? c.noradId : -1,
    attitude: parseAttitude(c.att),
    sensors: parseSensors(c.sensors),
  };
}

function num(v: unknown, fallback: number): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : fallback;
}
