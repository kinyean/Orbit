import { useStore } from '../store/useStore';

const CONSTELLATIONS = ['Starlink', 'OneWeb', 'GPS', 'Galileo', 'BeiDou', 'Iridium'];

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
