import { useSyncExternalStore } from 'react';
import { useStore } from '../store/useStore';
import {
  getRelativeData,
  getRelativeVersion,
  subscribeRelative,
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
  // Re-render when the relative buffer (which carries per-deputy TCA) changes.
  useSyncExternalStore(subscribeRelative, getRelativeVersion);

  if (!bounds) return null;

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

  return (
    <div className="timeline">
      <span className="timeline-label">{fmt(bounds.start)}</span>
      <div className="timeline-track">
        {fovWindows.map((w) => {
          const a = Math.max(lo, w.startMs);
          const b = Math.min(hi, w.endMs);
          return (
            <span
              key={w.key}
              className="timeline-fov"
              style={{ left: `${((a - lo) / span) * 100}%`, width: `${((b - a) / span) * 100}%` }}
              title={`sensor in view (host|sensor|target ${w.label})`}
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
            title={`${dep.name} closest approach${
              dep.tcaDistanceM !== null ? ` · ${(dep.tcaDistanceM / 1000).toFixed(2)} km` : ''
            }`}
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
      </div>
      <span className="timeline-label">{fmt(bounds.end)}</span>
    </div>
  );
}
