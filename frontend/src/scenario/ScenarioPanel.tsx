import { useEffect, useState } from 'react';
import { useStore } from '../store/useStore';
import { useCollapsed } from '../lib/usePanelChrome';

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
  const loadedScenario = useStore((s) => s.loadedScenario);
  const loadScenarios = useStore((s) => s.loadScenarios);
  const loadScenario = useStore((s) => s.loadScenario);
  const deleteScenario = useStore((s) => s.deleteScenario);
  const saveScenario = useStore((s) => s.saveScenario);
  const importMeasuredScenario = useStore((s) => s.importMeasuredScenario);
  const closeScenario = useStore((s) => s.closeScenario);
  const setComposerTimeRange = useStore((s) => s.setComposerTimeRange);
  const setComposerFidelity = useStore((s) => s.setComposerFidelity);
  const requestFocus = useStore((s) => s.requestFocus);
  const requestProximityFocus = useStore((s) => s.requestProximityFocus);
  const { collapsed, toggle } = useCollapsed('scenarios');

  useEffect(() => {
    void loadScenarios();
  }, [loadScenarios]);

  const nameFor = (id: number) =>
    catalogIndex.find((s) => s.noradId === id)?.name ?? `NORAD ${id}`;

  // Composer click → focus + select in BOTH viewports: the Cesium globe
  // recenters/tracks (and populates the info panel via requestFocus), and the
  // proximity camera rides that craft (the chief is the LVLH origin, so the
  // proximity view re-centers on it automatically).
  const focusMember = (id: number) => {
    requestFocus(id);
    requestProximityFocus(id);
  };

  // The scenario time window (UTC). <input type="datetime-local"> has no
  // timezone, so we treat its value as UTC: slice the ISO for display, append
  // ":00Z" to parse back. Editing either field marks the composer dirty;
  // saving creates a new version and re-streams (US-SCN time range, Phase 4).
  const toInput = (iso: string | null) => (iso ? iso.slice(0, 16) : '');
  function fromInput(value: string): string | null {
    if (!value) return null;
    const d = new Date(`${value}:00Z`);
    return Number.isNaN(d.getTime()) ? null : d.toISOString();
  }
  function onRangeChange(which: 'start' | 'end', value: string) {
    const iso = fromInput(value);
    if (!iso) return;
    const curStart = composer.start ?? new Date().toISOString();
    const curEnd = composer.end ?? new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
    setComposerTimeRange(which === 'start' ? iso : curStart, which === 'end' ? iso : curEnd);
  }

  // Measured-data import: a server-side WOD CSV path → a new scenario whose chief
  // is the measured craft (read-only truth). The reader runs backend-side.
  const [importOpen, setImportOpen] = useState(false);
  const [importPath, setImportPath] = useState('');
  const [importNorad, setImportNorad] = useState('');
  const [importBusy, setImportBusy] = useState(false);
  const [importErr, setImportErr] = useState<string | null>(null);

  async function onImport() {
    const path = importPath.trim();
    if (!path) return;
    const noradStr = importNorad.trim();
    const noradNum = noradStr ? Number(noradStr) : undefined;
    setImportBusy(true);
    setImportErr(null);
    try {
      await importMeasuredScenario(path, Number.isFinite(noradNum) ? noradNum : undefined);
      setImportPath('');
      setImportNorad('');
    } catch (e) {
      setImportErr((e as { message?: string })?.message ?? 'Import failed');
    } finally {
      setImportBusy(false);
    }
  }

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
        <div className="scenario-header-actions">
          {loadedScenario && (
            <button
              className="scenario-close-btn"
              onClick={closeScenario}
              title="Stop playback and return to the live catalog"
            >
              Close
            </button>
          )}
          <button
            className="scenario-save-btn"
            onClick={onSave}
            disabled={!canSave}
            title={canSave ? 'Save the current composer' : 'Designate a chief and make a change to save'}
          >
            Save
          </button>
          <button
            className="panel-min"
            onClick={toggle}
            title={collapsed ? 'Expand' : 'Minimize'}
            aria-label={collapsed ? 'Expand' : 'Minimize'}
          >
            {collapsed ? '▸' : '▾'}
          </button>
        </div>
      </div>
      {!collapsed && (
      <>
      {loadedScenario && (
        <div className="scenario-playing">▶ Playing: {loadedScenario.name}</div>
      )}

      <div className="scenario-import">
        <button
          className="scenario-import-toggle"
          onClick={() => setImportOpen((o) => !o)}
          title="Import a measured-telemetry CSV from the server"
        >
          <span className="scenario-import-caret">{importOpen ? '▾' : '▸'}</span>
          Import measured data
        </button>
        {importOpen && (
          <div className="scenario-import-body">
            <input
              type="text"
              className="scenario-import-path"
              placeholder="Server CSV path (e.g. /shared_folder/…)"
              value={importPath}
              onChange={(e) => setImportPath(e.target.value)}
              disabled={importBusy}
            />
            <div className="scenario-import-row">
              <input
                type="number"
                className="scenario-import-norad"
                placeholder="NORAD (auto)"
                title="Optional. Auto-detected from the file's satellite name; set it only to override or if the name isn't in the catalog."
                value={importNorad}
                onChange={(e) => setImportNorad(e.target.value)}
                disabled={importBusy}
              />
              <button
                className="scenario-import-btn"
                onClick={() => void onImport()}
                disabled={importBusy || !importPath.trim()}
              >
                {importBusy ? '…' : 'Import'}
              </button>
            </div>
            <div className="scenario-import-hint">
              Reads a measured-ephemeris CSV on the server into a new scenario (the
              craft becomes a read-only chief). NORAD is auto-detected from the file.
            </div>
            {importErr && <div className="scenario-import-err">{importErr}</div>}
          </div>
        )}
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
              <button
                type="button"
                className="composer-id composer-focus"
                onClick={() => focusMember(composer.chiefId!)}
                title="Focus this satellite in both views"
              >
                {nameFor(composer.chiefId)}
              </button>
            </div>
            {composer.deputyIds.length > 0 && (
              <div className="composer-deputies">
                <span className="composer-role">Deputies</span>
                <ul>
                  {composer.deputyIds.map((id) => (
                    <li key={id}>
                      <button
                        type="button"
                        className="composer-focus"
                        onClick={() => focusMember(id)}
                        title="Focus this satellite in both views"
                      >
                        {nameFor(id)}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <div className="composer-fidelity">
              <span className="composer-role">Fidelity</span>
              <select
                value={composer.fidelity}
                onChange={(e) => setComposerFidelity(e.target.value)}
                aria-label="Propagator fidelity"
              >
                <option value="sgp4">SGP4</option>
                <option value="numerical">Numerical (DP8(7))</option>
                <option value="cw">Clohessy–Wiltshire</option>
              </select>
            </div>
            <div className="composer-timerange">
              <span className="composer-role">Time range (UTC)</span>
              <label className="tr-field">
                <span>Start</span>
                <input
                  type="datetime-local"
                  value={toInput(composer.start)}
                  onChange={(e) => onRangeChange('start', e.target.value)}
                />
              </label>
              <label className="tr-field">
                <span>End</span>
                <input
                  type="datetime-local"
                  value={toInput(composer.end)}
                  onChange={(e) => onRangeChange('end', e.target.value)}
                />
              </label>
            </div>
          </>
        )}
      </div>
      </>
      )}
    </aside>
  );
}
