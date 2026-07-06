import { useRef, useState, useSyncExternalStore, type PointerEvent as ReactPointerEvent } from 'react';
import { api } from '../api/client';
import { useStore } from '../store/useStore';
import { usePanelPosition, usePanelSize } from '../lib/usePanelChrome';
import { getRelativeData, getRelativeVersion, subscribeRelative } from '../stream/relativeBuffer';
import { snapshotPng, type SnapshotSource } from './capture';
import { downloadBlob, slugify } from './download';
import { exportEventsCsv, exportEventsJson } from './eventsExport';
import { exportMp4, isMp4Supported, mp4FrameCount, MP4_MAX_FRAMES, type Mp4ExportHandle } from './mp4Exporter';

/**
 * Export panel (Phase 11B — SRS §4.2): PNG snapshots (US-IO-01), MP4 sequence
 * export (US-IO-02), scenario-events JSON/CSV (US-IO-07), CCSDS OEM ephemeris
 * (US-IO-06). Always mounted — a catalog-only snapshot is valid; the video /
 * events / OEM sections light up with a loaded scenario.
 *
 * `proximityMounted` comes from App (the proximity view is local App state) so
 * the proximity/both options disable when that view isn't rendering.
 */
export default function ExportPanel({ proximityMounted }: { proximityMounted: boolean }) {
  const loaded = useStore((s) => s.loadedScenario);

  const [mp4Source, setMp4Source] = useState<SnapshotSource>('both');
  const [rangeMode, setRangeMode] = useState<'full' | 'hour'>('full');
  const [speed, setSpeed] = useState('60'); // sim-seconds per video-second
  const [fps, setFps] = useState('30');
  const [progress, setProgress] = useState<{ frame: number; total: number } | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [oemBusy, setOemBusy] = useState(false);
  const jobRef = useRef<Mp4ExportHandle | null>(null);

  // Re-render when the stream buffer is replaced (enables the events buttons).
  useSyncExternalStore(subscribeRelative, getRelativeVersion);
  const relData = getRelativeData();

  const { pos, setPos, commitPos } = usePanelPosition('export', { x: 460, y: 250 });
  const panelRef = usePanelSize<HTMLElement>('export', false);

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

  const proximityAvailable = !!loaded && proximityMounted;
  const mp4Supported = isMp4Supported();

  // Scenario window (ms) for the video range options.
  const winStart = loaded?.body.timeRange?.start ? Date.parse(loaded.body.timeRange.start) : NaN;
  const winEnd = loaded?.body.timeRange?.end ? Date.parse(loaded.body.timeRange.end) : NaN;
  const hasWindow = Number.isFinite(winStart) && Number.isFinite(winEnd) && winEnd > winStart;

  function videoRange(): [number, number] {
    if (rangeMode === 'hour') {
      const nowMs = useStore.getState().currentTime.getTime();
      const lo = Math.max(winStart, nowMs - 1800_000);
      const hi = Math.min(winEnd, lo + 3600_000);
      return [Math.min(lo, hi), hi];
    }
    return [winStart, winEnd];
  }

  const speedNum = Math.max(0.01, Number(speed) || 60);
  const fpsNum = Number(fps) === 24 ? 24 : 30;
  const [rLo, rHi] = hasWindow ? videoRange() : [0, 0];
  const frames = hasWindow ? mp4FrameCount(rHi - rLo, speedNum, fpsNum) : 0;
  const clipSeconds = frames / fpsNum;
  const clamped = hasWindow && frames >= MP4_MAX_FRAMES;

  async function onSnapshot(source: SnapshotSource) {
    setMsg(null);
    try {
      const st = useStore.getState();
      await snapshotPng(source, st.loadedScenario?.name ?? null, st.currentTime);
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    }
  }

  async function onExportMp4() {
    if (!loaded || !hasWindow || progress) return;
    setMsg(null);
    const [startMs, endMs] = videoRange();
    const job = exportMp4({
      source: mp4Source,
      startMs,
      endMs,
      simSecondsPerVideoSecond: speedNum,
      fps: fpsNum,
      scenarioName: loaded.name,
      onProgress: (frame, total) => setProgress({ frame, total }),
    });
    jobRef.current = job;
    setProgress({ frame: 0, total: frames });
    try {
      await job.done;
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    } finally {
      jobRef.current = null;
      setProgress(null);
    }
  }

  function onEvents(format: 'json' | 'csv') {
    setMsg(null);
    if (!loaded || !relData) return;
    try {
      if (format === 'json') exportEventsJson(loaded, relData);
      else exportEventsCsv(loaded, relData);
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    }
  }

  async function onExportOem() {
    if (!loaded || oemBusy) return;
    setMsg(null);
    setOemBusy(true);
    try {
      // Fetched (not an <a href>) so the Bearer middleware applies in oidc mode.
      const res = await api.GET('/scenarios/{id}/export/oem', {
        params: { path: { id: loaded.id } },
        parseAs: 'text',
      });
      if (res.error !== undefined || typeof res.data !== 'string') {
        throw new Error('OEM export failed — is the scenario still available?');
      }
      downloadBlob(new Blob([res.data], { type: 'text/plain' }), `${slugify(loaded.name)}.oem`);
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    } finally {
      setOemBusy(false);
    }
  }

  return (
    <aside
      ref={panelRef}
      className="maneuver-panel"
      style={{ left: pos.x, top: pos.y }}
    >
      <div className="mvr-drag" onPointerDown={onDragStart} title="Drag to move">
        <span className="mvr-drag-title">
          <span className="mvr-grip" aria-hidden>⠿</span> Export · PNG / MP4 / data
        </span>
        <button
          className="panel-min"
          onClick={() => useStore.getState().closePanel('export')}
          title="Close"
          aria-label="Close"
        >
          ✕
        </button>
      </div>
        <>
          <div className="mvr-add">
            <div className="mvr-deputy-head">
              <span className="mvr-deputy-name">Snapshot (PNG)</span>
            </div>
            <div className="mvr-ric" style={{ gap: 6 }}>
              <button type="button" onClick={() => void onSnapshot('globe')} title="Capture the global view as a PNG">
                Global
              </button>
              <button
                type="button"
                onClick={() => void onSnapshot('proximity')}
                disabled={!proximityAvailable}
                title={proximityAvailable ? 'Capture the proximity view as a PNG' : 'Load a scenario (proximity view) first'}
              >
                Proximity
              </button>
              <button
                type="button"
                onClick={() => void onSnapshot('both')}
                disabled={!proximityAvailable}
                title={proximityAvailable ? 'Capture both views side-by-side' : 'Load a scenario (proximity view) first'}
              >
                Both
              </button>
            </div>
          </div>

          <div className="mvr-add">
            <div className="mvr-deputy-head">
              <span className="mvr-deputy-name">Video (MP4)</span>
            </div>
            {!loaded && <div className="mvr-note">Load a scenario to export a video sequence.</div>}
            {loaded && !mp4Supported && (
              <div className="mvr-note">
                MP4 export needs WebCodecs H.264 — use a Chromium-based browser (Chrome/Edge).
              </div>
            )}
            {loaded && mp4Supported && hasWindow && (
              <>
                <div className="mvr-ric">
                  <label>
                    source
                    <select
                      value={mp4Source}
                      onChange={(e) => setMp4Source(e.target.value as SnapshotSource)}
                      title="Which viewport(s) to record"
                    >
                      <option value="both" disabled={!proximityAvailable}>Both views</option>
                      <option value="globe">Global view</option>
                      <option value="proximity" disabled={!proximityAvailable}>Proximity view</option>
                    </select>
                  </label>
                  <label>
                    range
                    <select
                      value={rangeMode}
                      onChange={(e) => setRangeMode(e.target.value as 'full' | 'hour')}
                      title="Sim-time span the video covers"
                    >
                      <option value="full">Full scenario</option>
                      <option value="hour">1 h around now</option>
                    </select>
                  </label>
                </div>
                <div className="mvr-ric">
                  <label>
                    speed (sim-s / video-s)
                    <input
                      type="number"
                      step="any"
                      min={0.01}
                      value={speed}
                      onChange={(e) => setSpeed(e.target.value)}
                      title="Video playback speed: how many simulation seconds pass per video second"
                    />
                  </label>
                  <label>
                    fps
                    <select value={fps} onChange={(e) => setFps(e.target.value)} title="Output frame rate">
                      <option value="30">30</option>
                      <option value="24">24</option>
                    </select>
                  </label>
                </div>
                <div className="mvr-note">
                  {frames} frames ≈ {clipSeconds.toFixed(1)} s of video
                  {clamped ? ` (clamped to ${MP4_MAX_FRAMES} frames — raise the speed to cover the full range)` : ''}.
                  Rendered offline frame-by-frame — smooth regardless of live FPS; distant imagery may
                  refine over the first frames.
                </div>
                {progress === null ? (
                  <button type="button" onClick={() => void onExportMp4()} title="Render and download the MP4">
                    Export MP4
                  </button>
                ) : (
                  <>
                    <div className="mvr-note">
                      Rendering frame {progress.frame} / {progress.total}…
                    </div>
                    <button type="button" onClick={() => jobRef.current?.cancel()} title="Stop the export and restore playback">
                      Cancel
                    </button>
                  </>
                )}
              </>
            )}
          </div>

          <div className="mvr-add">
            <div className="mvr-deputy-head">
              <span className="mvr-deputy-name">Ephemeris (CCSDS OEM)</span>
            </div>
            {!loaded ? (
              <div className="mvr-note">Load a scenario — exports every craft&apos;s propagated ephemeris (EME2000/UTC) for downstream tools.</div>
            ) : (
              <>
                <div className="mvr-ric" style={{ gap: 6 }}>
                  <button
                    type="button"
                    onClick={() => void onExportOem()}
                    disabled={oemBusy}
                    title="Download the propagated ephemerides as a CCSDS OEM file (recorded in the audit trail)"
                  >
                    {oemBusy ? 'Exporting…' : 'Download .oem'}
                  </button>
                </div>
                <div className="mvr-note">One segment per craft; maneuvered deputies carry the real post-burn trajectory.</div>
              </>
            )}
          </div>

          <div className="mvr-add">
            <div className="mvr-deputy-head">
              <span className="mvr-deputy-name">Events (JSON / CSV)</span>
            </div>
            {!loaded || !relData ? (
              <div className="mvr-note">Load a scenario — exports the streamed event set (AOS/LOS, eclipse, conjunction, violations, TCA).</div>
            ) : (
              <div className="mvr-ric" style={{ gap: 6 }}>
                <button type="button" onClick={() => onEvents('json')} title="Download all scenario events as JSON">
                  JSON
                </button>
                <button type="button" onClick={() => onEvents('csv')} title="Download all scenario events as CSV">
                  CSV
                </button>
              </div>
            )}
          </div>

          {msg && <div className="mvr-msg">{msg}</div>}
        </>
    </aside>
  );
}
