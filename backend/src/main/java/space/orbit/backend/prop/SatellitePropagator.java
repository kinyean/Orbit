package space.orbit.backend.prop;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

/**
 * Thin SGP4 wrapper over Orekit's {@link TLEPropagator} (Decision 7, catalog
 * mode). Build one propagator per satellite (it is reusable for arbitrary
 * dates), then query positions per time step.
 *
 * <p>The public surface is deliberately small so the engine internals can be
 * swapped (e.g. the Phase 3 numerical propagator) without touching callers
 * (R1 mitigation).
 */
@Service
@DependsOn("orekitConfig")
public class SatellitePropagator {

    private final FrameService frames;

    public SatellitePropagator(FrameService frames) {
        this.frames = frames;
    }

    /** Build a reusable SGP4 (or SDP4) propagator for a TLE, in the TEME frame. */
    public TLEPropagator build(TLE tle) {
        return TLEPropagator.selectExtrapolator(tle, frames.teme());
    }

    /** ECEF position (metres) at {@code date} — what Cesium CZML "FIXED" consumes. */
    public Vector3D ecefPosition(TLEPropagator propagator, AbsoluteDate date) {
        return propagator.getPVCoordinates(date, frames.ecef()).getPosition();
    }

    /** Full ECI (EME2000) state — for relative-motion analysis in later phases. */
    public StateVector eciState(TLEPropagator propagator, AbsoluteDate date) {
        PVCoordinates pv = propagator.getPVCoordinates(date, frames.eci());
        return new StateVector(pv.getPosition(), pv.getVelocity(), date, frames.eci());
    }
}
