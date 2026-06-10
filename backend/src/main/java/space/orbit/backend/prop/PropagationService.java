package space.orbit.backend.prop;

import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

/**
 * Fidelity dispatch (SRS §3.1.8, Decision 19): turns a scenario's
 * {@link Fidelity} into a concrete Orekit {@link Propagator}, and samples any
 * propagator to a uniform ECI {@link StateVector} regardless of engine.
 *
 * <p>This is the seam Phase 4 will call when it wires per-scenario streaming; in
 * Phase 3B only tests exercise it. Keeping dispatch here (not in the catalog or
 * scenario layers) preserves the thin-wrapper discipline (R1) — callers ask for
 * a fidelity, not for SGP4-vs-numerical internals.
 */
@Service
@DependsOn("orekitConfig")
public class PropagationService {

    private final SatellitePropagator satellitePropagator;
    private final NumericalPropagation numericalPropagation;
    private final FrameService frames;

    public PropagationService(SatellitePropagator satellitePropagator,
                              NumericalPropagation numericalPropagation,
                              FrameService frames) {
        this.satellitePropagator = satellitePropagator;
        this.numericalPropagation = numericalPropagation;
        this.frames = frames;
    }

    /**
     * Build a reusable propagator for a TLE at the requested fidelity.
     *
     * <ul>
     *   <li>{@link Fidelity#SGP4} — analytic SGP4/SDP4 over the TLE.</li>
     *   <li>{@link Fidelity#NUMERICAL} — seed the high-fidelity propagator from
     *       the TLE's ECI state at its own epoch, then integrate with the pinned
     *       {@link PropagationSettings#DEFAULT}.</li>
     *   <li>{@link Fidelity#CW} — Clohessy–Wiltshire; lands in Phase 5.</li>
     * </ul>
     */
    public Propagator propagatorFor(TLE tle, Fidelity fidelity) {
        return switch (fidelity) {
            case SGP4 -> satellitePropagator.build(tle);
            case NUMERICAL -> {
                TLEPropagator sgp4 = satellitePropagator.build(tle);
                StateVector seed = satellitePropagator.eciState(sgp4, tle.getDate());
                yield numericalPropagation.build(seed, PropagationSettings.DEFAULT);
            }
            case CW -> throw new UnsupportedOperationException("CW propagation lands in Phase 5");
        };
    }

    /**
     * Sample any propagator to an ECI (EME2000) {@link StateVector} at
     * {@code date} — uniform output shape for both engines, so downstream
     * (frames, streaming) never branches on fidelity.
     */
    public StateVector sample(Propagator propagator, AbsoluteDate date) {
        PVCoordinates pv = propagator.getPVCoordinates(date, frames.eci());
        return new StateVector(pv.getPosition(), pv.getVelocity(), date, frames.eci());
    }
}
