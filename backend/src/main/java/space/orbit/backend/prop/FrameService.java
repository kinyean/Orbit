package space.orbit.backend.prop;

import jakarta.annotation.PostConstruct;
import org.hipparchus.geometry.euclidean.threed.Rotation;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.LOFType;
import org.orekit.frames.LocalOrbitalFrame;
import org.orekit.frames.Transform;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.orekit.utils.PVCoordinatesProvider;
import org.orekit.utils.TimeStampedPVCoordinates;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

/**
 * Canonical frame utility (Decision 12). Every inter-frame conversion goes
 * through here, backed by Orekit's IERS-aware frames; the shared frame and
 * ellipsoid instances are built once and reused across all ~14.5k satellites.
 *
 * <ul>
 *   <li>{@link #eci()} — EME2000 (J2000 inertial).</li>
 *   <li>{@link #teme()} — the native frame of SGP4 output.</li>
 *   <li>{@link #ecef()} — ITRF (Earth-fixed); what Cesium CZML "FIXED" expects.</li>
 *   <li>{@link #lvlh(PVCoordinatesProvider)} / {@link #ric(PVCoordinatesProvider)}
 *       — chief-centered relative frames (Phase 3B, US-FRAME-02).</li>
 *   <li>{@link #body(Rotation, String)} — per-spacecraft body frame from an
 *       attitude rotation (minimal until sensors, Phase 7).</li>
 * </ul>
 */
@Service
@DependsOn("orekitConfig")
public class FrameService {

    private Frame eci;
    private Frame teme;
    private Frame ecef;
    private OneAxisEllipsoid earth;

    @PostConstruct
    public void init() {
        eci = FramesFactory.getEME2000();
        teme = FramesFactory.getTEME();
        // simpleEOP=true skips tidal EOP corrections — fine at visualization fidelity.
        ecef = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                ecef);
    }

    public Frame eci() {
        return eci;
    }

    public Frame teme() {
        return teme;
    }

    public Frame ecef() {
        return ecef;
    }

    /** Transform a position from {@code from} into ECEF at {@code date}. */
    public Vector3D toEcef(Vector3D position, Frame from, AbsoluteDate date) {
        if (from == ecef) {
            return position;
        }
        return from.getTransformTo(ecef, date).transformPosition(position);
    }

    /** Geodetic (lat/lon/alt) of a position expressed in {@code from} at {@code date}. */
    public GeodeticPoint toGeodetic(Vector3D position, Frame from, AbsoluteDate date) {
        return earth.transform(position, from, date);
    }

    /** ECEF position of a geodetic point (inverse of {@link #toGeodetic}). */
    public Vector3D geodeticToEcef(GeodeticPoint point) {
        return earth.transform(point);
    }

    /** The shared WGS84 Earth ellipsoid (reused by the numerical force models). */
    public OneAxisEllipsoid earth() {
        return earth;
    }

    // --- Relative & body frames (Phase 3B, US-FRAME-02, Decision 12) ---------
    //
    // Convention note (R15): this project's R/I/C is the glossary triad —
    // X = radial-out, ~Y = in-track (transverse), Z = cross-track (orbit
    // normal). In Orekit 13.1.5 LOFType.LVLH and LOFType.QSW are *equivalent*
    // (X aligned with position, Z aligned with orbital momentum), and both
    // match the glossary, so lvlh() and ric() return the same triad — exactly
    // as the glossary states ("RIC = same frame as LVLH"). This is NOT
    // LOFType.LVLH_CCSDS (Z toward nadir), which CCSDS/Wertz/FreeFlyer use;
    // picking that would silently flip the frame. The orientation test pins it.

    /**
     * Chief-centered LVLH frame (Decision 12). {@code chief} provides the
     * reference orbit motion (a propagator is a {@link PVCoordinatesProvider}).
     */
    public Frame lvlh(PVCoordinatesProvider chief) {
        if (chief == null) {
            throw new IllegalArgumentException("LVLH frame requires a chief reference (US-FRAME-02).");
        }
        return new LocalOrbitalFrame(eci, LOFType.LVLH, chief, "LVLH");
    }

    /**
     * Chief-centered RIC frame — the glossary's (radial, in-track, cross-track)
     * triad. Equivalent to {@link #lvlh} in Orekit 13.1.5 (see convention note).
     */
    public Frame ric(PVCoordinatesProvider chief) {
        if (chief == null) {
            throw new IllegalArgumentException("RIC frame requires a chief reference (US-FRAME-02).");
        }
        return new LocalOrbitalFrame(eci, LOFType.QSW, chief, "RIC");
    }

    /**
     * Express a deputy's ECI state in the chief's LVLH frame at the chief's
     * epoch (single-epoch convenience over {@link #lvlh}). Both states must be
     * in {@link #eci()} and at the same instant.
     *
     * @return the deputy state tagged with the chief-LVLH frame
     * @throws IllegalArgumentException if either state is null/not ECI
     *         (US-FRAME-02: a missing chief is a clear error, never a silent
     *         identity transform — R15)
     */
    public StateVector toRelativeState(StateVector deputyEci, StateVector chiefEci) {
        if (chiefEci == null) {
            throw new IllegalArgumentException("relative state requires a chief; none supplied (US-FRAME-02).");
        }
        if (deputyEci == null) {
            throw new IllegalArgumentException("relative state requires a deputy state.");
        }
        requireEci(deputyEci, "deputy");
        requireEci(chiefEci, "chief");

        AbsoluteDate date = chiefEci.date();
        Frame lvlh = lvlh(constantProvider(chiefEci));
        Transform toLvlh = eci.getTransformTo(lvlh, date);
        PVCoordinates rel = toLvlh.transformPVCoordinates(
                new PVCoordinates(deputyEci.position(), deputyEci.velocity()));
        return new StateVector(rel.getPosition(), rel.getVelocity(), date, lvlh);
    }

    /**
     * A per-spacecraft body frame defined by a fixed ECI→body attitude rotation
     * (US-FRAME-02). Minimal by design — attitude profiles (time-varying
     * rotations) arrive with sensors in Phase 7; for now the rotation is fixed.
     */
    public Frame body(Rotation eciToBody, String name) {
        if (eciToBody == null) {
            throw new IllegalArgumentException("body frame requires an attitude rotation.");
        }
        return new Frame(eci, new Transform(AbsoluteDate.J2000_EPOCH, eciToBody), name);
    }

    private void requireEci(StateVector state, String role) {
        if (state.frame() != eci) {
            throw new IllegalArgumentException(
                    "relative state expects the " + role + " in ECI (EME2000); got " + state.frame().getName());
        }
    }

    /**
     * Wrap a single ECI state as a {@link PVCoordinatesProvider}. Valid only at
     * the state's own epoch (no propagation) — used by {@link #toRelativeState}
     * to build the chief-LVLH transform at that one instant.
     */
    private PVCoordinatesProvider constantProvider(StateVector eciState) {
        TimeStampedPVCoordinates pv = new TimeStampedPVCoordinates(
                eciState.date(), eciState.position(), eciState.velocity());
        return (date, frame) -> eci.getTransformTo(frame, date).transformPVCoordinates(pv);
    }
}
