import { useEffect, useState } from 'react';
import { useStore } from '../store/useStore';
import { HELP_SEEN_EVENT, HELP_SEEN_KEY, markHelpSeen } from './HelpOverlay';

/**
 * One-time onboarding callout (Phase 11 — SRS §5.6.1: "a new user shall be able to
 * load a sample scenario and play it back without prior training"). Points a fresh
 * browser at the seeded demos + the Help button; dismissed by ×, by opening Help,
 * or by loading any scenario — and never shown again (localStorage).
 */
export default function FirstRunHint() {
  const [visible, setVisible] = useState(() => {
    try {
      return localStorage.getItem(HELP_SEEN_KEY) === null;
    } catch {
      return false;
    }
  });
  const scenarioLoaded = useStore((s) => s.loadedScenario !== null);

  // Opening Help marks the key + fires the event — hide without a re-mount.
  useEffect(() => {
    const hide = () => setVisible(false);
    window.addEventListener(HELP_SEEN_EVENT, hide);
    return () => window.removeEventListener(HELP_SEEN_EVENT, hide);
  }, []);

  // Loading a scenario means the user found their way — dismiss for good.
  useEffect(() => {
    if (visible && scenarioLoaded) {
      markHelpSeen();
      setVisible(false);
    }
  }, [visible, scenarioLoaded]);

  if (!visible || scenarioLoaded) return null;
  return (
    <div className="first-run-hint" role="note">
      <span>
        New here? Open <b>Scenarios</b> and load a <b>Demo</b> — or press <b>?</b> (top right) for a
        quick tour.
      </span>
      <button
        className="first-run-dismiss"
        onClick={() => {
          markHelpSeen();
          setVisible(false);
        }}
        title="Dismiss"
        aria-label="Dismiss hint"
      >
        ×
      </button>
    </div>
  );
}
