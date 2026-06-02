import { useState, type FormEvent } from 'react';
import Globe from './components/Globe';
import TimeController from './components/TimeController';
import InfoPanel from './components/InfoPanel';
import StatsOverlay from './components/StatsOverlay';
import FilterPanel from './components/FilterPanel';
import StatusChip from './components/StatusChip';
import ScenarioPanel from './scenario/ScenarioPanel';
import { useStore } from './store/useStore';
import './App.css';

export default function App() {
  const [query, setQuery] = useState('');
  const [notFound, setNotFound] = useState(false);

  function onSearch(e: FormEvent) {
    e.preventDefault();
    const q = query.trim();
    if (!q) return;
    const { catalogIndex, requestFocus } = useStore.getState();

    // Numeric → exact NORAD id; otherwise name match (prefix preferred).
    let match = /^\d+$/.test(q)
      ? catalogIndex.find((s) => s.noradId === Number(q))
      : undefined;
    if (!match) {
      const lower = q.toLowerCase();
      match =
        catalogIndex.find((s) => s.name.toLowerCase().startsWith(lower)) ??
        catalogIndex.find((s) => s.name.toLowerCase().includes(lower));
    }

    if (match) {
      setNotFound(false);
      requestFocus(match.noradId);
    } else {
      setNotFound(true);
    }
  }

  return (
    <div className="app">
      <div className="globe-container">
        <Globe />
      </div>

      <header className="top-bar">
        <div className="brand">Orbit</div>
        <form className="search-form" onSubmit={onSearch}>
          <input
            type="search"
            placeholder="Search name or NORAD ID…"
            className={notFound ? 'search search-error' : 'search'}
            aria-label="Search satellites"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              if (notFound) setNotFound(false);
            }}
          />
        </form>
        <StatusChip />
      </header>

      <FilterPanel />
      <ScenarioPanel />
      <StatsOverlay />
      <button
        className="reset-view-btn"
        onClick={() => useStore.getState().resetCamera()}
        title="Reset the camera to a global view"
      >
        ⌂ Reset view
      </button>
      <InfoPanel />
      <TimeController />
    </div>
  );
}
