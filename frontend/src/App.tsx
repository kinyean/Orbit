import { useEffect, useRef, useState, type FormEvent, type PointerEvent as ReactPointerEvent } from 'react';
import Globe from './components/Globe';
import ProximityView from './views/ProximityView';
import RelativeReadout from './components/RelativeReadout';
import TimeController from './components/TimeController';
import Timeline from './components/Timeline';
import InfoPanel from './components/InfoPanel';
import StatsOverlay from './components/StatsOverlay';
import FilterPanel from './components/FilterPanel';
import StatusChip from './components/StatusChip';
import ScenarioPanel from './scenario/ScenarioPanel';
import ManeuverPanel from './scenario/ManeuverPanel';
import { useStore } from './store/useStore';
import { startClockEngine } from './store/clockEngine';
import './App.css';

export default function App() {
  const [query, setQuery] = useState('');
  const [notFound, setNotFound] = useState(false);

  // Split-screen: the proximity view appears only when a scenario is loaded; a
  // toggle unmounts it (frees the 2nd WebGL context). The divider sets the split.
  const scenarioActive = useStore((s) => s.loadedScenario !== null);
  const scenarioStreamError = useStore((s) => s.scenarioStreamError);
  const showCatalogInScenario = useStore((s) => s.showCatalogInScenario);
  const [proximityEnabled, setProximityEnabled] = useState(true);
  const [splitPct, setSplitPct] = useState(55);
  const viewportsRef = useRef<HTMLDivElement>(null);
  const proximityVisible = scenarioActive && proximityEnabled;

  function onDividerDown(e: ReactPointerEvent) {
    e.preventDefault();
    const el = viewportsRef.current;
    if (!el) return;
    const move = (ev: PointerEvent) => {
      const rect = el.getBoundingClientRect();
      const pct = ((ev.clientX - rect.left) / rect.width) * 100;
      setSplitPct(Math.min(80, Math.max(20, pct)));
    };
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  // The single clock writer: one rAF loop advances currentTime; both views read
  // it (Decision 11). Started once for the app's lifetime.
  useEffect(() => startClockEngine(), []);

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
      <div className="viewports" ref={viewportsRef}>
        <div
          className="viewport globe-pane"
          style={proximityVisible ? { flex: `0 0 ${splitPct}%` } : { flex: '1 1 100%' }}
        >
          <Globe />
        </div>
        {proximityVisible && (
          <>
            <div
              className="split-divider"
              role="separator"
              aria-label="Resize views"
              onPointerDown={onDividerDown}
            />
            <div className="viewport proximity-pane">
              <ProximityView />
            </div>
          </>
        )}
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

      {scenarioStreamError && (
        <div className="stream-error-banner" role="alert">
          {scenarioStreamError}
          <button
            className="stream-error-dismiss"
            onClick={() => useStore.getState().setScenarioStreamError(null)}
            title="Dismiss"
          >
            ×
          </button>
        </div>
      )}

      <FilterPanel />
      <ScenarioPanel />
      {scenarioActive && <ManeuverPanel />}
      <StatsOverlay />
      {scenarioActive && (
        <button
          className={showCatalogInScenario ? 'catalog-toggle active' : 'catalog-toggle'}
          onClick={() => useStore.getState().setShowCatalogInScenario(!showCatalogInScenario)}
          title={
            showCatalogInScenario
              ? 'Hide the rest of the catalog'
              : 'Show all catalog satellites so you can add one as a deputy (positions approximate at the scenario time)'
          }
        >
          {showCatalogInScenario ? '◉ Hide catalog' : '◎ Show catalog'}
        </button>
      )}
      <button
        className="reset-view-btn"
        onClick={() => useStore.getState().resetCamera()}
        title="Reset the camera to a global view"
      >
        ⌂ Reset view
      </button>
      {scenarioActive && (
        <button
          className="proximity-toggle"
          onClick={() => setProximityEnabled((v) => !v)}
          title={proximityEnabled ? 'Hide the proximity view' : 'Show the proximity view'}
        >
          {proximityEnabled ? '⊟ Hide proximity' : '⊞ Proximity view'}
        </button>
      )}
      <InfoPanel />
      {scenarioActive && <RelativeReadout />}
      <TimeController />
      <Timeline />
    </div>
  );
}
