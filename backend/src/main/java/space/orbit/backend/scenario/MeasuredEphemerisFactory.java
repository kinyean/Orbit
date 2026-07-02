package space.orbit.backend.scenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.Frame;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.orbits.KeplerianOrbit;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.Ephemeris;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;
import space.orbit.backend.io.MeasuredEphemeris;

/**
 * Builds a tabulated Orekit {@link Ephemeris} from a measured dataset's position/velocity
 * samples (EME2000, SI). The single source of the interpolation-degree invariant (R19),
 * shared by the scenario stream (to <em>fly</em> a measured craft) and the maneuver
 * templates / rendezvous search (to <em>plan against</em> one as the chief).
 */
public final class MeasuredEphemerisFactory {

    private MeasuredEphemerisFactory() {}

    /**
     * Interpolation points for the tabulated measured ephemeris. Each sample carries
     * velocity, so TWO points give a cubic Hermite per segment — accurate for dense
     * (~5 min) LEO ephemeris and, crucially, STABLE. More points raise the polynomial
     * degree (4 pts ⇒ degree-7) which, over ~20° of arc per step, overshoots wildly
     * between nodes (Runge oscillation → positions blowing up to ~1e11 km even though
     * the nodes are exact). DO NOT raise (R19) — pinned by the stream's
     * {@code interpolatesStablyBetweenNodes} test.
     */
    public static final int INTERP_POINTS = 2;

    /** A built ephemeris plus the first sample's Keplerian orbit (for period / mean motion /
     *  inclination / eccentricity, which callers derive without re-walking the samples). */
    public record Built(Ephemeris ephemeris, KeplerianOrbit firstOrbit) {}

    /**
     * Build a tabulated ephemeris from ascending EME2000 pos/vel samples. The caller has
     * already validated {@code samples.size() >= 2}.
     */
    public static Built build(List<MeasuredEphemeris.Sample> samples, Frame eci, TimeScale utc) {
        double mu = Constants.WGS84_EARTH_MU;
        List<SpacecraftState> states = new ArrayList<>(samples.size());
        for (MeasuredEphemeris.Sample s : samples) {
            AbsoluteDate date = new AbsoluteDate(Instant.ofEpochMilli(s.epochMillis()), utc);
            PVCoordinates pv = new PVCoordinates(
                    new Vector3D(s.px(), s.py(), s.pz()), new Vector3D(s.vx(), s.vy(), s.vz()));
            states.add(new SpacecraftState(new CartesianOrbit(pv, eci, date, mu)));
        }
        Ephemeris ephemeris = new Ephemeris(states, Math.min(INTERP_POINTS, states.size()));
        return new Built(ephemeris, new KeplerianOrbit(states.get(0).getOrbit()));
    }
}
