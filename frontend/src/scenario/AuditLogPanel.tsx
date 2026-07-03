import { useCallback, useEffect, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { api } from '../api/client';
import type { components } from '../api/schema';
import { useStore } from '../store/useStore';
import { useCollapsed, usePanelSize, usePanelPosition } from '../lib/usePanelChrome';

type AuditEntry = components['schemas']['AuditEntryResponse'];
type VersionSummary = components['schemas']['ScenarioVersionSummary'];
type Change = components['schemas']['Change'];

/** Per-version diff cache entry: not-fetched (undefined), loading, error, or the change list. */
type DiffState = 'loading' | 'error' | Change[];

/**
 * Audit-log & version-history panel (Phase 10, US-INFRA-06 / US-SCN-04). Reads
 * the loaded scenario's immutable version history + audit trail through the
 * generated client (GET /scenarios/{id}/versions and /audit) — the "who changed
 * what, when" view Frank needs for compliance/traceability. Read-only; refetches
 * when the loaded scenario changes or on demand.
 */
export default function AuditLogPanel() {
  const loaded = useStore((s) => s.loadedScenario);
  const scenarioId = loaded?.id ?? null;

  const [versions, setVersions] = useState<VersionSummary[]>([]);
  const [audit, setAudit] = useState<AuditEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<number | null>(null);
  const [diffs, setDiffs] = useState<Record<number, DiffState>>({});

  const { pos, setPos, commitPos } = usePanelPosition('audit', { x: 248, y: 640 });
  const { collapsed, toggle } = useCollapsed('audit');
  const panelRef = usePanelSize<HTMLElement>('audit', collapsed);

  const refresh = useCallback(async (id: string) => {
    setLoading(true);
    setError(null);
    try {
      const [v, a] = await Promise.all([
        api.GET('/scenarios/{id}/versions', { params: { path: { id } } }),
        api.GET('/scenarios/{id}/audit', { params: { path: { id } } }),
      ]);
      if (v.error || a.error) throw new Error('request failed');
      setVersions(v.data ?? []);
      setAudit(a.data ?? []);
      setDiffs({}); // history changed — drop the diff cache
    } catch {
      setError('Could not load audit history.');
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch a version's diff-vs-predecessor on first expand; cache it.
  const loadDiff = useCallback(async (id: string, versionNo: number) => {
    setDiffs((d) => ({ ...d, [versionNo]: 'loading' }));
    const r = await api.GET('/scenarios/{id}/versions/{v}/diff', {
      params: { path: { id, v: versionNo } },
    });
    setDiffs((d) => ({
      ...d,
      [versionNo]: r.error || !r.data ? 'error' : (r.data.changes ?? []),
    }));
  }, []);

  const toggleVersion = useCallback(
    (versionNo: number) => {
      setExpanded((cur) => (cur === versionNo ? null : versionNo));
      if (scenarioId && diffs[versionNo] === undefined) void loadDiff(scenarioId, versionNo);
    },
    [scenarioId, diffs, loadDiff],
  );

  useEffect(() => {
    if (scenarioId) void refresh(scenarioId);
    else {
      setVersions([]);
      setAudit([]);
    }
    setExpanded(null);
    setDiffs({});
  }, [scenarioId, refresh]);

  function onDragStart(e: ReactPointerEvent) {
    if ((e.target as HTMLElement).closest('button')) return;
    e.preventDefault();
    const startX = e.clientX;
    const startY = e.clientY;
    const origin = { ...pos };
    let last = origin;
    const move = (ev: PointerEvent) => {
      last = {
        x: Math.min(window.innerWidth - 80, Math.max(0, origin.x + (ev.clientX - startX))),
        y: Math.min(window.innerHeight - 60, Math.max(0, origin.y + (ev.clientY - startY))),
      };
      setPos(last);
    };
    const up = () => {
      commitPos(last);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  if (!scenarioId) return null;

  return (
    <aside ref={panelRef} className={`maneuver-panel${collapsed ? ' is-collapsed' : ''}`} style={{ left: pos.x, top: pos.y }}>
      <div className="mvr-drag" onPointerDown={onDragStart} title="Drag to move">
        <span className="mvr-drag-title"><span className="mvr-grip" aria-hidden>⠿</span> Audit &amp; history</span>
        <button className="panel-min" onClick={toggle} title={collapsed ? 'Expand' : 'Minimize'} aria-label={collapsed ? 'Expand' : 'Minimize'}>
          {collapsed ? '▸' : '▾'}
        </button>
      </div>
      {!collapsed && (
        <>
          <div className="mvr-add">
            <button type="button" onClick={() => void refresh(scenarioId)} disabled={loading}>
              {loading ? 'Loading…' : 'Refresh'}
            </button>
            {error && <div className="mvr-msg">{error}</div>}
          </div>

          <div className="mvr-deputy">
            <div className="mvr-deputy-head">
              <span className="mvr-deputy-name">Versions</span>
              <span className="mvr-budget">{versions.length}</span>
            </div>
            <div className="audit-list">
              {versions.length === 0 && <div className="mvr-note">No versions.</div>}
              {versions.length > 0 && <div className="mvr-note">Click a version to see what changed.</div>}
              {versions.map((v) => {
                const vn = v.versionNo ?? 0;
                const isOpen = expanded === vn;
                const d = diffs[vn];
                return (
                  <div key={vn} className="audit-ver-group">
                    <button
                      type="button"
                      className={`audit-row audit-ver-row${isOpen ? ' is-open' : ''}`}
                      onClick={() => toggleVersion(vn)}
                    >
                      <span className="audit-caret" aria-hidden>{isOpen ? '▾' : '▸'}</span>
                      <span className="audit-ver">v{vn}</span>
                      <span className="audit-actor">{v.authorEmail}</span>
                      <span className="audit-time">{fmtTime(v.createdAt)}</span>
                    </button>
                    {isOpen && (
                      <div className="audit-diff">
                        {d === 'loading' && <div className="mvr-note">Loading…</div>}
                        {d === 'error' && <div className="mvr-note">Could not load diff.</div>}
                        {Array.isArray(d) && d.length === 0 && (
                          <div className="mvr-note">No field changes.</div>
                        )}
                        {Array.isArray(d) &&
                          d.map((c, i) => (
                            <div key={i} className={`diff-line diff-${c.op}`}>
                              <span className="diff-op" aria-hidden>{opSymbol(c.op)}</span>
                              <span className="diff-cat">{c.category}</span>
                              <span className="diff-detail">{c.detail}</span>
                            </div>
                          ))}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>

          <div className="mvr-deputy">
            <div className="mvr-deputy-head">
              <span className="mvr-deputy-name">Audit trail</span>
              <span className="mvr-budget">{audit.length}</span>
            </div>
            <div className="audit-list">
              {audit.length === 0 && <div className="mvr-note">No audit entries.</div>}
              {audit.map((a, i) => (
                <div key={i} className="audit-row audit-row-entry">
                  <span className="audit-action">{a.action}</span>
                  <span className="audit-time">{fmtTime(a.timestamp)}</span>
                  {a.diffSummary && <span className="audit-summary">{a.diffSummary}</span>}
                  <span className="audit-actor">{a.actorEmail}</span>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </aside>
  );
}

/** Op → a compact glyph for the diff line (add / remove / change). */
function opSymbol(op?: string): string {
  if (op === 'add') return '+';
  if (op === 'remove') return '−';
  return '~';
}

/** ISO timestamp → a compact local date-time (best-effort; falls back to the raw string). */
function fmtTime(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}
