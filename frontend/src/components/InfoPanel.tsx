import { useStore } from '../store/useStore';

export default function InfoPanel() {
  const selectedId = useStore((s) => s.selectedId);
  const setSelectedId = useStore((s) => s.setSelectedId);

  if (selectedId === null) return null;

  return (
    <aside className="info-panel">
      <header className="info-header">
        <h2>Selected satellite</h2>
        <button className="close-btn" onClick={() => setSelectedId(null)} aria-label="Close">
          {'\u00D7'}
        </button>
      </header>
      <div className="info-content">
        <div className="info-row"><span>NORAD ID</span><span>{selectedId}</span></div>
      </div>
    </aside>
  );
}
