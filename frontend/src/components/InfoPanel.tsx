import { useStore } from '../store/useStore';

function fmt(n: number | null, digits: number, unit = ''): string {
  if (n === null || !Number.isFinite(n)) return '—';
  return `${n.toFixed(digits)}${unit}`;
}

/**
 * Inspection panel for the clicked satellite (UC-1: click inspects; committing
 * to a scenario role is an explicit button). The Set-as-chief / Add-as-deputy
 * buttons are present but disabled in Phase 2 — wired in Phase 3 (US-SCN-08/09).
 */
export default function InfoPanel() {
  const sat = useStore((s) => s.selectedSatellite);
  const setSelectedSatellite = useStore((s) => s.setSelectedSatellite);

  if (!sat) return null;

  return (
    <aside className="info-panel">
      <header className="info-header">
        <h2>Selected satellite</h2>
        <button
          className="close-btn"
          onClick={() => setSelectedSatellite(null)}
          aria-label="Close"
        >
          {'×'}
        </button>
      </header>
      <div className="info-content">
        <div className="info-name">{sat.name}</div>
        <div className="info-row"><span>NORAD ID</span><span>{sat.noradId}</span></div>
        <div className="info-row"><span>Latitude</span><span>{fmt(sat.latitudeDeg, 2, '°')}</span></div>
        <div className="info-row"><span>Longitude</span><span>{fmt(sat.longitudeDeg, 2, '°')}</span></div>
        <div className="info-row"><span>Altitude</span><span>{fmt(sat.altitudeKm, 1, ' km')}</span></div>
        <div className="info-row"><span>Inclination</span><span>{fmt(sat.inclinationDeg, 2, '°')}</span></div>
        <div className="info-row"><span>Period</span><span>{fmt(sat.periodMinutes, 1, ' min')}</span></div>

        <div className="info-actions">
          <button className="role-btn" disabled title="Scenario roles arrive in Phase 3">
            Set as chief
          </button>
          <button className="role-btn" disabled title="Scenario roles arrive in Phase 3">
            Add as deputy
          </button>
        </div>
      </div>
    </aside>
  );
}
