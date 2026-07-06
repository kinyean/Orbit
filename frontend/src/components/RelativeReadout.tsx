import { useEffect, useRef, useSyncExternalStore } from 'react';
import { useStore } from '../store/useStore';
import {
  getRelativeData,
  getRelativeVersion,
  subscribeRelative,
  simTimeToT,
  deputyStateAt,
} from '../stream/relativeBuffer';
import { useCollapsed, usePanelTab } from '../lib/usePanelChrome';
import { fmtDistance } from '../lib/format';
import DistanceChart, { type DistanceChartHandle } from './DistanceChart';

// Must match ProximityView's DEPUTY_COLORS order (and the globe's deputy palette)
// so a deputy is the same color in every view.
const DEPUTY_COLORS = [
  '#38bdf8', '#ff922b', '#a3e635', '#e879f9', '#2dd4bf', '#f472b6', '#818cf8', '#facc15',
];
const MAX_ROWS = 10; // SRS §5.1.1 — ≤10 spacecraft
const TABS = ['table', 'graph'] as const;

/** Signed R/I/C component (km past 1 km, else m). */
function fmtComponent(v: number): string {
  const s = v >= 0 ? '+' : '−';
  const a = Math.abs(v);
  return a >= 1000 ? `${s}${(a / 1000).toFixed(2)} km` : `${s}${a.toFixed(0)} m`;
}

/** Signed range-rate, m/s. Negative = closing. */
function fmtRate(v: number): string {
  return `${v >= 0 ? '+' : '−'}${Math.abs(v).toFixed(2)}`;
}

function fmtTca(epochMs: number | null, distanceM: number | null): string {
  if (epochMs === null) return '—';
  const hhmmss = new Date(epochMs).toISOString().slice(11, 19);
  return distanceM !== null ? `${hhmmss} · ${fmtDistance(distanceM)}` : hhmmss;
}

interface RowRefs {
  range: HTMLTableCellElement | null;
  rate: HTMLTableCellElement | null;
  r: HTMLTableCellElement | null;
  i: HTMLTableCellElement | null;
  c: HTMLTableCellElement | null;
}

/**
 * Live relative-state readout (Phase 5A, US-REL-01 / US-REL-02). One row per
 * deputy: chief-relative range, range-rate, R/I/C components, and the
 * backend-computed closest approach (TCA). The static row skeleton renders via
 * React (rebuilt only when the deputy set changes); the live numbers are written
 * to cell refs from a throttled rAF loop, so the high-frequency ephemeris never
 * goes through Zustand or React reconciliation (Decision 5).
 */
export default function RelativeReadout() {
  const version = useSyncExternalStore(subscribeRelative, getRelativeVersion);
  const data = getRelativeData();
  const deputies = (data?.deputies ?? []).slice(0, MAX_ROWS);
  const rowRefs = useRef<Map<number, RowRefs>>(new Map());
  const chartRef = useRef<DistanceChartHandle>(null);
  const { collapsed, toggle } = useCollapsed('relreadout');
  const { tab, setTab } = usePanelTab('relreadout', TABS);

  const cellRef = (noradId: number, key: keyof RowRefs) => (el: HTMLTableCellElement | null) => {
    let row = rowRefs.current.get(noradId);
    if (!row) {
      row = { range: null, rate: null, r: null, i: null, c: null };
      rowRefs.current.set(noradId, row);
    }
    row[key] = el;
  };

  useEffect(() => {
    let raf = 0;
    let last = 0;
    const out = new Float64Array(6);
    const loop = () => {
      raf = requestAnimationFrame(loop);
      const now = performance.now();
      if (now - last < 200) return;
      last = now;
      if (collapsed) return;
      const d = getRelativeData();
      if (!d) return;
      const t = simTimeToT(d.epochMs, useStore.getState().currentTime);
      // Graph tab: just slide the cursor (curves are static). Decision 5 — the
      // high-frequency update touches DOM/SVG via refs, never React/Zustand.
      if (tab === 'graph') {
        chartRef.current?.setCursorT(t);
        return;
      }
      for (const dep of d.deputies) {
        const refs = rowRefs.current.get(dep.noradId);
        if (!refs) continue;
        deputyStateAt(dep.samples, dep.stride, dep.hasVelocity, t, out);
        const dist = Math.hypot(out[0], out[1], out[2]);
        const rate = dist > 0 ? (out[0] * out[3] + out[1] * out[4] + out[2] * out[5]) / dist : 0;
        if (refs.range) refs.range.textContent = fmtDistance(dist);
        if (refs.rate) refs.rate.textContent = fmtRate(rate);
        if (refs.r) refs.r.textContent = fmtComponent(out[0]);
        if (refs.i) refs.i.textContent = fmtComponent(out[1]);
        if (refs.c) refs.c.textContent = fmtComponent(out[2]);
      }
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [version, tab, collapsed]);

  if (deputies.length === 0) return null;

  return (
    <div className="relative-readout">
      <div className="rel-title">
        <span>Relative state · LVLH</span>
        {!collapsed && (
          <div className="rel-tabs" role="tablist">
            <button title="Numeric relative-state table (R/I/C, range, range-rate)"
              className={`rel-tab${tab === 'table' ? ' active' : ''}`}
              role="tab"
              aria-selected={tab === 'table'}
              onClick={() => setTab('table')}
            >
              Table
            </button>
            <button title="Distance-vs-time graph over the scenario window"
              className={`rel-tab${tab === 'graph' ? ' active' : ''}`}
              role="tab"
              aria-selected={tab === 'graph'}
              onClick={() => setTab('graph')}
            >
              Graph
            </button>
          </div>
        )}
        <button
          className="panel-min"
          onClick={toggle}
          title={collapsed ? 'Expand' : 'Minimize'}
          aria-label={collapsed ? 'Expand' : 'Minimize'}
        >
          {collapsed ? '▸' : '▾'}
        </button>
      </div>
      {!collapsed && tab === 'graph' && data && (
        <DistanceChart
          ref={chartRef}
          deputies={deputies}
          epochMs={data.epochMs}
          colors={DEPUTY_COLORS}
        />
      )}
      {!collapsed && tab === 'table' && (
      <table>
        <thead>
          <tr>
            <th>deputy</th>
            <th>range</th>
            <th>ṙ m/s</th>
            <th>R</th>
            <th>I</th>
            <th>C</th>
            <th>closest approach</th>
          </tr>
        </thead>
        <tbody>
          {deputies.map((dep, idx) => (
            <tr key={dep.noradId}>
              <td className="rel-name">
                <span style={{ color: DEPUTY_COLORS[idx % DEPUTY_COLORS.length] }}>●</span> {dep.name}
              </td>
              <td ref={cellRef(dep.noradId, 'range')}>—</td>
              <td ref={cellRef(dep.noradId, 'rate')}>—</td>
              <td ref={cellRef(dep.noradId, 'r')}>—</td>
              <td ref={cellRef(dep.noradId, 'i')}>—</td>
              <td ref={cellRef(dep.noradId, 'c')}>—</td>
              <td className="rel-tca">{fmtTca(dep.tcaEpochMs, dep.tcaDistanceM)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      )}
    </div>
  );
}
