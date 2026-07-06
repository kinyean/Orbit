import { useStore } from '../store/useStore';

/**
 * Left-edge panel launcher (declutter). A thin vertical rail of buttons; each
 * toggles one floating panel open/closed. Panels are closed by default so the
 * globe stays clear, and several may be open at once (each button is independent).
 *
 * The rail, the App render gate, and each panel's own ✕ all read/write the single
 * `openPanels` store slice, so a button's active state and its panel stay in sync.
 * Scenario-only tools appear only while a scenario is loaded; Constellations and
 * Export are always available. Scenarios/Composer stays always visible (not docked).
 */
interface DockItem {
  id: string;
  glyph: string;
  /** Full name — the tooltip + aria-label (every control is titled, §5.6.2). */
  name: string;
  /** Only shown while a scenario is loaded (the panel itself needs one). */
  scenarioOnly: boolean;
}

const ITEMS: DockItem[] = [
  { id: 'filters', glyph: '✦', name: 'Constellations', scenarioOnly: false },
  { id: 'maneuvers', glyph: 'Δv', name: 'Maneuvers · ΔV', scenarioOnly: true },
  { id: 'sensors', glyph: '◹', name: 'Sensors · FOV', scenarioOnly: true },
  { id: 'environment', glyph: '☀', name: 'Environment · Events', scenarioOnly: true },
  { id: 'montecarlo', glyph: '🎲', name: 'Monte Carlo · dispersion', scenarioOnly: true },
  { id: 'audit', glyph: '🕓', name: 'Audit & history', scenarioOnly: true },
  { id: 'export', glyph: '⤓', name: 'Export · PNG / MP4 / data', scenarioOnly: false },
];

export default function PanelDock() {
  const scenarioActive = useStore((s) => s.loadedScenario !== null);
  const openPanels = useStore((s) => s.openPanels);
  const togglePanel = useStore((s) => s.togglePanel);

  const items = ITEMS.filter((it) => !it.scenarioOnly || scenarioActive);

  return (
    <nav className="panel-dock" aria-label="Panels">
      {items.map((it) => {
        const open = !!openPanels[it.id];
        return (
          <button
            key={it.id}
            className={open ? 'dock-btn active' : 'dock-btn'}
            onClick={() => togglePanel(it.id)}
            title={it.name}
            aria-label={it.name}
            aria-pressed={open}
          >
            <span aria-hidden>{it.glyph}</span>
          </button>
        );
      })}
    </nav>
  );
}
