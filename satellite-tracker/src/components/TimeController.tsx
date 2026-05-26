import { useStore } from '../store/useStore';

export default function TimeController() {
  const currentTime = useStore((s) => s.currentTime);
  const isPlaying = useStore((s) => s.isPlaying);
  const togglePlay = useStore((s) => s.togglePlay);

  const display = currentTime.toISOString().slice(0, 19).replace('T', ' ') + ' UTC';

  return (
    <div className="time-controller">
      <button className="play-btn" onClick={togglePlay} aria-label={isPlaying ? 'Pause' : 'Play'}>
        {isPlaying ? '\u23F8' : '\u25B6'}
      </button>
      <div className="time-display">{display}</div>
      <input
        type="range"
        className="scrubber"
        min={0}
        max={86400}
        defaultValue={43200}
        aria-label="Time scrubber"
      />
      <button className="speed-btn" aria-label="Speed">1x</button>
    </div>
  );
}
