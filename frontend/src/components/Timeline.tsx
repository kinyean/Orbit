import { useRef, useState, useSyncExternalStore, type MouseEvent as ReactMouseEvent } from 'react';
import { useStore } from '../store/useStore';
import {
  getRelativeData,
  getRelativeVersion,
  subscribeRelative,
  buildEclipseIntervals,
  type SensorEvent,
} from '../stream/relativeBuffer';

// Same deputy palette as ProximityView / RelativeReadout (color identity).
const DEPUTY_COLORS = [
  '#38bdf8', '#ff922b', '#a3e635', '#e879f9', '#2dd4bf', '#f472b6', '#818cf8', '#facc15',
];

interface FovWindow {
  key: string;
  startMs: number;
  endMs: number;
  label: string;
}

/**
 * Pair acquisition→loss events per (host,sensor,target) into in-view windows
 * (Phase 7, US-EVT-01). A dangling acquisition runs to the scenario end; a leading
 * loss runs from the start (the target was already in view at t=0).
 */
function buildFovWindows(events: SensorEvent[], lo: number, hi: number): FovWindow[] {
  const byKey = new Map<string, SensorEvent[]>();
  for (const e of events) {
    const k = `${e.hostId}|${e.sensorId}|${e.targetId}`;
    (byKey.get(k) ?? byKey.set(k, []).get(k)!).push(e);
  }
  const windows: FovWindow[] = [];
  for (const [k, list] of byKey) {
    list.sort((a, b) => a.epochMs - b.epochMs);
    let open: number | null = null;
    for (const e of list) {
      if (e.type === 'acquisition') {
        open = e.epochMs;
      } else if (open !== null) {
        windows.push({ key: `${k}-${e.epochMs}`, startMs: open, endMs: e.epochMs, label: k });
        open = null;
      } else {
        windows.push({ key: `${k}-lead-${e.epochMs}`, startMs: lo, endMs: e.epochMs, label: k });
      }
    }
    if (open !== null) windows.push({ key: `${k}-tail`, startMs: open, endMs: hi, label: k });
  }
  return windows;
}

/** Compact UTC label for the scrub-bar endpoints. */
function fmt(d: Date): string {
  return d.toISOString().slice(0, 16).replace('T', ' ');
}

/**
 * Scenario scrub bar (US-VIEW-04). Shows the scenario's start … end and the
 * current epoch as the thumb; dragging seeks the shared clock (pure client-side
 * over the precomputed samples → ≤200 ms input-to-frame). Only rendered when a
 * scenario window is active (bounds set); the live catalog regime has none.
 * Event annotations (maneuvers/eclipses) are later phases — the bar leaves room.
 */
