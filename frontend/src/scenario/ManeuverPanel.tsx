import { useState, useSyncExternalStore, type FormEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { useStore, type RendezvousSearchResult, type DvCell } from '../store/useStore';
import { getRelativeData, getRelativeVersion, subscribeRelative } from '../stream/relativeBuffer';
import { useCollapsed, usePanelSize, usePanelPosition } from '../lib/usePanelChrome';

/** Compact ΔV formatter — m/s, switching to km/s past 1000 (a garbage transfer tell). */
function fmtDv(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(2)} km/s` : `${n.toFixed(1)} m/s`;
}

/** Pull a readable message out of a thrown value (so failures are never silent). */
function msgOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

const CW_MAX_SEPARATION_M = 10_000; // CW linearization validity (~10 km)
const CW_MAX_ECCENTRICITY = 0.01; // CW assumes a near-circular chief

// Sanity guards (see the maneuver UI). A Hohmann target below this re-enters; a
// cumulative ΔV above this is far beyond any real Earth-orbit maneuver (orbital
// speed ≈ 7,500 m/s) and will re-enter or escape.
const MIN_ALT_KM = 150;
const DV_WARN_M_S = 5000;

// Same deputy palette as ProximityView / RelativeReadout (color identity).
const DEPUTY_COLORS = [
  '#38bdf8', '#ff922b', '#a3e635', '#e879f9', '#2dd4bf', '#f472b6', '#818cf8', '#facc15',
];

function magnitude(dv?: { r?: number; i?: number; c?: number }): number {
  if (!dv) return 0;
  return Math.hypot(dv.r ?? 0, dv.i ?? 0, dv.c ?? 0);
}

function signed(v?: number): string {
  const n = v ?? 0;
  return `${n >= 0 ? '+' : '−'}${Math.abs(n).toFixed(1)}`;
}

/** datetime-local value (no tz) ⇄ UTC ISO, matching ScenarioPanel's convention. */
function toInput(iso?: string): string {
  return iso ? iso.slice(0, 16) : '';
}

/**
 * Maneuver panel (Phase 5B, US-MAN-01 / US-MAN-05). Per-deputy impulsive ΔV list
 * with a cumulative ΔV budget, plus an "add Δv" form (RIC components, epoch bounded
 * to the scenario window). Edits go through the audited backend mutation path and
 * re-propagate the scenario (the store reloads it). RIC-only in 5B; a maneuvered
 * deputy is propagated numerically (body-frame ΔV + attitude arrive in Phase 7).
 */
export default function ManeuverPanel() {
  const loaded = useStore((s) => s.loadedScenario);
  const addManeuver = useStore((s) => s.addManeuver);
  const removeManeuver = useStore((s) => s.removeManeuver);
  const applyHohmann = useStore((s) => s.applyHohmann);
  const applyRendezvous = useStore((s) => s.applyRendezvous);
  const searchRendezvous = useStore((s) => s.searchRendezvous);
  const applyPhasing = useStore((s) => s.applyPhasing);
  const applyNmc = useStore((s) => s.applyNmc);
  const applyHold = useStore((s) => s.applyHold);
  // React to relative-buffer changes (carries the CW validity hint).
  useSyncExternalStore(subscribeRelative, getRelativeVersion);

  const [deputyId, setDeputyId] = useState<number | null>(null);
  const [epoch, setEpoch] = useState('');
  const [r, setR] = useState('0');
  const [i, setI] = useState('0');
  const [c, setC] = useState('0');
  const [finiteBurn, setFiniteBurn] = useState(false);
  const [thrustN, setThrustN] = useState('500');
  const [ispSec, setIspSec] = useState('300');
  const [targetAlt, setTargetAlt] = useState('');
  const [arrival, setArrival] = useState('');
  const [phasingRevs, setPhasingRevs] = useState('');
  const [holdAxis, setHoldAxis] = useState<'vbar' | 'rbar'>('vbar');
  const [holdDist, setHoldDist] = useState('');
  const [holdArrival, setHoldArrival] = useState('');
  const [searchResult, setSearchResult] = useState<RendezvousSearchResult | null>(null);
  const [selectedCell, setSelectedCell] = useState<DvCell | null>(null);
  const [searching, setSearching] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  // Draggable position (defaults beside the scenario panel so it doesn't cover it),
  // persisted + viewport-clamped so a refresh keeps it on-screen.
  const { pos, setPos, commitPos } = usePanelPosition('maneuvers', { x: 248, y: 320 });
  const { collapsed, toggle } = useCollapsed('maneuvers');
  const panelRef = usePanelSize<HTMLElement>('maneuvers', collapsed);

  function onDragStart(e: ReactPointerEvent) {
    if ((e.target as HTMLElement).closest('button')) return; // let header buttons click, not drag
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
  const deputies = loaded.body.deputies ?? [];
  if (deputies.length === 0) return null;

  const minInput = toInput(loaded.body.timeRange?.start);
  const maxInput = toInput(loaded.body.timeRange?.end);
  const selected = deputyId ?? deputies[0]?.noradId ?? null;

  // CW validity warning (US-REL-03): the relative stream carries the fidelity +
  // max separation + chief eccentricity hints.
  const rel = getRelativeData();
  const cwWarning =
    rel && rel.fidelity === 'cw' &&
    (rel.maxSeparationM > CW_MAX_SEPARATION_M || rel.chiefEccentricity > CW_MAX_ECCENTRICITY)
      ? `CW is linearized — ${
          rel.maxSeparationM > CW_MAX_SEPARATION_M
            ? `separation ${(rel.maxSeparationM / 1000).toFixed(1)} km exceeds ~10 km`
            : `chief eccentricity ${rel.chiefEccentricity.toFixed(3)} is not near-circular`
        }; results are approximate.`
      : null;

  async function onAdd(e: FormEvent) {
    e.preventDefault();
    if (selected == null || !epoch) return;
    const d = new Date(`${epoch}:00Z`);
    if (Number.isNaN(d.getTime())) return;
    await addManeuver(
      selected,
      d.toISOString(),
      { r: Number(r) || 0, i: Number(i) || 0, c: Number(c) || 0 },
      finiteBurn ? { thrustN: Number(thrustN) || 0, ispSec: Number(ispSec) || 0 } : undefined,
    );
    setR('0');
    setI('0');
    setC('0');
  }

  async function onHohmann() {
    if (selected == null) { setMsg('Select a deputy first.'); return; }
    if (!targetAlt) { setMsg('Enter a target altitude (km).'); return; }
    const alt = Number(targetAlt) || 0;
    // Instant feedback (the backend enforces this too): the field is an absolute
    // altitude above the surface, not a change — a low value just deorbits.
    if (alt < MIN_ALT_KM) {
      setMsg(`Target is an absolute altitude (km above the surface), not a change — must be ≥ ${MIN_ALT_KM} km, else it re-enters.`);
      return;
    }
    setMsg('Computing Hohmann…');
    try {
      const err = await applyHohmann(selected, alt);
      setMsg(err ?? `Hohmann inserted → ${targetAlt} km`);
    } catch (e) {
      setMsg(`Hohmann failed: ${msgOf(e)}`);
    }
  }

  async function onRendezvous() {
    if (selected == null) { setMsg('Select a deputy first.'); return; }
    if (!arrival) { setMsg('Enter an arrival time (or click Find and pick a row).'); return; }
    const d = new Date(`${arrival}:00Z`);
    if (Number.isNaN(d.getTime())) { setMsg('Arrival time is invalid.'); return; }
    setMsg('Correcting against the real propagators…');
    try {
      // corrected=true (default) closes R16; nRev from the chosen search cell if any.
      const err = await applyRendezvous(selected, d.toISOString(), true, selectedCell?.nRev);
      setMsg(err ?? 'Corrected rendezvous inserted — flies to the chief on the real model');
      if (!err) { setSearchResult(null); setSelectedCell(null); }
    } catch (e) {
      setMsg(`Rendezvous failed: ${msgOf(e)}`);
    }
  }

  async function onSearch() {
    if (selected == null) { setMsg('Select a deputy first.'); return; }
    setSearching(true);
    setMsg('Searching transfers…');
    try {
      const res = await searchRendezvous(selected);
      if (typeof res === 'string') { setMsg(res); return; }
      setSearchResult(res);
      setMsg(!res.cells || res.cells.length === 0
        ? 'No feasible transfer in the window.'
        : `Found ${res.cells.length} transfers — pick a row, then Insert.`);
    } catch (e) {
      setMsg(`Search failed: ${msgOf(e)}`);
    } finally {
      setSearching(false);
    }
  }

  function onPickCell(cell: DvCell) {
    setSelectedCell(cell);
    if (cell.arrivalEpoch) setArrival(toInput(cell.arrivalEpoch));
  }

  async function onPhasing() {
    if (selected == null) { setMsg('Select a deputy first.'); return; }
    if (!phasingRevs) { setMsg('Enter a number of revolutions (e.g. 3).'); return; }
    const n = Math.round(Number(phasingRevs));
    if (!(n >= 1)) { setMsg('Phasing needs at least 1 revolution.'); return; }
    setMsg('Computing phasing…');
    try {
      const err = await applyPhasing(selected, n);
      setMsg(err ?? `Phasing inserted (${n} rev${n === 1 ? '' : 's'})`);
    } catch (e) {
      setMsg(`Phasing failed: ${msgOf(e)}`);
    }
  }

  async function onNmc() {
    if (selected == null) { setMsg('Select a deputy first.'); return; }
    setMsg('Computing NMC…');
    try {
      const err = await applyNmc(selected);
      setMsg(err ?? 'NMC inserted — bounded relative orbit');
    } catch (e) {
      setMsg(`NMC failed: ${msgOf(e)}`);
    }
  }

  async function onHold() {
    if (selected == null) { setMsg('Select a deputy first.'); return; }
    if (!holdDist) { setMsg('Enter a hold distance in metres (e.g. 500 ahead/above, −500 behind/below).'); return; }
    if (!holdArrival) { setMsg('Enter a hold arrival time (within the scenario window).'); return; }
    const d = new Date(`${holdArrival}:00Z`);
    if (Number.isNaN(d.getTime())) { setMsg('Hold arrival time is invalid.'); return; }
    setMsg('Computing hold…');
    try {
      const err = await applyHold(selected, holdAxis, Number(holdDist) || 0, d.toISOString());
      setMsg(err ?? `${holdAxis.toUpperCase()} hold inserted at ${holdDist} m`);
    } catch (e) {
      setMsg(`Hold failed: ${msgOf(e)}`);
    }
  }

  return (
    <aside ref={panelRef} className={`maneuver-panel${collapsed ? ' is-collapsed' : ''}`} style={{ left: pos.x, top: pos.y }}>
      <div className="mvr-drag" onPointerDown={onDragStart} title="Drag to move">
        <span className="mvr-drag-title"><span className="mvr-grip" aria-hidden>⠿</span> Maneuvers · ΔV</span>
        <button
          className="panel-min"
          onClick={toggle}
          title={collapsed ? 'Expand' : 'Minimize'}
          aria-label={collapsed ? 'Expand' : 'Minimize'}
        >
          {collapsed ? '▸' : '▾'}
        </button>
      </div>
      {!collapsed && (
      <>
      {cwWarning && <div className="mvr-cw-warn">⚠ {cwWarning}</div>}
      {deputies.map((d, idx) => {
        const maneuvers = d.maneuvers ?? [];
        const budget = maneuvers.reduce((sum, m) => sum + magnitude(m.deltaV), 0);
        const overBudget = budget >= DV_WARN_M_S;
        return (
          <div key={d.noradId ?? idx} className="mvr-deputy">
            <div className="mvr-deputy-head">
              <span style={{ color: DEPUTY_COLORS[idx % DEPUTY_COLORS.length] }}>●</span>{' '}
              <span className="mvr-deputy-name">{d.name ?? `NORAD ${d.noradId}`}</span>
              <span className={overBudget ? 'mvr-budget warn' : 'mvr-budget'}>Σ {budget.toFixed(2)} m/s</span>
            </div>
            {overBudget && (
              <div className="mvr-budget-warn">
                ⚠ Far beyond a realistic burn (orbital speed ≈ 7,500 m/s) — this likely
                re-enters or escapes. Real proximity burns are well under a few hundred m/s.
              </div>
            )}
            {maneuvers.length === 0 ? (
              <div className="mvr-empty">no maneuvers</div>
            ) : (
              <ul className="mvr-list">
                {maneuvers.map((m) => (
                  <li key={m.id}>
                    <span className="mvr-epoch">{m.epoch?.slice(11, 16) ?? '—'}</span>
                    <span className="mvr-dv">
                      R{signed(m.deltaV?.r)} I{signed(m.deltaV?.i)} C{signed(m.deltaV?.c)} · |
                      {magnitude(m.deltaV).toFixed(2)}|
                      {m.thrustN != null && m.ispSec != null && (
                        <span className="mvr-finite-tag" title={`finite burn — ${m.thrustN} N, Isp ${m.ispSec} s`}>
                          {' '}finite
                        </span>
                      )}
                    </span>
                    <button
                      className="mvr-remove"
                      title="Remove maneuver"
                      onClick={() => m.id && void removeManeuver(m.id)}
                    >
                      ×
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        );
      })}

      <form className="mvr-add" onSubmit={onAdd}>
        <div className="mvr-add-title">Add Δv (RIC, m/s)</div>
        <select
          value={selected ?? ''}
          onChange={(e) => setDeputyId(Number(e.target.value))}
          aria-label="Deputy"
        >
          {deputies.map((d, idx) => (
            <option key={d.noradId ?? idx} value={d.noradId ?? ''}>
              {d.name ?? `NORAD ${d.noradId}`}
            </option>
          ))}
        </select>
        <label className="mvr-epoch-field">
          <span>epoch (UTC)</span>
          <input
            type="datetime-local"
            value={epoch}
            min={minInput}
            max={maxInput}
            onChange={(e) => setEpoch(e.target.value)}
            required
          />
        </label>
        <div className="mvr-ric">
          <label>
            R<input type="number" step="any" value={r} onChange={(e) => setR(e.target.value)} />
          </label>
          <label>
            I<input type="number" step="any" value={i} onChange={(e) => setI(e.target.value)} />
          </label>
          <label>
            C<input type="number" step="any" value={c} onChange={(e) => setC(e.target.value)} />
          </label>
        </div>
        <label className="mvr-finite-toggle">
          <input
            type="checkbox"
            checked={finiteBurn}
            onChange={(e) => setFiniteBurn(e.target.checked)}
          />
          <span>finite burn (thrust + Isp)</span>
        </label>
        {finiteBurn && (
          <div className="mvr-finite">
            <label>
              thrust (N)
              <input
                type="number"
                step="any"
                min="0"
                value={thrustN}
                onChange={(e) => setThrustN(e.target.value)}
              />
            </label>
            <label>
              Isp (s)
              <input
                type="number"
                step="any"
                min="0"
                value={ispSec}
                onChange={(e) => setIspSec(e.target.value)}
              />
            </label>
          </div>
        )}
        <button type="submit" disabled={!epoch}>
          Add Δv
        </button>
        <div className="mvr-note">
          maneuvered deputies propagate numerically
          {finiteBurn ? ' · finite burn integrated as constant thrust (duration from the rocket equation)' : ''}
        </div>
      </form>

      <div className="mvr-templates">
        <div className="mvr-add-title">Templates</div>
        <div className="mvr-template-row">
          <label>
            Hohmann → target alt (km)
            <input
              type="number"
              step="any"
              min={MIN_ALT_KM}
              placeholder="absolute, e.g. 550"
              value={targetAlt}
              onChange={(e) => setTargetAlt(e.target.value)}
            />
          </label>
          <button type="button" onClick={() => void onHohmann()}>
            Insert
          </button>
        </div>
        <div className="mvr-template-row">
          <label>
            Rendezvous → arrival
            <input
              type="datetime-local"
              value={arrival}
              min={minInput}
              max={maxInput}
              onChange={(e) => setArrival(e.target.value)}
            />
          </label>
          <button type="button" onClick={() => void onSearch()} disabled={searching} title="Sweep arrival × revolution count for the cheapest transfer">
            {searching ? '…' : 'Find'}
          </button>
          <button type="button" onClick={() => void onRendezvous()}>
            Insert
          </button>
        </div>

        {searchResult && searchResult.cells && searchResult.cells.length > 0 && (
          <div className="mvr-search">
            <div className="mvr-add-title">
              ΔV map — cheapest first {selectedCell ? '(row selected)' : '(click a row)'}
            </div>
            <table className="mvr-search-table" style={{ width: '100%', fontSize: 11, borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ textAlign: 'left', opacity: 0.7 }}>
                  <th>arrival</th><th>rev</th><th style={{ textAlign: 'right' }}>total Δv</th>
                </tr>
              </thead>
              <tbody>
                {searchResult.cells.slice(0, 10).map((cell, i) => {
                  const isSel = selectedCell != null
                    && cell.arrivalEpoch === selectedCell.arrivalEpoch && cell.nRev === selectedCell.nRev;
                  return (
                    <tr
                      key={`${cell.arrivalEpoch}-${cell.nRev ?? i}`}
                      onClick={() => onPickCell(cell)}
                      style={{ cursor: 'pointer', background: isSel ? 'rgba(56,189,248,0.18)' : undefined }}
                    >
                      <td>{cell.arrivalEpoch?.slice(11, 16) ?? '—'}</td>
                      <td>{cell.nRev ?? 0}</td>
                      <td style={{ textAlign: 'right', color: (cell.totalDvMs ?? 0) >= DV_WARN_M_S ? '#f87171' : undefined }}>
                        {fmtDv(cell.totalDvMs ?? 0)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div className="mvr-note">
              Two-body costs (cheap selector). Pick one, then Insert runs the differential
              corrector against the real propagators so the deputy actually arrives.
            </div>
          </div>
        )}

        <div className="mvr-template-row">
          <label>
            Phasing → revolutions
            <input
              type="number"
              step="1"
              min={1}
              placeholder="e.g. 3"
              value={phasingRevs}
              onChange={(e) => setPhasingRevs(e.target.value)}
            />
          </label>
          <button type="button" onClick={() => void onPhasing()}>
            Insert
          </button>
        </div>

        <div className="mvr-add-title" style={{ marginTop: 6 }}>Close-range (CW)</div>
        <div className="mvr-template-row">
          <label>
            NMC → bounded relative orbit
            <span className="mvr-note" style={{ marginTop: 0 }}>one in-track burn, no parameters</span>
          </label>
          <button type="button" onClick={() => void onNmc()} title="One in-track burn onto a bounded relative orbit">
            Insert
          </button>
        </div>
        <div className="mvr-template-row">
          <label>
            Hold → axis
            <select value={holdAxis} onChange={(e) => setHoldAxis(e.target.value as 'vbar' | 'rbar')} aria-label="Hold axis">
              <option value="vbar">V-bar (in-track)</option>
              <option value="rbar">R-bar (radial)</option>
            </select>
          </label>
          <label>
            distance (m)
            <input type="number" step="any" placeholder="±500" value={holdDist} onChange={(e) => setHoldDist(e.target.value)} />
          </label>
        </div>
        <div className="mvr-template-row">
          <label>
            Hold → arrive (park there)
            <input
              type="datetime-local"
              value={holdArrival}
              min={minInput}
              max={maxInput}
              onChange={(e) => setHoldArrival(e.target.value)}
              aria-label="Hold arrival"
            />
          </label>
          <button type="button" onClick={() => void onHold()}>
            Insert
          </button>
        </div>

        <div className="mvr-note">
          Altitude is absolute (height above the surface, not a change). Rendezvous departs
          at the scenario start; the corrected transfer is closed-loop against the real
          model. Phasing walks the along-track gap down over N revolutions (a two-body sketch).
          NMC / hold are CW close-range templates (valid within ~10 km of the chief); signed
          hold distance places the point ahead/behind (V-bar) or above/below (R-bar).
        </div>
        {msg && <div className="mvr-msg">{msg}</div>}
      </div>
      </>
      )}
    </aside>
  );
}
