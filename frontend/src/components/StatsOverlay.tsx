import { useStore } from '../store/useStore';

/**
 * Live catalog counts: total tracked vs currently visible after constellation
 * filtering. Replaces the Phase-0 hard-coded mock numbers.
 */
export default function StatsOverlay() {
  const total = useStore((s) => s.catalogTotal);
  const index = useStore((s) => s.catalogIndex);
  const active = useStore((s) => s.filters.constellations);

  // A satellite is hidden only if it belongs to a constellation that is toggled
  // off; non-constellation satellites are always visible.
  const activeSet = new Set(active);
  let hidden = 0;
  for (const entry of index) {
    if (entry.constellation && !activeSet.has(entry.constellation)) hidden++;
  }
  const visible = Math.max(0, total - hidden);

  return (
    <div className="stats-overlay">
      <div className="stat">
        <span className="stat-label">Visible</span>
        <span className="stat-value">{visible.toLocaleString()}</span>
      </div>
      <div className="stat">
        <span className="stat-label">Catalog</span>
        <span className="stat-value">{total.toLocaleString()}</span>
      </div>
    </div>
  );
}
