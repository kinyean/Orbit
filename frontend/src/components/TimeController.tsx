import { useStore } from '../store/useStore';

// Log rate slider: rate = 10^v, v ∈ [-2, 4] → 0.01× … 10000× (SRS §3.3.4).
const MIN_EXP = -2;
const MAX_EXP = 4;
// One "step" button press nudges the clock by this many seconds (then pauses).
const STEP_SECONDS = 60;

function fmtRate(r: number): string {
  if (r >= 100) return `${Math.round(r).toLocaleString()}×`;
  if (r >= 10) return `${r.toFixed(0)}×`;
  if (r >= 1) return `${Number.isInteger(r) ? r.toFixed(0) : r.toFixed(1)}×`;
  return `${r.toFixed(2)}×`;
}

/**
 * Playback transport for the shared simulation clock (US-VIEW-03). Two modes:
 *
 * - **Scenario** (a scenario is loaded): full transport — play/pause, step ±,
 *   reset-to-start, reverse, and a logarithmic 0.01×–10000× rate slider, all
 *   inside the scenario's time window.
 * - **Live catalog** (no scenario): a "● LIVE" toggle (follow real time via the
 *   shared broadcast) plus step ± buttons that freeze the globe and propagate
 *   the catalog to the new instant (Decision 21). Continuous rate/reverse don't
 *   apply to the live catalog, so they're hidden.
 *
 * All writes go through the clock slice; the clockEngine rAF loop applies them
 * (Decision 11 — the frontend owns playback control).
 */
export default function TimeController() {
  const currentTime = useStore((s) => s.currentTime);
  const isPlaying = useStore((s) => s.isPlaying);
  const rate = useStore((s) => s.rate);
  const direction = useStore((s) => s.direction);
  const loadedScenario = useStore((s) => s.loadedScenario);
  const catalogLive = useStore((s) => s.catalogLive);
  const togglePlay = useStore((s) => s.togglePlay);
  const setRate = useStore((s) => s.setRate);
  const toggleDirection = useStore((s) => s.toggleDirection);
  const step = useStore((s) => s.step);
  const resetClock = useStore((s) => s.resetClock);
  const goLive = useStore((s) => s.goLive);
  const toggleCatalogPlayback = useStore((s) => s.toggleCatalogPlayback);

  const display = currentTime.toISOString().slice(0, 19).replace('T', ' ') + ' UTC';
  const exp = Math.log10(rate > 0 ? rate : 0.01);
  const inScenario = loadedScenario !== null;

  if (!inScenario) {
    // Live catalog mode. `● LIVE` follows real time; pausing/stepping/scrubbing
    // freezes the globe at an instant; play then runs forward (or back) from
    // there via rolling propagated snapshots (rate capped at 100×). Rate +
    // reverse only appear once you've left live (they don't apply to realtime).
    return (
      <div className="time-controller">
        <button
          className={catalogLive ? 'ctrl-btn live active' : 'ctrl-btn live'}
          onClick={() => goLive()}
          title={catalogLive ? 'Following real time' : 'Return to real time (now)'}
        >
          {'● LIVE'}
        </button>
        <button className="ctrl-btn" onClick={() => step(-STEP_SECONDS)} title="Step back" aria-label="Step back">
          {'⏮'}
        </button>
        <button
          className="play-btn"
          onClick={toggleCatalogPlayback}
          aria-label={isPlaying ? 'Pause' : 'Play'}
          title={isPlaying ? 'Pause' : 'Play from here'}
        >
          {isPlaying ? '⏸' : '▶'}
        </button>
        <button className="ctrl-btn" onClick={() => step(STEP_SECONDS)} title="Step forward" aria-label="Step forward">
          {'⏭'}
        </button>
        {!catalogLive && (
          <button
            className={direction === -1 ? 'ctrl-btn active' : 'ctrl-btn'}
            onClick={toggleDirection}
            title={direction === -1 ? 'Reverse (playing backward)' : 'Forward'}
            aria-label="Toggle direction"
          >
            {direction === -1 ? '◀◀' : '▶▶'}
          </button>
        )}
        <div className="time-display">{display}</div>
        {catalogLive ? (
          <span className="rate-readout">live</span>
        ) : (
          <div className="rate-control">
            <input
              type="range"
              className="rate-slider"
              min={MIN_EXP}
              max={2} /* 100× cap in live mode */
              step={0.01}
              value={Math.min(2, exp)}
              onChange={(e) => setRate(Math.pow(10, Number(e.target.value)))}
              aria-label="Playback rate"
            />
            <span className="rate-readout">{fmtRate(rate)}</span>
          </div>
        )}
      </div>
    );
  }

  // Scenario mode — full transport.
  return (
    <div className="time-controller">
      <button className="ctrl-btn" onClick={resetClock} title="Reset to start" aria-label="Reset">
        {'↻'}
      </button>
      <button className="ctrl-btn" onClick={() => step(-STEP_SECONDS)} title="Step back" aria-label="Step back">
        {'⏮'}
      </button>
      <button className="play-btn" onClick={togglePlay} aria-label={isPlaying ? 'Pause' : 'Play'}>
        {isPlaying ? '⏸' : '▶'}
      </button>
      <button className="ctrl-btn" onClick={() => step(STEP_SECONDS)} title="Step forward" aria-label="Step forward">
        {'⏭'}
      </button>
      <button
        className={direction === -1 ? 'ctrl-btn active' : 'ctrl-btn'}
        onClick={toggleDirection}
        title={direction === -1 ? 'Reverse (playing backward)' : 'Forward'}
        aria-label="Toggle direction"
      >
        {direction === -1 ? '◀◀' : '▶▶'}
      </button>

      <div className="time-display">{display}</div>

      <div className="rate-control">
        <input
          type="range"
          className="rate-slider"
          min={MIN_EXP}
          max={MAX_EXP}
          step={0.01}
          value={exp}
          onChange={(e) => setRate(Math.pow(10, Number(e.target.value)))}
          aria-label="Playback rate"
        />
        <span className="rate-readout">{fmtRate(rate)}</span>
      </div>
    </div>
  );
}
