package space.orbit.backend.prop;

import java.util.Comparator;
import java.util.List;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.attitudes.LofOffset;
import org.orekit.forces.maneuvers.ConstantThrustManeuver;
import org.orekit.forces.maneuvers.Control3DVectorCostType;
import org.orekit.forces.maneuvers.ImpulseManeuver;
import org.orekit.forces.maneuvers.ImpulseProvider;
import org.orekit.frames.LOFType;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.events.DateDetector;
import org.orekit.propagation.numerical.NumericalPropagator;
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

    /** Deterministic order for same-instant impulses (R11): epoch, then ΔV components. */
    private static final Comparator<Impulse> IMPULSE_ORDER =
            Comparator.comparing(Impulse::epoch)
                    .thenComparingDouble(Impulse::r)
                    .thenComparingDouble(Impulse::i)
                    .thenComparingDouble(Impulse::c);

    /**
     * Build a propagator for a maneuvered role (Phase 5B, US-MAN-01). With no
     * impulses this is exactly {@link #propagatorFor(TLE, Fidelity)}.
     *
     * <p><b>A maneuvered role is always numerical.</b> An impulse is applied by an
     * {@link ImpulseManeuver} event that resets the propagator's state at the burn
     * epoch — which the analytic {@link TLEPropagator} cannot do (its state can't
     * be reset, and SGP4 can't re-seed from an arbitrary osculating state). So we
     * seed the numerical engine from the TLE start state regardless of the
     * requested {@code fidelity}; {@code SGP4} silently upgrades for that role.
     *
     * <p>Each impulse fires at a fixed {@link DateDetector} (pinned maxCheck +
     * threshold) with its ΔV expressed in a QSW/RIC-aligned {@link LofOffset}
     * attitude, {@link Control3DVectorCostType#NONE} (no mass bookkeeping). Impulses
     * are attached in a sorted order so same-instant burns compose deterministically.
     *
     * <p><b>Finite burns (Phase 9, US-MAN-11).</b> An {@link Impulse#finite()} burn is
     * realised as an Orekit {@link ConstantThrustManeuver} force model instead — a real
     * thrust integrated over a window, with mass depleted via the rocket equation. See
     * {@link #buildManeuvered}.
     */
    public Propagator propagatorFor(TLE tle, Fidelity fidelity, List<Impulse> impulses) {
        if (impulses == null || impulses.isEmpty()) {
            return propagatorFor(tle, fidelity);
        }
        TLEPropagator sgp4 = satellitePropagator.build(tle);
        StateVector seed = satellitePropagator.eciState(sgp4, tle.getDate());
        return buildManeuvered(seed, impulses);
    }

    /**
     * Build a maneuvered numerical propagator directly from an ECI seed state (Phase 9C):
     * the seam Monte Carlo dispersion uses to perturb the deputy's initial state. Same
     * engine + impulse attachment as the TLE path — always numerical.
     */
    public Propagator propagatorFor(StateVector seed, List<Impulse> impulses) {
        return buildManeuvered(seed, impulses == null ? List.of() : impulses);
    }

    /** Standard gravity (m/s²) for the rocket equation; effective exhaust velocity = Isp·g0. */
    private static final double G0 = 9.80665;

    /**
     * Numerical propagator from a seed + sorted RIC impulses (the shared core).
     *
     * <p>An impulsive burn is an {@link ImpulseManeuver} event detector (instant ΔV reset,
     * no mass change); a {@link Impulse#finite() finite} burn is a
     * {@link ConstantThrustManeuver} force model — a real thrust integrated over the
     * {@link #finiteDuration duration} that achieves the requested ΔV (Tsiolkovsky, using
     * the pinned initial mass), centred on the maneuver epoch so it collapses to the
     * impulsive case as thrust → ∞. Both share the QSW/RIC {@link LofOffset} so the ΔV /
     * thrust direction is interpreted in RIC. Attached in {@link #IMPULSE_ORDER} so same-
     * instant burns compose deterministically (R11).
     */
    private NumericalPropagator buildManeuvered(StateVector seed, List<Impulse> impulses) {
        NumericalPropagator propagator = numericalPropagation.build(seed, PropagationSettings.DEFAULT);
        LofOffset ricAttitude = new LofOffset(frames.eci(), LOFType.QSW); // satellite frame = RIC
        double massKg = PropagationSettings.DEFAULT.massKg();
        impulses.stream().sorted(IMPULSE_ORDER).forEach(imp -> {
            Vector3D deltaVRic = new Vector3D(imp.r(), imp.i(), imp.c());
            if (imp.finite()) {
                double dvMag = deltaVRic.getNorm();
                double duration = finiteDuration(dvMag, imp.thrustN(), imp.ispSec(), massKg);
                // Centre the burn on the epoch so the finite burn's effective ΔV lands at
                // the same instant the impulsive case would (and matches the CW midpoint).
                AbsoluteDate start = imp.epoch().shiftedBy(-duration / 2.0);
                Vector3D dir = dvMag > 0 ? deltaVRic.normalize() : Vector3D.PLUS_I;
                propagator.addForceModel(new ConstantThrustManeuver(
                        start, duration, imp.thrustN(), imp.ispSec(), ricAttitude, dir));
            } else {
                DateDetector trigger = new DateDetector(imp.epoch())
                        .withMaxCheck(60.0)
                        .withThreshold(1.0e-6);
                propagator.addEventDetector(new ImpulseManeuver(
                        trigger, ricAttitude, ImpulseProvider.of(deltaVRic),
                        Double.POSITIVE_INFINITY, Control3DVectorCostType.NONE));
            }
        });
        return propagator;
    }

    /**
     * Burn duration (s) that achieves a {@code dvMag} (m/s) ΔV with a constant
     * {@code thrustN} (N) / {@code ispSec} (s) engine from an initial {@code massKg}
     * (kg), via the Tsiolkovsky rocket equation:
     * {@code Δm = m0·(1−e^{−Δv/ve})}, {@code ṁ = F/ve}, {@code t = Δm/ṁ} with
     * {@code ve = Isp·g0}. Independent of thrust magnitude up to the achieved-ΔV
     * level (thrust only sets how long it takes). Uses the pinned initial mass for
     * every burn; sequential mass depletion across multiple finite burns is a
     * second-order approximation (acceptable for ΔV that is small vs the wet mass).
     */
    static double finiteDuration(double dvMag, double thrustN, double ispSec, double massKg) {
        double ve = ispSec * G0;
        double deltaM = massKg * (1.0 - Math.exp(-dvMag / ve));
        double mdot = thrustN / ve;
        return deltaM / mdot;
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
