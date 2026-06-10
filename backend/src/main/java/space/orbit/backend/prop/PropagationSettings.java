package space.orbit.backend.prop;

/**
 * Pinned propagation settings for the numerical propagator (Phase 3B).
 *
 * <p>These are <strong>constants in code</strong>, never read from the process
 * environment, so propagation is deterministic by construction (SRS §5.4.1,
 * R11). They describe a generic representative LEO spacecraft; the values are
 * not yet user-tunable.
 *
 * <p>When Phase 4 wires per-scenario propagation, these move into the scenario
 * body so each scenario pins (and reproduces) its own settings — at which point
 * R11's "settings live in the body" guidance applies. Until then a single
 * {@link #DEFAULT} keeps every numerical run identical.
 *
 * <p>Units: mass kg, area m², gravity degree/order dimensionless, integrator
 * tolerance/steps in metres / seconds.
 */
public record PropagationSettings(
        double massKg,
        double areaM2,
        double cd,
        double cr,
        int gravityDegree,
        int gravityOrder,
        double positionToleranceM,
        double minStepS,
        double maxStepS) {

    /**
     * The pinned defaults (standard flight-dynamics values for a generic LEO
     * spacecraft):
     * <ul>
     *   <li>500 kg, 1 m² cross-section;</li>
     *   <li>drag C<sub>d</sub> = 2.2, reflectivity C<sub>r</sub> = 1.8;</li>
     *   <li>gravity field 16×16 (well past the ≥J4 floor of US-PROP-02);</li>
     *   <li>DP8(7) position tolerance 1e-3 m, step bounds 1e-3 s … 300 s.</li>
     * </ul>
     */
    public static final PropagationSettings DEFAULT = new PropagationSettings(
            500.0,   // massKg
            1.0,     // areaM2
            2.2,     // cd
            1.8,     // cr
            16,      // gravityDegree
            16,      // gravityOrder
            1.0e-3,  // positionToleranceM
            1.0e-3,  // minStepS
            300.0);  // maxStepS
}
