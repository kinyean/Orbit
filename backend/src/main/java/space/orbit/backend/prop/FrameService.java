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

    // --- modeled attitude (Phase 7, US-PROX-01 / US-SENSE-01, Decision 24) ----
    //
    // The proximity view needs each craft's BODY orientation expressed in the
    // chief-LVLH *scene* frame. We model it on the backend (authoritative — the
    // Phase-6 frontend "estimate" is retired) and stream a three.js-convention
    // quaternion. Body axis convention matches the proximity model + orientation.ts:
    //   +Y = nose (ram / velocity), +Z = top (radial-out), +X = +Y × +Z.
    // The basis→quaternion math is implemented explicitly here (NOT via Hipparchus
    // Rotation) so the convention is pinned to three.js and verified by a test (R15),
    // not left to a quaternion-handedness coincidence.

    /**
     * Body orientation quaternion {@code (x,y,z,w)} (three.js convention) of
     * {@code craft} expressed in the chief-{@code lvlh} scene frame at {@code date}.
     *
     * @param mode          {@code "lvlh"}/null → modeled LVLH-aligned attitude from
     *                      the craft's own ECI state; {@code "fixed"} → a constant
     *                      ECI body orientation given by {@code fixedQuatXyzw}
     *                      (body→ECI), re-expressed per-sample in the rotating LVLH.
     * @param fixedQuatXyzw the constant body→ECI quaternion for {@code "fixed"} mode
     *                      (ignored otherwise); may be null.
     */
    public double[] bodyQuaternionInLvlh(PVCoordinatesProvider craft, Frame lvlh,
                                         AbsoluteDate date, String mode, double[] fixedQuatXyzw) {
        PVCoordinates pv = craft.getPVCoordinates(date, eci);
        return bodyQuaternionInLvlh(pv, eci.getTransformTo(lvlh, date), mode, fixedQuatXyzw);
    }

    /**
     * Overload reusing an already-computed ECI state + ECI→LVLH transform (the
     * stream loop has both per sample, so this avoids re-propagating). {@code craftEci}
     * is the craft's state in {@link #eci()}; {@code eciToLvlh} is
     * {@code eci().getTransformTo(lvlh, date)}.
     */
    public double[] bodyQuaternionInLvlh(PVCoordinates craftEci, Transform eciToLvlh,
                                         String mode, double[] fixedQuatXyzw) {
        Vector3D noseEci;
        Vector3D topEci;
        if ("fixed".equalsIgnoreCase(mode) && fixedQuatXyzw != null && fixedQuatXyzw.length == 4) {
            noseEci = rotateByQuat(fixedQuatXyzw, Vector3D.PLUS_J); // body +Y in ECI
            topEci = rotateByQuat(fixedQuatXyzw, Vector3D.PLUS_K);  // body +Z in ECI
        } else {
            noseEci = craftEci.getVelocity();   // ram
            topEci = craftEci.getPosition();    // radial-out
        }
        // Rotate the body axes from ECI into the LVLH scene frame (direction only —
        // transformVector applies the rotation, not the translation/rate).
        return basisQuaternion(eciToLvlh.transformVector(noseEci), eciToLvlh.transformVector(topEci));
    }

    /**
     * Quaternion {@code (x,y,z,w)} of the right-handed body basis with +Y along
     * {@code noseDir} and +Z along {@code topDir} (orthonormalized), built so the
     * matrix columns are {@code [+X, +Y, +Z] = [nose×top, nose, top]} — matching
     * the frontend's {@code makeBasis(side, nose, top)} (orientation.ts). The
     * matrix→quaternion conversion is the standard (three.js) trace formula.
     */
    static double[] basisQuaternion(Vector3D noseDir, Vector3D topDir) {
        Vector3D y = noseDir.normalize();
        Vector3D z = topDir.subtract(y.scalarMultiply(Vector3D.dotProduct(topDir, y)));
        if (z.getNormSq() < 1e-12) {
            // nose ~parallel to radial → fall back to cross-track as "up".
            z = Vector3D.PLUS_K;
            z = z.subtract(y.scalarMultiply(Vector3D.dotProduct(z, y)));
        }
        z = z.normalize();
        Vector3D x = Vector3D.crossProduct(y, z); // +X = +Y × +Z (right-handed)
        double m00 = x.getX(), m01 = y.getX(), m02 = z.getX();
        double m10 = x.getY(), m11 = y.getY(), m12 = z.getY();
        double m20 = x.getZ(), m21 = y.getZ(), m22 = z.getZ();
        double trace = m00 + m11 + m22;
        double qx, qy, qz, qw;
        if (trace > 0) {
            double s = 0.5 / Math.sqrt(trace + 1.0);
            qw = 0.25 / s;
            qx = (m21 - m12) * s;
            qy = (m02 - m20) * s;
            qz = (m10 - m01) * s;
        } else if (m00 > m11 && m00 > m22) {
            double s = 2.0 * Math.sqrt(1.0 + m00 - m11 - m22);
            qw = (m21 - m12) / s;
            qx = 0.25 * s;
            qy = (m01 + m10) / s;
            qz = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = 2.0 * Math.sqrt(1.0 + m11 - m00 - m22);
            qw = (m02 - m20) / s;
            qx = (m01 + m10) / s;
            qy = 0.25 * s;
            qz = (m12 + m21) / s;
        } else {
            double s = 2.0 * Math.sqrt(1.0 + m22 - m00 - m11);
            qw = (m10 - m01) / s;
            qx = (m02 + m20) / s;
            qy = (m12 + m21) / s;
            qz = 0.25 * s;
        }
        return new double[] {qx, qy, qz, qw};
    }

    /** Rotate {@code v} by a {@code (x,y,z,w)} quaternion (Hamilton / three.js convention). */
    private static Vector3D rotateByQuat(double[] q, Vector3D v) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double vx = v.getX(), vy = v.getY(), vz = v.getZ();
        // t = 2 * cross(q.xyz, v); v' = v + w*t + cross(q.xyz, t)
        double tx = 2.0 * (y * vz - z * vy);
        double ty = 2.0 * (z * vx - x * vz);
        double tz = 2.0 * (x * vy - y * vx);
        double rx = vx + w * tx + (y * tz - z * ty);
        double ry = vy + w * ty + (z * tx - x * tz);
        double rz = vz + w * tz + (x * ty - y * tx);
        return new Vector3D(rx, ry, rz);
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
