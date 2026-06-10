package space.orbit.backend.prop;

/**
 * Propagation fidelity selector (SRS §3.1.8). The only place the loose
 * {@code ScenarioBody.fidelity} string becomes a type — the persisted body
 * stays a plain string (Decision 19); {@link PropagationService} parses it
 * here, so the persistence layer never depends on this enum.
 *
 * <ul>
 *   <li>{@link #SGP4} — analytic, from a TLE (Phase 2 / catalog).</li>
 *   <li>{@link #NUMERICAL} — high-fidelity DP8(7) + perturbations (Phase 3B).</li>
 *   <li>{@link #CW} — Clohessy–Wiltshire relative motion (Phase 5; not yet
 *       implemented).</li>
 * </ul>
 */
public enum Fidelity {
    SGP4,
    NUMERICAL,
    CW;

    /**
     * Parse a body's {@code fidelity} string, case-insensitively. Null, blank,
     * or unrecognised values fall back to {@link #SGP4} — the safe, cheapest
     * default (and what every Phase-2/3A scenario already carries).
     */
    public static Fidelity fromString(String value) {
        if (value == null || value.isBlank()) {
            return SGP4;
        }
        try {
            return Fidelity.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return SGP4;
        }
    }
}
