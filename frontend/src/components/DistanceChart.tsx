import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import type { DeputyRelative } from '../stream/relativeBuffer';
import { fmtDistance } from '../lib/format';

/**
 * Imperative handle so the parent's throttled rAF loop can move the "now" cursor
 * without re-rendering the (static) curves — same refs/rAF posture as the rest of
 * the relative-state UI (Decision 5: high-frequency data never goes through React).
 */
export interface DistanceChartHandle {
  setCursorT: (t: number) => void;
}

interface Props {
  deputies: DeputyRelative[];
  epochMs: number; // t=0 reference (scenario start)
  colors: string[]; // shared deputy palette (matches Table / ProximityView)
}

const HEIGHT = 150;
const M = { top: 12, right: 12, bottom: 20, left: 52 }; // plot margins (px)
const LONG_WINDOW_SEC = 2 * 86400; // ≥2 days visible → label axis by date, not clock

// Selectable visible spans. 'all' fits the whole scenario (overview, may render as
// an envelope band); the finite spans zoom in so individual orbits read as lines.
const WINDOWS = [
  { key: 'h', label: '1h', sec: 3600 },
  { key: '6h', label: '6h', sec: 6 * 3600 },
  { key: 'd', label: '1d', sec: 86400 },
  { key: 'w', label: '7d', sec: 7 * 86400 },
  { key: 'all', label: 'All', sec: Infinity },
] as const;
type WinKey = (typeof WINDOWS)[number]['key'];

/** Distance (m) from a stride-aware sample at offset `base`. */
function distAt(s: Float64Array, base: number): number {
  return Math.hypot(s[base + 1], s[base + 2], s[base + 3]);
}

