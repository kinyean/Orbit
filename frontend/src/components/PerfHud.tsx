import { useEffect, useReducer } from 'react';
import { snapshot, subscribePerf } from '../lib/perf';

/**
 * Performance HUD (Phase 11, US-UX-03 — SRS §5.1). A small read-only overlay
 * showing live FPS per view, scrub latency, and the last scenario-load time —
 * the instruments behind the §5.1 acceptance evidence. Toggled from the stats
 * overlay's ⏱ button (or `?perf=1`); refreshes at 2 Hz off the perf singleton.
 */
export default function PerfHud() {
  const [, bump] = useReducer((n: number) => n + 1, 0);
  useEffect(() => subscribePerf(bump), []);

  const s = snapshot();
  const fps = (v: number | null) => (v === null ? '—' : v.toFixed(0));
  const ms = (v: number | null) => (v === null ? '—' : `${v.toFixed(0)} ms`);
  const sec = (v: number | null) => (v === null ? '—' : `${(v / 1000).toFixed(2)} s`);
  const cls = (v: number | null, bad: (n: number) => boolean) =>
    v !== null && bad(v) ? 'perf-bad' : '';

  return (
    <div className="perf-hud" title="§5.1 targets: proximity ≥60 fps · globe ≥30 fps · scrub ≤200 ms · 24 h load ≤5 s">
      <div className="perf-row">
        <span>globe</span>
        <span className={cls(s.globeFps, (n) => n < 29)}>{fps(s.globeFps)} fps</span>
      </div>
      <div className="perf-row">
        <span>proximity</span>
        <span className={cls(s.proxFps, (n) => n < 59)}>{fps(s.proxFps)} fps</span>
      </div>
      <div className="perf-row">
        <span>scrub</span>
        <span className={cls(s.scrubP95Ms, (n) => n > 200)}>
          {ms(s.scrubLastMs)}{s.scrubP95Ms !== null ? ` · p95 ${ms(s.scrubP95Ms)}` : ''}
        </span>
      </div>
      <div className="perf-row">
        <span>load</span>
        <span className={cls(s.lastLoadMs, (n) => n > 5000)}>{sec(s.lastLoadMs)}</span>
      </div>
    </div>
  );
}
