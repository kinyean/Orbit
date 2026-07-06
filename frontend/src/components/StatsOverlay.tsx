import { useState } from 'react';
import { useStore } from '../store/useStore';
import PerfHud from './PerfHud';

const PERF_KEY = 'orbit.perf.hud';

function perfInitiallyOn(): boolean {
  try {
    if (new URLSearchParams(window.location.search).get('perf') === '1') return true;
    return localStorage.getItem(PERF_KEY) === '1';
  } catch {
    return false;
  }
}

/**
 * Live catalog counts: total tracked vs currently visible after constellation
 * filtering. Replaces the Phase-0 hard-coded mock numbers. Phase 11 adds the
 * ⏱ toggle for the performance HUD (US-UX-03, §5.1).
 */
export default function StatsOverlay() {
  const total = useStore((s) => s.catalogTotal);
  const index = useStore((s) => s.catalogIndex);
  const active = useStore((s) => s.filters.constellations);
  const [perfOn, setPerfOn] = useState(perfInitiallyOn);

  // The Visible/Catalog counts describe the catalog layer. In scenario mode the
  // catalog is hidden (Globe restores it only when `showCatalogInScenario`), so the
  // counts are irrelevant then — hide them unless the user has re-shown the catalog.
  // The ⏱ perf toggle stays (it measures the proximity view's FPS, §5.1).
  const scenarioActive = useStore((s) => s.loadedScenario !== null);
  const showCatalogInScenario = useStore((s) => s.showCatalogInScenario);
  const catalogShown = !scenarioActive || showCatalogInScenario;

  // A satellite is hidden only if it belongs to a constellation that is toggled
  // off; non-constellation satellites are always visible.
  const activeSet = new Set(active);
  let hidden = 0;
  for (const entry of index) {
    if (entry.constellation && !activeSet.has(entry.constellation)) hidden++;
  }
  const visible = Math.max(0, total - hidden);

  const togglePerf = () => {
    const next = !perfOn;
    setPerfOn(next);
    try {
      localStorage.setItem(PERF_KEY, next ? '1' : '0');
    } catch {
      // storage unavailable — the toggle just doesn't persist
    }
  };

  const perfButton = (
    <button
      className={`perf-toggle${catalogShown ? '' : ' perf-toggle-standalone'}${perfOn ? ' active' : ''}`}
      onClick={togglePerf}
      title="Toggle the performance HUD (FPS, scrub latency, load time — SRS §5.1)"
      aria-label="Toggle performance HUD"
    >
      ⏱
    </button>
  );

  return (
    <>
      {catalogShown ? (
        // Full counts box (catalog layer visible): counts + the ⏱ toggle inside.
        <div className="stats-overlay">
          <div className="stat">
            <span className="stat-label">Visible</span>
            <span className="stat-value">{visible.toLocaleString()}</span>
          </div>
          <div className="stat">
            <span className="stat-label">Catalog</span>
            <span className="stat-value">{total.toLocaleString()}</span>
          </div>
          {perfButton}
        </div>
      ) : (
        // Catalog hidden in scenario mode: drop the empty box and dock the lone
        // ⏱ toggle into the right-side button column (under "Hide proximity").
        perfButton
      )}
      {perfOn && <PerfHud />}
    </>
  );
}