/** First sample index whose time ≥ `target` (samples are time-sorted). */
function lowerBound(s: Float64Array, stride: number, n: number, target: number): number {
  let lo = 0;
  let hi = n;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (s[mid * stride] < target) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

/** "nice" log-spaced y ticks within [lo, hi] (both > 0). */
function logTicks(lo: number, hi: number, count = 4): number[] {
  const a = Math.log10(lo);
  const b = Math.log10(hi);
  const out: number[] = [];
  for (let i = 0; i < count; i++) out.push(10 ** (a + ((b - a) * i) / (count - 1)));
  return out;
}

/** Axis tick: MM-DD over multi-day spans, else HH:MM (UTC). */
function fmtAxis(ms: number, longWindow: boolean): string {
  const iso = new Date(ms).toISOString();
  return longWindow ? iso.slice(5, 10) : iso.slice(11, 16);
}

/** Cursor label: prepend the date when the visible span crosses days. */
function fmtCursor(ms: number, longWindow: boolean): string {
  const iso = new Date(ms).toISOString();
  return longWindow ? `${iso.slice(5, 10)} ${iso.slice(11, 16)}` : iso.slice(11, 16);
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

/**
 * Distance-vs-time graph (chief↔deputy range). A window toggle + pan scroller keep
 * only part of long scenarios on screen, so individual orbits read as crisp lines;
 * the 'All' overview falls back to a min/max envelope band when samples overplot the
 * pixels. Each visible deputy gets a labeled marker at its closest point in the view
 * (the headline "smallest distance"), and the legend toggles deputies on/off — the
 * y-axis rescales to whatever is shown, so isolating one deputy zooms in on it. Log
 * y-axis; the moving cursor is driven imperatively by the parent (Decision 5) and,
 * with "follow" on, pages the window to keep playback in view.
 */
const DistanceChart = forwardRef<DistanceChartHandle, Props>(function DistanceChart(
  { deputies, epochMs, colors },
  ref,
) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const cursorRef = useRef<SVGLineElement>(null);
  const cursorLabelRef = useRef<SVGTextElement>(null);
  const lastTRef = useRef(0); // last cursor time, for re-centering on window change
  const [width, setWidth] = useState(560);
  const [winKey, setWinKey] = useState<WinKey>('all');
  const [viewStart, setViewStart] = useState(0); // left edge, seconds since epoch
  const [follow, setFollow] = useState(true);
  const [hidden, setHidden] = useState<Set<number>>(() => new Set());

  // Track the container width so the SVG renders at real pixel size (crisp text,
  // correct cursor-x math) and reflows when the panel is resized.
  useLayoutEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    setWidth(el.clientWidth);
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width;
      if (w && w > 0) setWidth(w);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Stable color per deputy (by position in the full list), so filtering doesn't
  // recolor the survivors.
  const colorOf = useMemo(() => {
    const m = new Map<number, string>();
    deputies.forEach((d, i) => m.set(d.noradId, colors[i % colors.length]));
    return m;
  }, [deputies, colors]);

  const visible = useMemo(
    () => deputies.filter((d) => !hidden.has(d.noradId)),
    [deputies, hidden],
  );

  // Time extent comes from the full dataset (stable while filtering); the y-scale
  // comes only from the *visible* deputies, so hiding one rescales to fit the rest.
  const timeDomain = useMemo(() => {
    let tMin = Infinity;
    let tMax = -Infinity;
    for (const dep of deputies) {
      const n = Math.floor(dep.samples.length / dep.stride);
      if (n < 1) continue;
      tMin = Math.min(tMin, dep.samples[0]);
      tMax = Math.max(tMax, dep.samples[(n - 1) * dep.stride]);
    }
    return Number.isFinite(tMin) && tMax > tMin ? { tMin, tMax } : null;
  }, [deputies]);

  const yDomain = useMemo(() => {
    let dMin = Infinity;
    let dMax = -Infinity;
    for (const dep of visible) {
      const n = Math.floor(dep.samples.length / dep.stride);
      for (let k = 0; k < n; k++) {
        const d = distAt(dep.samples, k * dep.stride);
        if (d < dMin) dMin = d;
        if (d > dMax) dMax = d;
      }
    }
    if (!Number.isFinite(dMax)) return null;
    const lo = Math.max(1, dMin);
    return { dLo: lo, dHi: Math.max(lo * 1.0001, dMax) };
  }, [visible]);

  const duration = timeDomain ? timeDomain.tMax - timeDomain.tMin : 0;
  const avail = WINDOWS.filter((w) => w.key === 'all' || w.sec < duration);

  // On a new dataset, show all deputies, default to a span that resolves orbits as
  // lines (1d if the scenario is long, else the whole thing), reset pan/follow.
  useEffect(() => {
    if (!timeDomain) return;
    setHidden(new Set());
    const def: WinKey = duration > LONG_WINDOW_SEC ? 'd' : 'all';
    setWinKey(WINDOWS.some((w) => w.key === def && (w.key === 'all' || w.sec < duration)) ? def : 'all');
    setViewStart(timeDomain.tMin);
    setFollow(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [timeDomain]);

  const geom = useMemo(() => {
    if (!timeDomain || !yDomain) return null;
    const plotW = Math.max(10, width - M.left - M.right);
    const plotH = HEIGHT - M.top - M.bottom;
    const cols = Math.max(1, Math.round(plotW));

    const win = WINDOWS.find((w) => w.key === winKey) ?? WINDOWS[WINDOWS.length - 1];
    const windowSec = Math.min(win.sec, duration);
    const vStart = clamp(viewStart, timeDomain.tMin, timeDomain.tMax - windowSec);
    const vEnd = vStart + windowSec;
    const longWindow = windowSec >= LONG_WINDOW_SEC;

    const logLo = Math.log10(yDomain.dLo) - 0.05;
    const logHi = Math.log10(yDomain.dHi) + 0.05;
    const x = (t: number) => M.left + ((t - vStart) / windowSec) * plotW;
    const y = (d: number) =>
      M.top + (1 - (Math.log10(Math.max(1, d)) - logLo) / (logHi - logLo)) * plotH;

    type Curve = {
      color: string;
      kind: 'line' | 'band';
      geom: string;
      minD: number; // smallest distance within the visible window
      minT: number;
    };
    const curves: Curve[] = visible.map((dep) => {
      const s = dep.samples;
      const n = Math.floor(s.length / dep.stride);
      const color = colorOf.get(dep.noradId) ?? colors[0];
      if (n < 1) return { color, kind: 'line', geom: '', minD: NaN, minT: 0 };

      // Visible range plus one bracketing sample each side so lines reach the edges.
      const k0 = Math.max(0, lowerBound(s, dep.stride, n, vStart) - 1);
      const k1 = Math.min(n - 1, lowerBound(s, dep.stride, n, vEnd));
      const visN = k1 - k0 + 1;

      let minD = Infinity;
      let minT = 0;
      for (let k = k0; k <= k1; k++) {
        const base = k * dep.stride;
        const t = s[base];
        if (t < vStart || t > vEnd) continue; // closest point strictly within view
        const d = distAt(s, base);
        if (d < minD) {
          minD = d;
          minT = t;
        }
      }

      if (visN > cols * 1.5) {
        // Dense: min/max envelope band per pixel column (lower edge = closest-approach trend).
        const topY = new Float64Array(cols).fill(Infinity);
        const botY = new Float64Array(cols).fill(-Infinity);
        for (let k = k0; k <= k1; k++) {
          const base = k * dep.stride;
          const col = clamp(Math.floor(x(s[base]) - M.left), 0, cols - 1);
          const py = y(distAt(s, base));
          if (py < topY[col]) topY[col] = py;
          if (py > botY[col]) botY[col] = py;
        }
        const top: string[] = [];
        const bot: string[] = [];
        for (let c = 0; c < cols; c++) {
          if (!Number.isFinite(topY[c])) continue;
          const cx = (M.left + c + 0.5).toFixed(1);
          top.push(`${cx},${topY[c].toFixed(1)}`);
          bot.push(`${cx},${botY[c].toFixed(1)}`);
        }
        return {
          color,
          kind: 'band',
          geom: top.length ? `M${top.join(' L')} L${bot.reverse().join(' L')} Z` : '',
          minD,
          minT,
        };
      }
      const pts: string[] = [];
      for (let k = k0; k <= k1; k++) {
        const base = k * dep.stride;
        pts.push(`${x(s[base]).toFixed(1)},${y(distAt(s, base)).toFixed(1)}`);
      }
      return { color, kind: 'line', geom: pts.join(' '), minD, minT };
    });

    return {
      plotW,
      plotH,
      windowSec,
      vStart,
      vEnd,
      longWindow,
      tMin: timeDomain.tMin,
      tMax: timeDomain.tMax,
      x,
      y,
      curves,
      yTicks: logTicks(yDomain.dLo, yDomain.dHi).map((d) => ({ d, py: y(d) })),
      xTicks: Array.from({ length: 4 }, (_, i) => {
        const t = vStart + (windowSec * i) / 3;
        return { px: x(t), label: fmtAxis(epochMs + t * 1000, longWindow) };
      }),
    };
  }, [timeDomain, yDomain, duration, visible, colorOf, colors, epochMs, width, winKey, viewStart]);

  // Park the cursor whenever geometry changes; the parent's loop then drives it.
  useEffect(() => {
    if (geom && cursorRef.current) {
      const xPx = String(geom.x(clamp(lastTRef.current, geom.vStart, geom.vEnd)).toFixed(1));
      cursorRef.current.setAttribute('x1', xPx);
      cursorRef.current.setAttribute('x2', xPx);
    }
  }, [geom]);

  useImperativeHandle(
    ref,
    () => ({
      setCursorT: (t: number) => {
        if (!geom) return;
        lastTRef.current = t;
        // Follow: page the window when playback leaves the visible span.
        if (follow && geom.windowSec < duration && (t < geom.vStart || t > geom.vEnd)) {
          const ns = clamp(t - 0.1 * geom.windowSec, geom.tMin, geom.tMax - geom.windowSec);
          setViewStart(ns);
          return; // recompute will redraw the cursor via the effect above
        }
        const clamped = clamp(t, geom.vStart, geom.vEnd);
        const xPx = geom.x(clamped);
        const line = cursorRef.current;
        const label = cursorLabelRef.current;
        if (line) {
          const v = xPx.toFixed(1);
          line.setAttribute('x1', v);
          line.setAttribute('x2', v);
        }
        if (label) {
          const nearRight = xPx > M.left + geom.plotW - 48;
          label.setAttribute('x', (nearRight ? xPx - 4 : xPx + 4).toFixed(1));
          label.setAttribute('text-anchor', nearRight ? 'end' : 'start');
          label.textContent = fmtCursor(epochMs + clamped * 1000, geom.longWindow);
        }
      },
    }),
    [geom, epochMs, follow, duration],
  );

  // Re-center the visible window on the cursor when the span preset changes.
  const pickWindow = (key: WinKey) => {
    setWinKey(key);
    if (!timeDomain) return;
    const win = WINDOWS.find((w) => w.key === key);
    const windowSec = Math.min(win?.sec ?? Infinity, duration);
    setViewStart(clamp(lastTRef.current - windowSec / 2, timeDomain.tMin, timeDomain.tMax - windowSec));
  };

  const toggleDeputy = (noradId: number) => {
    setHidden((prev) => {
      const next = new Set(prev);
      if (next.has(noradId)) next.delete(noradId);
      else next.add(noradId);
      return next;
    });
  };

  const plotRight = geom ? M.left + geom.plotW : 0;
  const plotBottom = M.top + (HEIGHT - M.top - M.bottom);
  const panMax = geom ? Math.max(0, duration - geom.windowSec) : 0;

  return (
    <div className="distance-chart" ref={wrapRef}>
      {geom && avail.length > 1 && (
        <div className="dchart-controls">
          <div className="dchart-wins" role="group" aria-label="visible time span">
            {avail.map((w) => (
              <button title="Show this time span in the chart"
                key={w.key}
                className={`dchart-win${winKey === w.key ? ' active' : ''}`}
                onClick={() => pickWindow(w.key)}
              >
                {w.label}
              </button>
            ))}
          </div>
          <button
            className={`dchart-follow${follow ? ' active' : ''}`}
            onClick={() => setFollow((f) => !f)}
            title="Keep the playback cursor in view"
          >
            ⊙ follow
          </button>
        </div>
      )}

      {geom ? (
        <svg width={width} height={HEIGHT} role="img" aria-label="chief-relative distance over time">
          {geom.yTicks.map((tick, i) => (
            <g key={`y${i}`}>
              <line className="dchart-grid" x1={M.left} x2={plotRight} y1={tick.py} y2={tick.py} />
              <text className="dchart-axis" x={M.left - 6} y={tick.py + 3} textAnchor="end">
                {fmtDistance(tick.d)}
              </text>
            </g>
          ))}
          {geom.xTicks.map((tick, i) => (
            <text
              key={`x${i}`}
              className="dchart-axis"
              x={tick.px}
              y={plotBottom + 13}
              textAnchor={i === 0 ? 'start' : i === geom.xTicks.length - 1 ? 'end' : 'middle'}
            >
              {tick.label}
            </text>
          ))}
          {/* deputy curves: lines when sparse, a min/max envelope band when dense */}
          {geom.curves.map((c, i) =>
            c.geom === '' ? null : c.kind === 'band' ? (
              <path key={`c${i}`} className="dchart-band" d={c.geom} fill={c.color} stroke={c.color} />
            ) : (
              <polyline key={`c${i}`} className="dchart-line" points={c.geom} stroke={c.color} />
            ),
          )}
          {/* closest-approach marker (within view) — the "smallest distance", labeled */}
          {geom.curves.map((c, i) => {
            if (!Number.isFinite(c.minD)) return null;
            const mx = geom.x(c.minT);
            const my = geom.y(c.minD);
            const above = my > M.top + 16; // label above the point unless it's near the top
            const lx = clamp(mx, M.left + 18, plotRight - 18);
            return (
              <g key={`m${i}`}>
                <circle className="dchart-min-ring" cx={mx} cy={my} r={4.5} stroke={c.color} />
                <circle cx={mx} cy={my} r={1.6} fill={c.color} />
                <text
                  className="dchart-min-label"
                  x={lx}
                  y={above ? my - 7 : my + 13}
                  textAnchor="middle"
                >
                  {fmtDistance(c.minD)}
                </text>
              </g>
            );
          })}
          {/* moving "now" cursor (driven imperatively) */}
          <line ref={cursorRef} className="dchart-cursor" y1={M.top} y2={plotBottom} />
          <text ref={cursorLabelRef} className="dchart-cursor-label" y={M.top + 8} />
        </svg>
      ) : (
        <div className="dchart-empty">
          {deputies.length ? 'No deputies selected.' : 'Not enough data to plot.'}
        </div>
      )}

      {geom && panMax > 0 && (
        <input
          className="dchart-pan"
          type="range"
          min={0}
          max={panMax}
          step={Math.max(1, geom.windowSec / 200)}
          value={clamp(geom.vStart - geom.tMin, 0, panMax)}
          onChange={(e) => {
            setFollow(false);
            setViewStart((timeDomain?.tMin ?? 0) + Number(e.target.value));
          }}
          aria-label="scroll through time"
        />
      )}

      {deputies.length > 1 && (
        <div className="dchart-legend">
          {deputies.map((d) => {
            const off = hidden.has(d.noradId);
            return (
              <button
                key={d.noradId}
                className={`dchart-chip${off ? ' off' : ''}`}
                onClick={() => toggleDeputy(d.noradId)}
                title={off ? 'Show' : 'Hide'}
                aria-pressed={!off}
              >
                <span className="dchart-chip-dot" style={{ background: colorOf.get(d.noradId) }} />
                {d.name}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
});

export default DistanceChart;
