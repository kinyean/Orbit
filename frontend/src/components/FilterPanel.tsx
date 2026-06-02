import { useStore } from '../store/useStore';
import { CONSTELLATIONS } from '../lib/constellations';

/**
 * Constellation visibility toggles. Each defaults on; unchecking hides that
 * group to declutter (non-constellation satellites stay visible). State
 * persists in localStorage (US-CAT-06).
 */
export default function FilterPanel() {
  const filters = useStore((s) => s.filters);
  const toggleConstellation = useStore((s) => s.toggleConstellation);

  return (
    <aside className="filter-panel">
      <h3>Constellations</h3>
      {CONSTELLATIONS.map((name) => (
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