export default function Timeline() {
  const bounds = useStore((s) => s.bounds);
  const currentTime = useStore((s) => s.currentTime);
  const seek = useStore((s) => s.seek);
  const loaded = useStore((s) => s.loadedScenario);
  // Re-render when the relative buffer (which carries per-deputy TCA) changes.
  useSyncExternalStore(subscribeRelative, getRelativeVersion);

  // Custom hover tooltip: the band/mark spans are pointer-events:none (so they
  // never steal the scrub drag), which also kills native `title` tooltips — so we
  // drive our own off the track's mousemove. This also lets it list EVERY band
  // active at the cursor time (overlapping craft, umbra vs penumbra) at once.
  const trackRef = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<{ leftPct: number; lines: string[] } | null>(null);

  if (!bounds) return null;

  // NORAD id → display name + sensor id → name, for human-readable hover tooltips.
  // (The relative stream carries ids; names live in the loaded scenario body.)
  const nameById = new Map<number, string>();
  const sensorNameById = new Map<string, string>();
  for (const role of loaded ? [loaded.body.chief, ...(loaded.body.deputies ?? [])] : []) {
    if (role?.noradId != null) nameById.set(role.noradId, role.name ?? `NORAD ${role.noradId}`);
    for (const s of role?.sensors ?? []) if (s.id) sensorNameById.set(s.id, s.name ?? s.kind ?? 'sensor');
  }
  const nm = (id: number) => nameById.get(id) ?? `NORAD ${id}`;
  const hhmm = (ms: number) => `${new Date(ms).toISOString().slice(11, 16)}Z`;

  const lo = bounds.start.getTime();
  const hi = bounds.end.getTime();
  const span = Math.max(1, hi - lo);
  const frac = Math.min(1, Math.max(0, (currentTime.getTime() - lo) / span));

  // Closest-approach ticks (US-REL-02): one per deputy with a TCA in range.
  const rel = getRelativeData();
  const tcaTicks = (rel?.deputies ?? [])
    .map((dep, idx) => ({ dep, idx }))
    .filter(({ dep }) => dep.tcaEpochMs !== null && dep.tcaEpochMs >= lo && dep.tcaEpochMs <= hi);

  // Sensor in-view windows (US-EVT-01): translucent bands between AOS and LOS.
  const fovWindows = buildFovWindows(rel?.events ?? [], lo, hi)
    .filter((w) => w.endMs >= lo && w.startMs <= hi);

  // Eclipse bands (US-ENV-02 / UC-5): penumbra (grey) + umbra (dark) per craft.
  // Draw penumbra first so the narrower umbra band sits on top of it.
  const eclipseBands = buildEclipseIntervals(rel?.eclipses ?? [], lo, hi)
    .filter((e) => e.endMs >= lo && e.startMs <= hi)
    .sort((a, b) => (a.kind === b.kind ? 0 : a.kind === 'penumbra' ? -1 : 1));

  // Conjunction TCA ticks (US-EVT-02) + constraint-violation marks (US-EVT-03, at the
  // start of each violation) within the window.
  const conjunctions = (rel?.conjunctions ?? []).filter((c) => c.tcaEpochMs >= lo && c.tcaEpochMs <= hi);
  const violationStarts = (rel?.violations ?? []).filter(
    (v) => v.type === 'violation-start' && v.epochMs >= lo && v.epochMs <= hi,
  );

  // What is happening at the cursor time? (drives the hover tooltip). Lists the cursor
  // time, every band the cursor is inside, and any instant marks within ~8px.
  function describeAt(clientX: number): { leftPct: number; lines: string[] } | null {
    const el = trackRef.current;
    if (!el) return null;
    const rect = el.getBoundingClientRect();
    if (rect.width <= 0) return null;
    const frac = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    const tMs = lo + frac * span;
    const tol = (8 / rect.width) * span; // ~8px hit radius for point marks
    const lines: string[] = [`◷ ${hhmm(tMs)}`];
    for (const b of eclipseBands) {
      if (tMs >= b.startMs && tMs <= b.endMs) {
        lines.push(`${b.kind === 'umbra' ? '● umbra (full shadow)' : '◐ penumbra (partial)'} — ${nm(b.noradId)}`);
      }
    }
    for (const w of fovWindows) {
      if (tMs >= w.startMs && tMs <= w.endMs) {
        const [h, s, t] = w.label.split('|');
        lines.push(`▣ in FOV — ${sensorNameById.get(s) ?? 'sensor'} on ${nm(Number(h))} sees ${nm(Number(t))}`);
      }
    }
    for (const c of conjunctions) {
      if (Math.abs(c.tcaEpochMs - tMs) <= tol) {
        lines.push(`◆ conjunction — ${nm(c.aNoradId)} ↔ ${nm(c.bNoradId)} · ${(c.missDistanceM / 1000).toFixed(2)} km`);
      }
    }
    for (const v of violationStarts) {
      if (Math.abs(v.epochMs - tMs) <= tol) {
        lines.push(
          v.kind === 'sun-keep-out'
            ? `▮ sun-keep-out — ${nm(v.hostId)} · Sun ${v.valueDeg.toFixed(1)}° < ${v.limitDeg.toFixed(0)}°`
            : `▮ corridor — ${nm(v.targetId)} left ${nm(v.hostId)} · ${v.valueDeg.toFixed(1)}° > ${v.limitDeg.toFixed(0)}°`,
        );
      }
    }
    for (const { dep } of tcaTicks) {
      if (Math.abs((dep.tcaEpochMs as number) - tMs) <= tol) {
        lines.push(`╎ closest approach — ${dep.name}${dep.tcaDistanceM !== null ? ` · ${(dep.tcaDistanceM / 1000).toFixed(2)} km` : ''}`);
      }
    }
    return { leftPct: frac * 100, lines };
  }

  const onMove = (e: ReactMouseEvent) => setHover(describeAt(e.clientX));

  return (
    <div className="timeline">
      <span className="timeline-label">{fmt(bounds.start)}</span>
      <div
        className="timeline-track"
        ref={trackRef}
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
      >
        {eclipseBands.map((e, idx) => {
          const a = Math.max(lo, e.startMs);
          const b = Math.min(hi, e.endMs);
          return (
            <span
              key={`ecl-${e.noradId}-${e.kind}-${idx}`}
              className={e.kind === 'umbra' ? 'timeline-eclipse-umbra' : 'timeline-eclipse-penumbra'}
              style={{ left: `${((a - lo) / span) * 100}%`, width: `${((b - a) / span) * 100}%` }}
              title={`Eclipse · ${nm(e.noradId)} in ${
                e.kind === 'umbra' ? 'umbra (full Earth shadow)' : 'penumbra (partial shadow)'
              } · ${hhmm(e.startMs)}–${hhmm(e.endMs)}`}
            />
          );
        })}
        {fovWindows.map((w) => {
          const a = Math.max(lo, w.startMs);
          const b = Math.min(hi, w.endMs);
          return (
            <span
              key={w.key}
              className="timeline-fov"
              style={{ left: `${((a - lo) / span) * 100}%`, width: `${((b - a) / span) * 100}%` }}
              title={(() => {
                const [h, s, t] = w.label.split('|');
                const sensor = sensorNameById.get(s) ?? 'sensor';
                return `Sensor in view · ${sensor} on ${nm(Number(h))} sees ${nm(Number(t))} · ${hhmm(a)}–${hhmm(b)}`;
              })()}
            />
          );
        })}
        {tcaTicks.map(({ dep, idx }) => (
          <span
            key={dep.noradId}
            className="timeline-tca"
            style={{
              left: `${(((dep.tcaEpochMs as number) - lo) / span) * 100}%`,
              background: DEPUTY_COLORS[idx % DEPUTY_COLORS.length],
            }}
            title={`Closest approach · ${dep.name}${
              dep.tcaDistanceM !== null ? ` · ${(dep.tcaDistanceM / 1000).toFixed(2)} km` : ''
            } · ${hhmm(dep.tcaEpochMs as number)}`}
          />
        ))}
        {conjunctions.map((c) => (
          <span
            key={`conj-${c.aNoradId}-${c.bNoradId}-${c.tcaEpochMs}`}
            className="timeline-conjunction"
            style={{ left: `${((c.tcaEpochMs - lo) / span) * 100}%` }}
            title={`Conjunction (close approach) · ${nm(c.aNoradId)} ↔ ${nm(c.bNoradId)} · ${(c.missDistanceM / 1000).toFixed(2)} km · ${hhmm(c.tcaEpochMs)}`}
          />
        ))}
        {violationStarts.map((v) => (
          <span
            key={`viol-${v.constraintId}-${v.epochMs}`}
            className="timeline-violation"
            style={{ left: `${((v.epochMs - lo) / span) * 100}%` }}
            title={
              v.kind === 'sun-keep-out'
                ? `Sun-keep-out violation · ${nm(v.hostId)}${v.sensorId ? ` (${sensorNameById.get(v.sensorId) ?? 'sensor'})` : ''} · Sun ${v.valueDeg.toFixed(1)}° < ${v.limitDeg.toFixed(0)}° keep-out · from ${hhmm(v.epochMs)}`
                : `Approach-corridor violation · ${nm(v.targetId)} left ${nm(v.hostId)}'s corridor · ${v.valueDeg.toFixed(1)}° > ${v.limitDeg.toFixed(0)}° · from ${hhmm(v.epochMs)}`
            }
          />
        ))}
        <input
          type="range"
          className="timeline-scrub"
          min={0}
          max={1000}
          value={Math.round(frac * 1000)}
          onChange={(e) => seek(new Date(lo + (Number(e.target.value) / 1000) * span))}
          aria-label="Timeline scrubber"
        />
        {hover && (
          <div
            className="timeline-tooltip"
            style={{ left: `${Math.min(92, Math.max(8, hover.leftPct))}%` }}
          >
            {hover.lines.map((line, i) => (
              <div key={i} className={i === 0 ? 'timeline-tooltip-time' : undefined}>
                {line}
              </div>
            ))}
          </div>
        )}
      </div>
      <span className="timeline-label">{fmt(bounds.end)}</span>
    </div>
  );
}
