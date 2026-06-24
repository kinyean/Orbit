import { useState, useSyncExternalStore, type FormEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { useStore, type ConstraintRequest, type ScreeningResult } from '../store/useStore';
import { useCollapsed, usePanelSize, usePanelPosition } from '../lib/usePanelChrome';
import { getRelativeData, getRelativeVersion, subscribeRelative } from '../stream/relativeBuffer';

/** Parse a raw numeric-field string; NaN for empty/invalid (callers guard on > 0). */
function num(s: string): number {
  return parseFloat(s);
}

/** Build + download a CSV of the screening results (UC-7 step 5). */
function exportScreeningCsv(res: ScreeningResult): void {
  const header = 'scenarioNoradId,scenarioName,catalogNoradId,catalogName,tcaEpoch,missDistanceKm';
  const rows = (res.conjunctions ?? []).map((c) =>
    [c.scenarioNoradId, JSON.stringify(c.scenarioName ?? ''), c.catalogNoradId,
     JSON.stringify(c.catalogName ?? ''), c.tcaEpoch, ((c.missDistanceM ?? 0) / 1000).toFixed(3)].join(','),
  );
  const blob = new Blob([[header, ...rows].join('\n')], { type: 'text/csv' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'conjunction-screening.csv';
  a.click();
  URL.revokeObjectURL(url);
}

const CHIEF_COLOR = '#ffd166';
const DEPUTY_COLORS = [
  '#38bdf8', '#ff922b', '#a3e635', '#e879f9', '#2dd4bf', '#f472b6', '#818cf8', '#facc15',
];

type Kind = 'sun-keep-out' | 'approach-corridor';

/**
 * Environment & events panel (Phase 8, US-EVT-02/03/04). Authors the conjunction
 * miss-distance threshold and per-host constraints (sun-keep-out / approach-corridor)
 * through the audited store actions — edits re-propagate (the store reloads), so the
 * eclipse bands, conjunction ticks, and violation marks on the timeline + the
 * Sun-driven lighting in the proximity view all update. Mirrors SensorPanel's
 * draggable/collapsible chrome. A live count of detected conjunctions/violations is
 * read from the relative buffer (Decision 5 — not Zustand).
 */
export default function EnvironmentPanel() {
  const loaded = useStore((s) => s.loadedScenario);
  const addConstraint = useStore((s) => s.addConstraint);
  const removeConstraint = useStore((s) => s.removeConstraint);
  const setMissDistance = useStore((s) => s.setMissDistance);
  const screenCatalog = useStore((s) => s.screenCatalog);
  const seek = useStore((s) => s.seek);
  const requestFocus = useStore((s) => s.requestFocus);

  // Re-render when the relative buffer changes (conjunction/violation counts).
  useSyncExternalStore(subscribeRelative, getRelativeVersion);
  const rel = getRelativeData();

  const [hostId, setHostId] = useState<number | null>(null);
  const [kind, setKind] = useState<Kind>('sun-keep-out');
  const [sensorId, setSensorId] = useState<string>('');
  const [targetId, setTargetId] = useState<number | null>(null);
  // Numeric fields are held as RAW STRINGS so they can be cleared/edited freely —
  // binding a number-input to a number snaps an empty field back to 0 (you could
  // never delete the 0 to type a fresh value). They're parsed where used.
  const [limitDeg, setLimitDeg] = useState('20');
  const [rangeKm, setRangeKm] = useState('5');
  const [thresholdKm, setThresholdKm] = useState('5');
  const [msg, setMsg] = useState<string | null>(null);
  const { pos, setPos, commitPos } = usePanelPosition('environment', { x: 248, y: 800 });
  // Catalog screening (UC-7): threshold, in-flight flag, last result.
  const [screenKm, setScreenKm] = useState('5');
  const [screening, setScreening] = useState(false);
  const [screenResult, setScreenResult] = useState<ScreeningResult | null>(null);
  const [screenMsg, setScreenMsg] = useState<string | null>(null);
  const { collapsed, toggle } = useCollapsed('environment');
  const panelRef = usePanelSize<HTMLElement>('environment', collapsed);

  function onDragStart(e: ReactPointerEvent) {
    if ((e.target as HTMLElement).closest('button')) return;
    e.preventDefault();
    const startX = e.clientX;
    const startY = e.clientY;
    const origin = { ...pos };
    let last = origin;
    const move = (ev: PointerEvent) => {
      const x = Math.min(window.innerWidth - 80, Math.max(0, origin.x + (ev.clientX - startX)));
      const y = Math.min(window.innerHeight - 60, Math.max(0, origin.y + (ev.clientY - startY)));
      last = { x, y };
      setPos(last);
    };
    const up = () => {
      commitPos(last); // persist the final spot so a refresh keeps it
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  if (!loaded) return null;
  const chief = loaded.body.chief;
  const deputies = loaded.body.deputies ?? [];
  if (!chief) return null;
  const crafts = [chief, ...deputies];
  const selectedHost = hostId ?? chief.noradId ?? null;
  const hostRole = crafts.find((c) => c.noradId === selectedHost);
  const hostSensors = hostRole?.sensors ?? [];
  const targets = crafts.filter((c) => c.noradId !== selectedHost);
  const currentThreshold = loaded.body.missDistanceThresholdM;

  function craftName(noradId: number | undefined): string {
    return crafts.find((c) => c.noradId === noradId)?.name ?? `NORAD ${noradId}`;
  }

  function formError(): string | null {
    const ld = num(limitDeg);
    if (!(ld > 0 && ld < 180)) return 'Limit angle must be between 0° and 180°.';
    if (kind === 'sun-keep-out' && !sensorId) {
      return hostSensors.length === 0
        ? 'Sun-keep-out needs a sensor on the host — add one in the Sensors panel first.'
        : 'Select the host sensor to protect.';
    }
    if (kind === 'approach-corridor') {
      if (targetId == null) return 'Select the corridor target.';
      if (!(num(rangeKm) > 0)) return 'Corridor range must be > 0 km.';
    }
    return null;
  }

  async function onAdd(e: FormEvent) {
    e.preventDefault();
    if (selectedHost == null || formError()) return;
    const req: ConstraintRequest = { hostNoradId: selectedHost, kind, limitDeg: num(limitDeg) };
    if (kind === 'sun-keep-out') req.sensorId = sensorId;
    else {
      req.targetNoradId = targetId ?? undefined;
      req.rangeM = num(rangeKm) * 1000;
    }
    setMsg(null);
    const err = await addConstraint(req);
    setMsg(err ?? `Added ${kind} on ${craftName(selectedHost)}`);
  }

  async function onSetThreshold() {
    setMsg(null);
    const v = num(thresholdKm); // empty / 0 / invalid → clear the threshold
    const err = await setMissDistance(v > 0 ? v * 1000 : null);
    setMsg(err ?? `Conjunction threshold ${v > 0 ? `${v} km` : 'cleared'}`);
  }

  async function onScreen() {
    const v = num(screenKm);
    if (!(v > 0)) {
      setScreenMsg('Enter a miss distance in km.');
      return;
    }
    setScreening(true);
    setScreenMsg(null);
    const res = await screenCatalog(v);
    setScreening(false);
    if (typeof res === 'string') {
      setScreenMsg(res);
      return;
    }
    setScreenResult(res);
    setScreenMsg(`${res.conjunctions?.length ?? 0} found · screened ${res.catalogSize ?? 0} sats`);
  }

  const formErr = formError();
  const conjCount = rel?.conjunctions?.length ?? 0;
  const violCount = (rel?.violations ?? []).filter((v) => v.type === 'violation-start').length;
  const eclipseCount = (rel?.eclipses ?? []).filter((e) => e.type === 'umbra-ingress').length;

  return (
    <aside ref={panelRef} className={`maneuver-panel env-panel${collapsed ? ' is-collapsed' : ''}`} style={{ left: pos.x, top: pos.y }}>
      <div className="mvr-drag" onPointerDown={onDragStart} title="Drag to move">
        <span className="mvr-drag-title"><span className="mvr-grip" aria-hidden>⠿</span> Environment · Events</span>
        <button className="panel-min" onClick={toggle} title={collapsed ? 'Expand' : 'Minimize'}>
          {collapsed ? '▸' : '▾'}
        </button>
      </div>
      {!collapsed && (
        <>
          <div className="mvr-deputy">
            <div className="mvr-note">
              Detected over the window — eclipses: {eclipseCount} · conjunctions: {conjCount} · violations: {violCount}.
              Eclipse bands, conjunction ticks (◆) and violation marks (|) appear on the timeline.
            </div>
          </div>

          <div className="mvr-deputy">
            <div className="mvr-deputy-head">
              <span className="mvr-deputy-name">Conjunction threshold</span>
            </div>
            <div className="env-row">
              <label className="env-field">
                <span>miss &lt; (km)</span>
                <input
                  type="number"
                  step="any"
                  min={0}
                  value={thresholdKm}
                  onChange={(e) => setThresholdKm(e.target.value)}
                />
              </label>
              <button type="button" className="env-btn" onClick={() => void onSetThreshold()}>Set</button>
            </div>
            <div className="mvr-note">
              {currentThreshold != null
                ? `current: ${(currentThreshold / 1000).toFixed(1)} km`
                : 'current: default (5 km)'} · set 0 to clear.
            </div>
          </div>

          {crafts.map((c, idx) => {
            const constraints = c.constraints ?? [];
            const color = idx === 0 ? CHIEF_COLOR : DEPUTY_COLORS[(idx - 1) % DEPUTY_COLORS.length];
            if (constraints.length === 0) return null;
            return (
              <div key={c.noradId ?? idx} className="mvr-deputy">
                <div className="mvr-deputy-head">
                  <span style={{ color }}>●</span>{' '}
                  <span className="mvr-deputy-name">{c.name ?? `NORAD ${c.noradId}`}</span>
                </div>
                <ul className="mvr-list">
                  {constraints.map((k) => (
                    <li key={k.id}>
                      <span className="mvr-epoch">{k.kind === 'sun-keep-out' ? 'sun-KO' : 'corridor'}</span>
                      <span className="mvr-dv">
                        {k.limitDeg}°
                        {k.kind === 'approach-corridor' ? ` · ${craftName(k.targetNoradId)} ≤ ${((k.rangeM ?? 0) / 1000).toFixed(0)}km` : ''}
                      </span>
                      <button className="mvr-remove" title="Remove constraint" onClick={() => k.id && void removeConstraint(k.id)}>
                        ×
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}

          <form className="mvr-add" onSubmit={onAdd}>
            <div className="mvr-add-title">Add constraint</div>
            <select value={selectedHost ?? ''} onChange={(e) => { setHostId(Number(e.target.value)); setSensorId(''); }} aria-label="Host">
              {crafts.map((c, idx) => (
                <option key={c.noradId ?? idx} value={c.noradId ?? ''}>{c.name ?? `NORAD ${c.noradId}`}</option>
              ))}
            </select>
            <select value={kind} onChange={(e) => setKind(e.target.value as Kind)} aria-label="Kind">
              <option value="sun-keep-out">Sun keep-out</option>
              <option value="approach-corridor">Approach corridor</option>
            </select>
            <div className="mvr-ric">
              {kind === 'sun-keep-out' ? (
                <label>
                  sensor
                  <select value={sensorId} onChange={(e) => setSensorId(e.target.value)}>
                    <option value="">—</option>
                    {hostSensors.map((s) => (
                      <option key={s.id} value={s.id}>{s.name}</option>
                    ))}
                  </select>
                </label>
              ) : (
                <label>
                  target
                  <select value={targetId ?? ''} onChange={(e) => setTargetId(Number(e.target.value))}>
                    <option value="">—</option>
                    {targets.map((t) => (
                      <option key={t.noradId} value={t.noradId ?? ''}>{t.name ?? `NORAD ${t.noradId}`}</option>
                    ))}
                  </select>
                </label>
              )}
              <label title={kind === 'sun-keep-out' ? 'Minimum Sun↔boresight angle' : 'Corridor half-angle about the host ram (+Y) axis'}>
                limit°
                <input type="number" step="any" min={0} max={180} value={limitDeg}
                  onChange={(e) => setLimitDeg(e.target.value)} />
              </label>
              {kind === 'approach-corridor' && (
                <label title="The corridor applies only within this range of the host">
                  range km
                  <input type="number" step="any" min={0} value={rangeKm}
                    onChange={(e) => setRangeKm(e.target.value)} />
                </label>
              )}
            </div>
            <div className="mvr-note">
              {kind === 'sun-keep-out'
                ? 'Flags when the Sun comes within limit° of the sensor boresight (UC-4).'
                : 'Flags when the target leaves a limit° cone about the host ram axis while within range.'}
            </div>
            {formErr && <div className="mvr-budget-warn">⚠ {formErr}</div>}
            <button type="submit" disabled={!!formErr}>Add constraint</button>
            {msg && <div className="mvr-msg">{msg}</div>}
          </form>

          <div className="mvr-deputy">
            <div className="mvr-deputy-head">
              <span className="mvr-deputy-name">Screen against catalog</span>
            </div>
            <div className="env-row">
              <label className="env-field">
                <span>miss &lt; (km)</span>
                <input type="number" step="any" min={0} value={screenKm}
                  onChange={(e) => setScreenKm(e.target.value)} />
              </label>
              <button type="button" className="env-btn" disabled={screening || !(num(screenKm) > 0)} onClick={() => void onScreen()}>
                {screening ? 'Screening…' : 'Screen'}
              </button>
              {screenResult && (screenResult.conjunctions?.length ?? 0) > 0 && (
                <button type="button" className="env-btn env-btn-ghost" onClick={() => exportScreeningCsv(screenResult)} title="Export CSV">CSV</button>
              )}
            </div>
            {screenMsg && <div className="mvr-msg">{screenMsg}</div>}
            {screenResult && (screenResult.conjunctions?.length ?? 0) > 0 && (
              <div className="screen-results">
                <table>
                  <thead>
                    <tr><th>craft</th><th>third party</th><th>miss</th><th>TCA (UTC)</th></tr>
                  </thead>
                  <tbody>
                    {(screenResult.conjunctions ?? []).map((c, i) => (
                      <tr
                        key={`${c.scenarioNoradId}-${c.catalogNoradId}-${i}`}
                        onClick={() => {
                          if (c.tcaEpoch) seek(new Date(c.tcaEpoch));
                          if (c.catalogNoradId) requestFocus(c.catalogNoradId);
                        }}
                        title="Scrub to the TCA + highlight the third party in the global view"
                      >
                        <td>{c.scenarioName ?? c.scenarioNoradId}</td>
                        <td>{c.catalogName ?? c.catalogNoradId}</td>
                        <td>{((c.missDistanceM ?? 0) / 1000).toFixed(2)} km</td>
                        <td>{(c.tcaEpoch ?? '').slice(0, 19).replace('T', ' ')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div className="mvr-note">
              Screens the scenario craft vs the live catalog (a snapshot — the catalog refreshes ~6h).
              Click a row to scrub to the TCA. Catalog deputies may be phase-approximate (R19).
            </div>
          </div>
        </>
      )}
    </aside>
  );
}
