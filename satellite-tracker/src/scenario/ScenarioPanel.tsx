import { useStore } from '../store/useStore';

/**
 * Scenario panel — Phase 1 stub.
 *
 * Shows:
 *   - "No saved scenarios" placeholder (saved-list comes in Phase 3 with the
 *     scenario CRUD API).
 *   - The composer card: the current chief + deputies + dirty indicator.
 *
 * The composer state lives in the Zustand store (US-SCN-02). Setting the
 * chief / adding a deputy is wired from the info panel's action buttons
 * (Phase 3, US-SCN-08/09).
 */
export default function ScenarioPanel() {
  const composer = useStore((s) => s.composer);

  return (
    <aside className="scenario-panel">
      <h3>Scenarios</h3>
      <div className="scenario-empty">No saved scenarios</div>

      <div className="composer-card">
        <div className="composer-card-header">
          <span>Composer</span>
          {composer.isDirty && <span className="composer-dirty">unsaved</span>}
        </div>

        {composer.chiefId === null ? (
          <div className="composer-empty">No chief designated</div>
        ) : (
          <>
            <div className="composer-row">
              <span className="composer-role">Chief</span>
              <span className="composer-id">{composer.chiefId}</span>
            </div>
            {composer.deputyIds.length > 0 && (
              <div className="composer-deputies">
                <span className="composer-role">Deputies</span>
                <ul>
                  {composer.deputyIds.map((id) => (
                    <li key={id}>{id}</li>
                  ))}
                </ul>
              </div>
            )}
          </>
        )}
      </div>
    </aside>
  );
}
