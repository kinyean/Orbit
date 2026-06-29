package space.orbit.backend.scenario;

/**
 * Web-agnostic input to {@link ScenarioService#addManeuver} (Phase 5B, US-MAN-01).
 * The controller maps a validated request DTO into this. A ΔV applied to one deputy
 * at one epoch; {@code frame} is {@code "ric"} (body-frame ΔV arrives with attitude in
 * Phase 7). Components are metres/second.
 *
 * <p><b>Finite burns (Phase 9, US-MAN-11).</b> Optional {@code thrustN} (N) +
 * {@code ispSec} (s): when both are present + positive the ΔV is flown as a finite
 * constant-thrust burn rather than an instant impulse. Null → impulsive (Phase-5B).
 */
public record ManeuverDraft(
        int deputyNoradId,
        String epoch,
        String frame,
        double r,
        double i,
        double c,
        Double thrustN,
        Double ispSec) {

    /** Impulsive convenience (no finite-burn parameters) — keeps Phase-5B/5C call sites. */
    public ManeuverDraft(int deputyNoradId, String epoch, String frame, double r, double i, double c) {
        this(deputyNoradId, epoch, frame, r, i, c, null, null);
    }

    /** True when both finite-burn parameters are present + positive. */
    public boolean finite() {
        return thrustN != null && ispSec != null && thrustN > 0.0 && ispSec > 0.0;
    }
}
