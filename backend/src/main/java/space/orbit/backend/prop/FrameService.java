package space.orbit.backend.prop;

import jakarta.annotation.PostConstruct;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
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
}
