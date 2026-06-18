import { useStore } from '../store/useStore';
import { useCollapsed } from '../lib/usePanelChrome';

function fmt(n: number | null, digits: number, unit = ''): string {
  if (n === null || !Number.isFinite(n)) return '—';
  return `${n.toFixed(digits)}${unit}`;
}

/**
 * Inspection panel for the clicked satellite (UC-1: click inspects; committing
 * to a scenario role is an explicit button). The Set-as-chief / Add-as-deputy /
 * Remove actions write the composer slice (US-SCN-08/09/10).
 */
export default function InfoPanel() {
  const sat = useStore((s) => s.selectedSatellite);
  const setSelectedSatellite = useStore((s) => s.setSelectedSatellite);
  const composer = useStore((s) => s.composer);
  const setChief = useStore((s) => s.setChief);
  const addDeputy = useStore((s) => s.addDeputy);
  const promoteToChief = useStore((s) => s.promoteToChief);
  const removeFromScenario = useStore((s) => s.removeFromScenario);
  const { collapsed, toggle } = useCollapsed('info');

  if (!sat) return null;

  const isChief = composer.chiefId === sat.noradId;
  const isDeputy = composer.deputyIds.includes(sat.noradId);
  const isMember = isChief || isDeputy;
  const hasChief = composer.chiefId !== null;

  function onSetChief() {
    // Replace-with-confirm when a different chief already exists (UC-1 edge case).
    if (hasChief && !isChief) {
      const ok = window.confirm(
        `Replace the current chief with ${sat!.name}? Existing deputies will be ` +
          `re-expressed in the new chief's LVLH frame.`,
      );
      if (!ok) return;
    }
    setChief(sat!.noradId);
  }

  return (
    <aside className="info-panel">
      <header className="info-header">
        <h2>Selected satellite</h2>
        <div className="info-header-actions">
          <button
            className="panel-min"
            onClick={toggle}
            title={collapsed ? 'Expand' : 'Minimize'}
            aria-label={collapsed ? 'Expand' : 'Minimize'}
          >
            {collapsed ? '▸' : '▾'}
          </button>
          <button
            className="close-btn"
            onClick={() => setSelectedSatellite(null)}
            aria-label="Close"
          >
            {'×'}
          </button>
        </div>
      </header>
      {!collapsed && (
      <div className="info-content">
        <div className="info-name">{sat.name}</div>
        <div className="info-row"><span>NORAD ID</span><span>{sat.noradId}</span></div>
        <div className="info-row"><span>Latitude</span><span>{fmt(sat.latitudeDeg, 2, '°')}</span></div>
        <div className="info-row"><span>Longitude</span><span>{fmt(sat.longitudeDeg, 2, '°')}</span></div>
        <div className="info-row"><span>Altitude</span><span>{fmt(sat.altitudeKm, 1, ' km')}</span></div>
        <div className="info-row"><span>Inclination</span><span>{fmt(sat.inclinationDeg, 2, '°')}</span></div>
        <div className="info-row"><span>Period</span><span>{fmt(sat.periodMinutes, 1, ' min')}</span></div>

        <div className="info-actions">
          {isMember ? (
            <>
              {isDeputy && (
                <button
                  className="role-btn"
                  onClick={() => promoteToChief(sat.noradId)}
                  title="Swap roles: make this the chief; the current chief becomes a deputy"
                >
                  Make chief
                </button>
              )}
              <button className="role-btn" onClick={() => removeFromScenario(sat.noradId)}>
                {isChief ? 'Remove chief from scenario' : 'Remove from scenario'}
              </button>
            </>
          ) : (
            <>
              <button className="role-btn" onClick={onSetChief}>
                Set as chief
              </button>
              <button
                className="role-btn"
                onClick={() => addDeputy(sat.noradId)}
                disabled={!hasChief}
                title={hasChief ? '' : 'Designate a chief first'}
              >
                Add as deputy
              </button>
            </>
          )}
        </div>
      </div>
      )}
    </aside>
  );
}
