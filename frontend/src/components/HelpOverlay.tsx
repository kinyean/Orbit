import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';

/**
 * Contextual help (Phase 11, US-UX-02 — SRS §5.6.2) + the §5.6.1 on-ramp: a `?`
 * button in the top bar opening a hand-rolled modal (no deps, Decision-22 posture)
 * with a quick start, a controls reference, and a mini-glossary distilled from
 * docs/glossary.md. Opening it marks `orbit.help.seen`, which also dismisses the
 * first-run hint (see {@link FirstRunHint}).
 */

export const HELP_SEEN_KEY = 'orbit.help.seen';
/** Fired on window when help is opened, so the first-run hint can dismiss itself. */
export const HELP_SEEN_EVENT = 'orbit:help-seen';

export function markHelpSeen(): void {
  try {
    localStorage.setItem(HELP_SEEN_KEY, '1');
  } catch {
    // storage unavailable (private mode) — the hint just reappears next load
  }
  window.dispatchEvent(new Event(HELP_SEEN_EVENT));
}

const GLOSSARY: [string, string][] = [
  ['Chief / deputy', 'The reference spacecraft (origin of the relative frame) and the spacecraft expressed relative to it.'],
  ['LVLH / RIC', 'The chief-centered frame: R radial-out, I in-track (along velocity), C cross-track (orbit normal). The proximity view draws R→x, I→y, C→z.'],
  ['ΔV', 'A velocity change (m/s) — the cost currency of maneuvers. The maneuver panel totals it per deputy.'],
  ['NMC', 'Natural Motion Circumnavigation — a fuel-free relative orbit where a deputy circles the chief.'],
  ['V-bar / R-bar', 'Approach/hold directions along the chief\'s velocity vector (V-bar) or radial direction (R-bar).'],
  ['TCA', 'Time of Closest Approach — when two craft reach minimum separation.'],
  ['AOS / LOS', 'Acquisition / Loss Of Sight — a target entering/leaving a sensor\'s field of view with a clear line of sight.'],
  ['FOV / boresight', 'A sensor\'s angular field of view, and its center pointing axis.'],
  ['Umbra / penumbra', 'Full / partial Earth shadow — eclipse bands on the timeline; craft dim in shadow.'],
  ['SNR', 'Signal-to-noise ratio of a sensor link (dB) — the timeline band turns red below the threshold.'],
];

export default function HelpOverlay() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const show = () => {
    markHelpSeen();
    setOpen(true);
  };

  return (
    <>
      <button
        className="help-btn"
        onClick={show}
        title="Help — quick start, controls, glossary"
        aria-label="Help"
      >
        ?
      </button>
      {open && createPortal(
        // Portalled to document.body so the overlay escapes the .top-bar stacking
        // context (position:absolute; z-index:10). Rendered inline, its z-index only
        // competed within the top-bar and the sibling panels (z-index 10, later in the
        // DOM) painted over it. At the document root its z-index:60 wins over the panels.
        <div className="help-overlay" role="dialog" aria-modal="true" aria-label="Help" onClick={() => setOpen(false)}>
          <div className="help-modal" onClick={(e) => e.stopPropagation()}>
            <div className="help-head">
              <span>Orbit — help</span>
              <button className="help-close" onClick={() => setOpen(false)} title="Close (Esc)" aria-label="Close help">
                ×
              </button>
            </div>
            <div className="help-columns">
              <section>
                <h3>Quick start</h3>
                <ol>
                  <li>Open <b>Scenarios</b> (left) and load a <b>Demo</b> — e.g. “close formation (NMC)”.</li>
                  <li>Press <b>▶</b> to play; drag the <b>timeline</b> to scrub; change the rate (0.01×–10000×, reverse).</li>
                  <li>The right pane is the <b>proximity view</b> — the chief-centered relative frame. Drag to orbit, scroll to zoom (1 m–100,000 km).</li>
                  <li><b>Single-click</b> a satellite = inspect (info panel + orbit path); <b>double-click</b> = camera focus.</li>
                  <li>Compose your own: click a catalog satellite → <b>Set as chief</b> / <b>Add as deputy</b> → Save.</li>
                  <li>Try a template in <b>Maneuvers</b> (the V-bar demo is a ready hold/glideslope start), sensors in <b>Sensors</b>, eclipse/conjunctions in <b>Environment</b>.</li>
                  <li><b>Export</b>: PNG snapshots, MP4 clips, events (JSON/CSV), and CCSDS OEM ephemerides.</li>
                </ol>
              </section>
              <section>
                <h3>Controls</h3>
                <ul>
                  <li><b>Globe</b> — drag rotate · scroll zoom · click inspect + path toggle · double-click focus · <i>⌂ Reset view</i>.</li>
                  <li><b>Proximity</b> — drag orbit · scroll zoom · camera modes (external / chief / deputy / sensor) · <i>Fit</i> re-frames.</li>
                  <li><b>Time</b> — play / pause / step / reverse · log rate slider · timeline scrub. Without a scenario the catalog is live; step/scrub to time-travel it.</li>
                  <li><b>Panels</b> — drag a header to move · ▾ to collapse · edges to resize. Positions persist.</li>
                  <li><b>Search</b> — name or NORAD id, Enter to fly to the match.</li>
                </ul>
              </section>
              <section>
                <h3>Glossary</h3>
                <dl>
                  {GLOSSARY.map(([term, def]) => (
                    <div key={term} className="help-term">
                      <dt>{term}</dt>
                      <dd>{def}</dd>
                    </div>
                  ))}
                </dl>
                <p className="help-more">Full glossary + user guide: <code>docs/user-guide.md</code> in the repository.</p>
              </section>
            </div>
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}
