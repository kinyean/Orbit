import { useState, type PointerEvent as ReactPointerEvent } from 'react';
import { useStore, type MonteCarloParams } from '../store/useStore';
import { usePanelSize, usePanelPosition } from '../lib/usePanelChrome';

/**
 * Monte Carlo dispersion panel (Phase 9C, UC-6, US-MC-01/02). Authors a seeded dispersion
 * (1-σ initial-state uncertainty + maneuver execution error), runs it via the one-shot REST
 * analysis, and surfaces the result (held in Zustand) — the proximity view draws the cloud
 * + covariance ellipsoids. The explicit seed makes a run reproducible (SRS §5.4.1).
 */
export default function MonteCarloPanel() {
  const loaded = useStore((s) => s.loadedScenario);
  const runMonteCarlo = useStore((s) => s.runMonteCarlo);
  const result = useStore((s) => s.monteCarlo);
  const visible = useStore((s) => s.monteCarloVisible);
  const setVisible = useStore((s) => s.setMonteCarloVisible);
  const clear = useStore((s) => s.clearMonteCarlo);

  const [deputyId, setDeputyId] = useState<number | null>(null);
  const [sampleCount, setSampleCount] = useState('100');
  const [seed, setSeed] = useState('1');
  const [posSigma, setPosSigma] = useState('100');
  const [velSigma, setVelSigma] = useState('0.1');
  const [dvMagFrac, setDvMagFrac] = useState('0.02');
  const [dvPointingDeg, setDvPointingDeg] = useState('0.5');
  const [running, setRunning] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  const { pos, setPos, commitPos } = usePanelPosition('montecarlo', { x: 396, y: 186 });
  const panelRef = usePanelSize<HTMLElement>('montecarlo', false);

  function onDragStart(e: ReactPointerEvent) {
    if ((e.target as HTMLElement).closest('button')) return;
    e.preventDefault();
    const startX = e.clientX;
    const startY = e.clientY;
    const origin = { ...pos };
    let last = origin;
    const move = (ev: PointerEvent) => {
      last = {
        x: Math.min(window.innerWidth - 80, Math.max(0, origin.x + (ev.clientX - startX))),
        y: Math.min(window.innerHeight - 60, Math.max(0, origin.y + (ev.clientY - startY))),
      };
      setPos(last);
    };
    const up = () => {
      commitPos(last);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  if (!loaded) return null;
  const deputies = loaded.body.deputies ?? [];
  if (deputies.length === 0) return null;
  const selected = deputyId ?? deputies[0]?.noradId ?? null;

  async function onRun() {
    if (selected == null) return;
    const params: MonteCarloParams = {
      sampleCount: Math.max(1, Math.round(Number(sampleCount) || 100)),
      seed: Math.round(Number(seed) || 0),
      posSigmaM: Number(posSigma) || 0,
      velSigmaMs: Number(velSigma) || 0,
      dvMagFrac: Number(dvMagFrac) || 0,
      dvPointingDeg: Number(dvPointingDeg) || 0,
    };
    setRunning(true);
    setMsg(`Running ${params.sampleCount} samples…`);
    const err = await runMonteCarlo(selected, params);
    setRunning(false);
    setMsg(err ?? null);
  }

  // Largest 3-σ semi-axis across all epochs — a one-number "how wide is the dispersion".
  const maxExtent = (result?.ellipsoids ?? []).reduce((m, e) => {
    const a = e.semiAxes3Sigma ?? [];
    return Math.max(m, a[0] ?? 0, a[1] ?? 0, a[2] ?? 0);
  }, 0);

  return (
    <aside ref={panelRef} className="maneuver-panel" style={{ left: pos.x, top: pos.y }}>
      <div className="mvr-drag" onPointerDown={onDragStart} title="Drag to move">
        <span className="mvr-drag-title"><span className="mvr-grip" aria-hidden>⠿</span> Monte Carlo · dispersion</span>
        <button className="panel-min" onClick={() => useStore.getState().closePanel('montecarlo')} title="Close" aria-label="Close">
          ✕
        </button>
      </div>
        <>
          <div className="mvr-add">
            <select value={selected ?? ''} onChange={(e) => setDeputyId(Number(e.target.value))} aria-label="Deputy">
              {deputies.map((d, idx) => (
                <option key={d.noradId ?? idx} value={d.noradId ?? ''}>{d.name ?? `NORAD ${d.noradId}`}</option>
              ))}
            </select>
            <div className="mvr-ric">
              <label>samples<input title="Number of dispersion samples — each is a full numerical propagation" type="number" step="1" min={1} value={sampleCount} onChange={(e) => setSampleCount(e.target.value)} /></label>
              <label>seed<input title="RNG seed — the same seed reproduces the run bit-identically" type="number" step="1" value={seed} onChange={(e) => setSeed(e.target.value)} /></label>
            </div>
            <div className="mvr-ric">
              <label>σ pos (m)<input title="1-σ initial position uncertainty, m" type="number" step="any" value={posSigma} onChange={(e) => setPosSigma(e.target.value)} /></label>
              <label>σ vel (m/s)<input title="1-σ initial velocity uncertainty, m/s" type="number" step="any" value={velSigma} onChange={(e) => setVelSigma(e.target.value)} /></label>
            </div>
            <div className="mvr-ric">
              <label>Δv mag frac<input title="1-σ ΔV magnitude execution error, as a fraction of the burn" type="number" step="any" value={dvMagFrac} onChange={(e) => setDvMagFrac(e.target.value)} /></label>
              <label>Δv point (°)<input title="1-σ ΔV pointing execution error, degrees" type="number" step="any" value={dvPointingDeg} onChange={(e) => setDvPointingDeg(e.target.value)} /></label>
            </div>
            <button title="Run the seeded dispersion analysis" type="button" onClick={() => void onRun()} disabled={running}>
              {running ? 'Running…' : 'Run dispersion'}
            </button>
            <div className="mvr-note">
              Each sample is a full numerical propagation — higher counts take longer. The
              seed makes the run reproducible.
            </div>
          </div>

          {result && (
            <div className="mvr-deputy">
              <div className="mvr-deputy-head">
                <span className="mvr-deputy-name">{result.name ?? `NORAD ${result.deputyNoradId}`}</span>
                <span className="mvr-budget">seed {result.seed}</span>
              </div>
              <div className="mvr-note">
                {result.sampleCount} samples · showing {result.returnedTracks} tracks ·
                max 3-σ extent {maxExtent >= 1000 ? `${(maxExtent / 1000).toFixed(2)} km` : `${maxExtent.toFixed(0)} m`}
              </div>
              <label className="mvr-epoch-field" style={{ flexDirection: 'row', gap: 6, alignItems: 'center' }}>
                <input title="Show or hide the trajectory cloud + 3σ covariance ellipsoids" type="checkbox" checked={visible} onChange={(e) => setVisible(e.target.checked)} />
                <span>show cloud + ellipsoids</span>
              </label>
              <button title="Discard this dispersion result" type="button" onClick={() => clear()}>Clear</button>
            </div>
          )}
          {msg && <div className="mvr-msg">{msg}</div>}
        </>
    </aside>
  );
}
