import { useState, type FormEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { useStore, type SensorRequest } from '../store/useStore';
import { usePanelSize, usePanelPosition } from '../lib/usePanelChrome';

// Same palette as ProximityView / ManeuverPanel so a craft's color is consistent.
const CHIEF_COLOR = '#ffd166';
const DEPUTY_COLORS = [
  '#38bdf8', '#ff922b', '#a3e635', '#e879f9', '#2dd4bf', '#f472b6', '#818cf8', '#facc15',
];

// Sensor-type presets (usability — Maya authors in <2 min). Pick one and tweak;
// the backend validates whatever is submitted. UC-4's imager is the wide rect.
interface Preset extends Omit<SensorRequest, 'noradId'> {
  label: string;
}
const PRESETS: Preset[] = [
  { label: 'Narrow optical imager', kind: 'optical', name: 'Narrow imager', fovType: 'cone',
    halfAngleDeg: 5, hDeg: 0, vDeg: 0, minRangeM: 1000, maxRangeM: 500000,
    boresightX: 1, boresightY: 0, boresightZ: 0, clockDeg: 0 },
  { label: 'Wide imager (20°×15°)', kind: 'optical', name: 'Wide imager', fovType: 'rect',
    halfAngleDeg: 0, hDeg: 20, vDeg: 15, minRangeM: 100, maxRangeM: 50000,
    boresightX: 1, boresightY: 0, boresightZ: 0, clockDeg: 0 },
  { label: 'Rendezvous lidar', kind: 'lidar', name: 'Rdv lidar', fovType: 'cone',
    halfAngleDeg: 10, hDeg: 0, vDeg: 0, minRangeM: 1, maxRangeM: 5000,
    boresightX: 1, boresightY: 0, boresightZ: 0, clockDeg: 0 },
];

const BORESIGHTS: { label: string; v: [number, number, number] }[] = [
  { label: '+X', v: [1, 0, 0] }, { label: '+Y', v: [0, 1, 0] }, { label: '+Z', v: [0, 0, 1] },
  { label: '−X', v: [-1, 0, 0] }, { label: '−Y', v: [0, -1, 0] }, { label: '−Z', v: [0, 0, -1] },
];

function fovLabel(s: { fov?: { type?: string; halfAngleDeg?: number; hDeg?: number; vDeg?: number } }): string {
  const f = s.fov;
  if (!f) return '';
  return f.type === 'rect' ? `${f.hDeg ?? 0}°×${f.vDeg ?? 0}°` : `cone ${f.halfAngleDeg ?? 0}°`;
}

function rangeLabel(min?: number, max?: number): string {
  const fmt = (m?: number) => ((m ?? 0) >= 1000 ? `${((m ?? 0) / 1000).toFixed(0)}km` : `${(m ?? 0).toFixed(0)}m`);
  return `${fmt(min)}–${fmt(max)}`;
}

/** Parse a raw numeric-field string; NaN for empty/invalid (callers guard). */
function num(s: string): number {
  return parseFloat(s);
}

/**
 * The add-sensor form holds its editable numeric fields as RAW STRINGS so they can be
 * cleared and retyped freely — binding a number-input to a number snaps an emptied
 * field back to 0 (you could never delete the 0 to type a new value). Parsed to
 * numbers only on submit.
 */
interface SensorForm {
  kind: string;
  name: string;
  fovType: 'cone' | 'rect';
  halfAngleDeg: string;
  hDeg: string;
  vDeg: string;
  minRangeM: string;
  maxRangeM: string;
}

function presetToForm(p: Preset): SensorForm {
  return {
    kind: p.kind,
    name: p.name,
    fovType: p.fovType,
    halfAngleDeg: String(p.halfAngleDeg),
    hDeg: String(p.hDeg),
    vDeg: String(p.vDeg),
    minRangeM: String(p.minRangeM),
    maxRangeM: String(p.maxRangeM),
  };
}

/**
 * Sensor panel (Phase 7, US-SENSE-01). Per-craft (chief + deputies) sensor list with
 * an add form (type preset + FOV + range + boresight) and a remove control, plus a
 * per-craft attitude mode toggle (lvlh / fixed-inertial). Edits go through the audited
 * backend path and re-propagate (the store reloads), so the FOV volumes, modeled
 * orientation, and acquisition events in the proximity view update. Mirrors
 * ManeuverPanel's draggable/collapsible chrome.
 */
export default function SensorPanel() {
  const loaded = useStore((s) => s.loadedScenario);
  const addSensor = useStore((s) => s.addSensor);
  const removeSensor = useStore((s) => s.removeSensor);
  const setAttitude = useStore((s) => s.setAttitude);
  const setLinkBudget = useStore((s) => s.setLinkBudget);

  const [hostId, setHostId] = useState<number | null>(null);
  const [presetIdx, setPresetIdx] = useState(1); // wide imager default (UC-4)
  const [form, setForm] = useState<SensorForm>(() => presetToForm(PRESETS[1]));
  const [boresightIdx, setBoresightIdx] = useState(0);
  const [msg, setMsg] = useState<string | null>(null);
  // Link-budget sub-form (Phase 9D): pick a sensor + RF/optical params → SNR series.
  const [linkSensorId, setLinkSensorId] = useState<string | null>(null);
  const [link, setLink] = useState({
    kind: 'rf', eirpDbw: '20', gOverTdbK: '5', frequencyGhz: '2.2', bandwidthHz: '1e6', thresholdDb: '10',
  });
  const [linkMsg, setLinkMsg] = useState<string | null>(null);
  const { pos, setPos, commitPos } = usePanelPosition('sensors', { x: 332, y: 122 });
  const panelRef = usePanelSize<HTMLElement>('sensors', false);

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

  function applyPreset(idx: number) {
    setPresetIdx(idx);
    setForm(presetToForm(PRESETS[idx]));
    setBoresightIdx(0);
  }

  // Live field validation (mirrors the backend rules) → inline warning + disabled submit.
  // A forward-pointed FOV can't see more than 90° off the boresight: a cone's half-angle
  // (boresight→edge) is < 90°; a rect's full H/V widths are < 180° (each side < 90°).
  function formError(): string | null {
    if (form.fovType === 'cone') {
      const ha = num(form.halfAngleDeg);
      if (!(ha > 0 && ha < 90)) {
        return 'Cone half-angle must be between 0° and 90° (measured boresight → edge). 90° or more is a hemisphere, not a pointed cone.';
      }
    } else {
      const h = num(form.hDeg);
      const v = num(form.vDeg);
      if (!(h > 0 && h < 180) || !(v > 0 && v < 180)) {
        return 'Rect H° and V° are full widths and must each be between 0° and 180°.';
      }
    }
    const mn = num(form.minRangeM);
    const mx = num(form.maxRangeM);
    if (!(mn >= 0) || !(mx > mn)) {
      return 'Range must satisfy 0 ≤ min < max (metres).';
    }
    return null;
  }

  async function onAdd(e: FormEvent) {
    e.preventDefault();
    if (selectedHost == null) return;
    if (formError()) return; // guard the Enter key; the button is also disabled
    const b = BORESIGHTS[boresightIdx].v;
    setMsg(null);
    const err = await addSensor({
      noradId: selectedHost,
      kind: form.kind,
      name: form.name,
      fovType: form.fovType,
      halfAngleDeg: num(form.halfAngleDeg),
      hDeg: num(form.hDeg),
      vDeg: num(form.vDeg),
      minRangeM: num(form.minRangeM),
      maxRangeM: num(form.maxRangeM),
      boresightX: b[0],
      boresightY: b[1],
      boresightZ: b[2],
      clockDeg: 0,
    });
    setMsg(err ?? `Added ${form.name} to ${selectedHost}`);
  }

  async function onSetLink(sid: string | null) {
    if (!sid) return;
    setLinkMsg(null);
    const err = await setLinkBudget(sid, {
      kind: link.kind,
      eirpDbw: Number(link.eirpDbw) || 0,
      gOverTdbK: Number(link.gOverTdbK) || 0,
      frequencyGhz: Number(link.frequencyGhz) || 0,
      bandwidthHz: Number(link.bandwidthHz) || 0,
      thresholdDb: Number(link.thresholdDb) || 0,
    });
    setLinkMsg(err ?? 'Link budget set — SNR band on the timeline');
  }

  const formErr = formError();
  // All sensors across crafts (for the link-budget selector); a ✓ marks ones with a budget.
  const allSensors = crafts.flatMap((c) =>
    (c.sensors ?? []).filter((s) => s.id).map((s) => ({
      id: s.id as string,
      label: `${c.name ?? `NORAD ${c.noradId}`} — ${s.name}`,
      hasLink: !!s.linkBudget,
    })),
  );
  const selectedLinkSensor = linkSensorId ?? allSensors[0]?.id ?? null;

  return (
    <aside ref={panelRef} className="maneuver-panel" style={{ left: pos.x, top: pos.y }}>
      <div className="mvr-drag" onPointerDown={onDragStart} title="Drag to move">
        <span className="mvr-drag-title"><span className="mvr-grip" aria-hidden>⠿</span> Sensors · FOV</span>
        <button className="panel-min" onClick={() => useStore.getState().closePanel('sensors')} title="Close" aria-label="Close">
          ✕
        </button>
      </div>
        <>
          {crafts.map((c, idx) => {
            const sensors = c.sensors ?? [];
            const color = idx === 0 ? CHIEF_COLOR : DEPUTY_COLORS[(idx - 1) % DEPUTY_COLORS.length];
            const mode = c.attitude?.mode ?? 'lvlh';
            return (
              <div key={c.noradId ?? idx} className="mvr-deputy">
                <div className="mvr-deputy-head">
                  <span style={{ color }}>●</span>{' '}
                  <span className="mvr-deputy-name">{c.name ?? `NORAD ${c.noradId}`}</span>
                  <select
                    className="sensor-att"
                    value={mode}
                    title="Attitude profile"
                    onChange={(e) =>
                      c.noradId != null &&
                      void setAttitude(
                        c.noradId,
                        e.target.value as 'lvlh' | 'fixed',
                        e.target.value === 'fixed' ? [0, 0, 0, 1] : undefined,
                      )
                    }
                  >
                    <option value="lvlh">att: LVLH</option>
                    <option value="fixed">att: fixed</option>
                  </select>
                </div>
                {sensors.length === 0 ? (
                  <div className="mvr-empty">no sensors</div>
                ) : (
                  <ul className="mvr-list">
                    {sensors.map((s) => (
                      <li key={s.id}>
                        <span className="mvr-epoch">{s.kind}</span>
                        <span className="mvr-dv">
                          {s.name} · {fovLabel(s)} · {rangeLabel(s.minRangeM, s.maxRangeM)}
                        </span>
                        <button
                          className="mvr-remove"
                          title="Remove sensor"
                          onClick={() => s.id && void removeSensor(s.id)}
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
            <div className="mvr-add-title">Add sensor</div>
            <select value={selectedHost ?? ''} onChange={(e) => setHostId(Number(e.target.value))} aria-label="Host">
              {crafts.map((c, idx) => (
                <option key={c.noradId ?? idx} value={c.noradId ?? ''}>
                  {c.name ?? `NORAD ${c.noradId}`}
                </option>
              ))}
            </select>
            <select value={presetIdx} onChange={(e) => applyPreset(Number(e.target.value))} aria-label="Preset">
              {PRESETS.map((p, i) => (
                <option key={p.label} value={i}>{p.label}</option>
              ))}
            </select>
            <div className="mvr-ric">
              <label>
                type
                <select title="FOV shape — circular cone or rectangle"
                  value={form.fovType}
                  onChange={(e) => setForm({ ...form, fovType: e.target.value as 'cone' | 'rect' })}
                >
                  <option value="cone">cone</option>
                  <option value="rect">rect</option>
                </select>
              </label>
              {form.fovType === 'cone' ? (
                <label title="Measured from the boresight (centre) to the cone edge. 0–90°.">
                  half-angle°
                  <input title="Cone half-angle, degrees"
                    type="number"
                    step="any"
                    min={0}
                    max={90}
                    value={form.halfAngleDeg}
                    onChange={(e) => setForm({ ...form, halfAngleDeg: e.target.value })}
                  />
                </label>
              ) : (
                <>
                  <label title="Full horizontal width of the field of view. 0–180°.">
                    H° (full)
                    <input title="Rectangular FOV full width, degrees"
                      type="number"
                      step="any"
                      min={0}
                      max={180}
                      value={form.hDeg}
                      onChange={(e) => setForm({ ...form, hDeg: e.target.value })}
                    />
                  </label>
                  <label title="Full vertical width of the field of view. 0–180°.">
                    V° (full)
                    <input title="Rectangular FOV full height, degrees"
                      type="number"
                      step="any"
                      min={0}
                      max={180}
                      value={form.vDeg}
                      onChange={(e) => setForm({ ...form, vDeg: e.target.value })}
                    />
                  </label>
                </>
              )}
            </div>
            <div className="mvr-note">
              {form.fovType === 'cone'
                ? 'Cone: half-angle from the boresight to the edge (0–90°).'
                : 'Rect: full H×V angular widths (0–180° each).'}{' '}
              max = the sensor's straight-line detection range.
            </div>
            <div className="mvr-ric">
              <label>
                min m
                <input title="Minimum working range, m"
                  type="number"
                  step="any"
                  value={form.minRangeM}
                  onChange={(e) => setForm({ ...form, minRangeM: e.target.value })}
                />
              </label>
              <label>
                max m
                <input title="Maximum working range, m"
                  type="number"
                  step="any"
                  value={form.maxRangeM}
                  onChange={(e) => setForm({ ...form, maxRangeM: e.target.value })}
                />
              </label>
              <label>
                boresight
                <select title="Body axis the sensor boresight points along" value={boresightIdx} onChange={(e) => setBoresightIdx(Number(e.target.value))}>
                  {BORESIGHTS.map((b, i) => (
                    <option key={b.label} value={i}>{b.label}</option>
                  ))}
                </select>
              </label>
            </div>
            {formErr && <div className="mvr-budget-warn">⚠ {formErr}</div>}
            <button title="Add the sensor (creates a new scenario version)" type="submit" disabled={!!formErr}>Add sensor</button>
            <div className="mvr-note">FOV volumes + acquisition events appear in the proximity view.</div>
            {msg && <div className="mvr-msg">{msg}</div>}
          </form>

          {allSensors.length > 0 && (
            <div className="mvr-add">
              <div className="mvr-add-title">Link budget (SNR)</div>
              <select
                value={selectedLinkSensor ?? ''}
                onChange={(e) => setLinkSensorId(e.target.value)}
                aria-label="Sensor"
              >
                {allSensors.map((s) => (
                  <option key={s.id} value={s.id}>{s.label}{s.hasLink ? ' ✓' : ''}</option>
                ))}
              </select>
              <div className="mvr-ric">
                <label>
                  kind
                  <select title="Link type — RF or optical (both use the Friis form)" value={link.kind} onChange={(e) => setLink({ ...link, kind: e.target.value })}>
                    <option value="rf">rf</option>
                    <option value="optical">optical</option>
                  </select>
                </label>
                <label>EIRP dBW<input title="Transmitter EIRP, dBW" type="number" step="any" value={link.eirpDbw} onChange={(e) => setLink({ ...link, eirpDbw: e.target.value })} /></label>
                <label>G/T dB/K<input title="Receiver G/T, dB/K" type="number" step="any" value={link.gOverTdbK} onChange={(e) => setLink({ ...link, gOverTdbK: e.target.value })} /></label>
              </div>
              <div className="mvr-ric">
                <label>freq GHz<input title="Carrier frequency, GHz" type="number" step="any" value={link.frequencyGhz} onChange={(e) => setLink({ ...link, frequencyGhz: e.target.value })} /></label>
                <label>BW Hz<input title="Receiver bandwidth, Hz" type="number" step="any" value={link.bandwidthHz} onChange={(e) => setLink({ ...link, bandwidthHz: e.target.value })} /></label>
                <label>thr dB<input title="Detection threshold, dB — the timeline band turns red below it" type="number" step="any" value={link.thresholdDb} onChange={(e) => setLink({ ...link, thresholdDb: e.target.value })} /></label>
              </div>
              <button title="Save the link budget (creates a new scenario version)" type="button" onClick={() => void onSetLink(selectedLinkSensor)} disabled={!selectedLinkSensor}>
                Set link budget
              </button>
              <div className="mvr-note">
                SNR(r) = EIRP + G/T − free-space loss + 228.6 − 10·log₁₀B. Drawn as a timeline
                band (red below the threshold).
              </div>
              {linkMsg && <div className="mvr-msg">{linkMsg}</div>}
            </div>
          )}
        </>
    </aside>
  );
}
