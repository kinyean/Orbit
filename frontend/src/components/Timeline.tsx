import { useStore } from '../store/useStore';

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

  if (!bounds) return null;

  const lo = bounds.start.getTime();
  const hi = bounds.end.getTime();
  const span = Math.max(1, hi - lo);
  const frac = Math.min(1, Math.max(0, (currentTime.getTime() - lo) / span));

  return (
    <div className="timeline">
      <span className="timeline-label">{fmt(bounds.start)}</span>
      <input
        type="range"
        className="timeline-scrub"
        min={0}
        max={1000}
        value={Math.round(frac * 1000)}
        onChange={(e) => seek(new Date(lo + (Number(e.target.value) / 1000) * span))}
        aria-label="Timeline scrubber"
      />
      <span className="timeline-label">{fmt(bounds.end)}</span>
    </div>
  );
}
