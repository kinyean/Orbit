import { useEffect } from 'react';
import { useStore } from '../store/useStore';

/**
 * Scenario panel (Phase 3A). A real saved-scenario list + the composer card.
 *
 *   - List loads on mount (GET /scenarios). Click a row to load it into the
 *     composer; the × archives it (soft-delete).
 *   - Save is enabled once a chief is designated and the composer is dirty.
 *     First save prompts for a name (POST → v1); later saves create a new
 *     version (PUT), and renaming is just saving under a new name.
 *   - Chief/deputies show display names (resolved from the catalog index), not
 *     bare NORAD ids.
 *
 * Composer state lives in the Zustand store (US-SCN-02); the info panel's
 * action buttons write it (US-SCN-08/09/10).
 */
export default function ScenarioPanel() {
  const composer = useStore((s) => s.composer);
  const scenarios = useStore((s) => s.scenarios);
  const catalogIndex = useStore((s) => s.catalogIndex);
  const loadScenarios = useStore((s) => s.loadScenarios);
  const loadScenario = useStore((s) => s.loadScenario);
  const deleteScenario = useStore((s) => s.deleteScenario);
  const saveScenario = useStore((s) => s.saveScenario);

  useEffect(() => {
    void loadScenarios();
  }, [loadScenarios]);

  const nameFor = (id: number) =>
    catalogIndex.find((s) => s.noradId === id)?.name ?? `NORAD ${id}`;

  const canSave = composer.isDirty && composer.chiefId !== null;

  async function onSave() {
    const current = composer.scenarioId
      ? scenarios.find((s) => s.id === composer.scenarioId)?.name ?? ''
      : '';
    const name = window.prompt('Scenario name', current);
    if (name === null) return; // cancelled
    const trimmed = name.trim();
    if (!trimmed) return;
    try {
      await saveScenario(trimmed);
    } catch {
      window.alert('Save failed — a scenario with that name may already exist.');
    }
  }

  return (
    <aside className="scenario-panel">
      <div className="scenario-header">
        <h3>Scenarios</h3>
        <button
          className="scenario-save-btn"
          onClick={onSave}
          disabled={!canSave}
          title={canSave ? 'Save the current composer' : 'Designate a chief and make a change to save'}
        >
          Save
        </button>
      </div>

      {scenarios.length === 0 ? (
        <div className="scenario-empty">No saved scenarios</div>
      ) : (
        <ul className="scenario-list">
          {scenarios.map((s) => (
            <li
              key={s.id}
              className={s.id === composer.scenarioId ? 'scenario-item active' : 'scenario-item'}
            >
              <button className="scenario-load" onClick={() => s.id && void loadScenario(s.id)}>
                <span className="scenario-name">{s.name}</span>
                <span className="scenario-meta">
                  {nameFor(s.chiefNoradId ?? -1)}
                  {(s.deputyNoradIds?.length ?? 0) > 0 && ` +${s.deputyNoradIds!.length}`}
                  {` · v${s.latestVersionNo ?? 1}`}
                </span>
              </button>
              <button
                className="scenario-delete"
                title="Delete scenario"
                aria-label={`Delete ${s.name}`}
                onClick={() =>
                  s.id && window.confirm(`Delete "${s.name}"?`) && void deleteScenario(s.id)
                }
              >
                {'×'}
              </button>
            </li>
          ))}
        </ul>
      )}

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
              <span className="composer-id">{nameFor(composer.chiefId)}</span>
            </div>
            {composer.deputyIds.length > 0 && (
              <div className="composer-deputies">
                <span className="composer-role">Deputies</span>
                <ul>
                  {composer.deputyIds.map((id) => (
                    <li key={id}>{nameFor(id)}</li>
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
