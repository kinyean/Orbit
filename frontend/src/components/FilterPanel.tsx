import { useStore } from '../store/useStore';
import { CONSTELLATIONS } from '../lib/constellations';

/**
 * Constellation visibility toggles. Each defaults on; unchecking hides that
 * group to declutter (non-constellation satellites stay visible). State
 * persists in localStorage (US-CAT-06). Opened/closed from the left-edge dock.
 */
export default function FilterPanel() {
  const filters = useStore((s) => s.filters);
  const toggleConstellation = useStore((s) => s.toggleConstellation);
  const closePanel = useStore((s) => s.closePanel);

  return (
    <aside className="filter-panel">
      <div className="panel-head-row">
        <h3>Constellations</h3>
        <button
          className="panel-min"
          onClick={() => closePanel('filters')}
          title="Close"
          aria-label="Close"
        >
          ✕
        </button>
      </div>
      {CONSTELLATIONS.map((name) => (
        <label key={name} className="filter-row">
          <input title="Show or hide this constellation's satellites"
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
