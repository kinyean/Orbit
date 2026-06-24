import { useState, useSyncExternalStore, type FormEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { useStore } from '../store/useStore';
import { getRelativeData, getRelativeVersion, subscribeRelative } from '../stream/relativeBuffer';
import { useCollapsed, usePanelSize, usePanelPosition } from '../lib/usePanelChrome';

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
  // React to relative-buffer changes (carries the CW validity hint).
  useSyncExternalStore(subscribeRelative, getRelativeVersion);

  const [deputyId, setDeputyId] = useState<number | null>(null);
  const [epoch, setEpoch] = useState('');
  const [r, setR] = useState('0');
  const [i, setI] = useState('0');
  const [c, setC] = useState('0');
  const [targetAlt, setTargetAlt] = useState('');
  const [arrival, setArrival] = useState('');
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
    await addManeuver(selected, d.toISOString(), {
      r: Number(r) || 0,
      i: Number(i) || 0,
      c: Number(c) || 0,
    });
    setR('0');
    setI('0');
    setC('0');
  }

  async function onHohmann() {
    if (selected == null || !targetAlt) return;
    const alt = Number(targetAlt) || 0;
    // Instant feedback (the backend enforces this too): the field is an absolute
    // altitude above the surface, not a change — a low value just deorbits.
    if (alt < MIN_ALT_KM) {
      setMsg(`Target is an absolute altitude (km above the surface), not a change — must be ≥ ${MIN_ALT_KM} km, else it re-enters.`);
      return;
    }
    setMsg(null);
    const err = await applyHohmann(selected, alt);
    setMsg(err ?? `Hohmann inserted → ${targetAlt} km`);
  }

  async function onRendezvous() {
    if (selected == null || !arrival) return;
    const d = new Date(`${arrival}:00Z`);
    if (Number.isNaN(d.getTime())) return;
    setMsg(null);
    const err = await applyRendezvous(selected, d.toISOString());
    setMsg(err ?? 'Lambert rendezvous inserted');
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
        <button type="submit" disabled={!epoch}>
          Add Δv
        </button>
        <div className="mvr-note">maneuvered deputies propagate numerically</div>
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
          <button type="button" onClick={() => void onHohmann()} disabled={!targetAlt}>
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
          <button type="button" onClick={() => void onRendezvous()} disabled={!arrival}>
            Insert
          </button>
        </div>
        <div className="mvr-note">
          Altitude is absolute (height above the surface, not a change). Rendezvous
          departs at the scenario start and arrives at your time — keep it within the
          window, between orbits that are actually near each other.
        </div>
        {msg && <div className="mvr-msg">{msg}</div>}
      </div>
      </>
      )}
    </aside>
  );
}
