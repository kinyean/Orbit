import { useStore } from '../store/useStore';
import { CONSTELLATIONS } from '../lib/constellations';
import { useCollapsed } from '../lib/usePanelChrome';

/**
 * Constellation visibility toggles. Each defaults on; unchecking hides that
 * group to declutter (non-constellation satellites stay visible). State
 * persists in localStorage (US-CAT-06). Minimizable (chrome persisted too).
 */
export default function FilterPanel() {
  const filters = useStore((s) => s.filters);
  const toggleConstellation = useStore((s) => s.toggleConstellation);
  const { collapsed, toggle } = useCollapsed('filters');

  return (
    <aside className="filter-panel">
      <div className="panel-head-row">
        <h3>Constellations</h3>
        <button
          className="panel-min"
          onClick={toggle}
          title={collapsed ? 'Expand' : 'Minimize'}
          aria-label={collapsed ? 'Expand' : 'Minimize'}
        >
          {collapsed ? '▸' : '▾'}
        </button>
      </div>
      {!collapsed &&
        CONSTELLATIONS.map((name) => (
          <label key={name} className="filter-row">
            <input
              type="checkbox"
              checked={filters.constellations.includes(name)}
              onChange={() => toggleConstellation(name)}
            />
            {name}
          </label>
        ))}
    </aside>
  );
}
