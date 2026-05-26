import Globe from './components/Globe';
import TimeController from './components/TimeController';
import InfoPanel from './components/InfoPanel';
import StatsOverlay from './components/StatsOverlay';
import FilterPanel from './components/FilterPanel';
import './App.css';

export default function App() {
  return (
    <div className="app">
      <div className="globe-container">
        <Globe />
      </div>

      <header className="top-bar">
        <div className="brand">Orbit</div>
        <input
          type="search"
          placeholder="Search satellites..."
          className="search"
          aria-label="Search satellites"
        />
      </header>

      <FilterPanel />
      <StatsOverlay />
      <InfoPanel />
      <TimeController />
    </div>
  );
}
