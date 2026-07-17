package space.orbit.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.frames.Frame;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;
import org.orekit.utils.PVCoordinatesProvider;
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.Impulse;
import space.orbit.backend.prop.NumericalPropagation;
import space.orbit.backend.prop.OrekitTestData;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.StateVector;
import space.orbit.backend.prop.TleFactory;

/**
 * {@link CollisionAvoidancePlanner} (US-MAN-12; the inverse of {@link RendezvousCorrector}). Real
 * prop/frame stack + the Orekit data bundle, no Spring/DB.
 *
 * <p>The threat is a constructed fast crossing — seeded at the TCA a small radial offset from the
 * deputy with a large in-track relative velocity — i.e. the realistic conjunction a CAM defends
 * against (a brief, sharp close approach). The planner must raise that miss to the target with a
 * bounded ΔV, and a cross-track (out-of-plane) burn must do it without changing the orbit's energy
 * (altitude) — the user's "not too high or low".
 */
class CollisionAvoidancePlannerTests {

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
    }

    private static FrameService frames() {
        FrameService f = new FrameService();
        f.init();
        return f;
    }

    private static PropagationService prop(FrameService frames) {
        return new PropagationService(new SatellitePropagator(frames), new NumericalPropagation(frames), frames);
    }

    private static TLE deputyTle() {
        TleFactory factory = new TleFactory();
        factory.init();
        GpRecord r = new GpRecord(
                "DEPUTY", "1998-067A", "2024-06-01T11:00:00.000",
                15.50125000, 0.0006703, 51.6416, 247.4627, 130.5360, 0.0,
                25545, 999, 45000, 0.00010270, "U", 0);
        return factory.fromGp(r);
    }

    private static final AbsoluteDate START =
            new AbsoluteDate("2024-06-01T12:00:00.000", TimeScalesFactory.getUTC());
    private static final double WINDOW_SEC = 3600.0;
    private static final AbsoluteDate TCA = START.shiftedBy(1800.0);

    /** A fast crossing that passes {@code missM} (radial offset) from the deputy at the TCA, with
     *  {@code relSpeedMps} of in-track relative velocity (a brief, sharp conjunction). Seeded AT the
     *  TCA so the deputy-vs-threat separation there is exactly {@code missM}. */
    private static PVCoordinatesProvider crossingThreat(PropagationService prop, Frame eci,
                                                        TLE deputy, double missM, double relSpeedMps) {
        Propagator dep = prop.propagatorFor(deputy, Fidelity.NUMERICAL, List.of());
        PVCoordinates atTca = dep.getPVCoordinates(TCA, eci);
        Vector3D rHat = atTca.getPosition().normalize();  // radial (⊥ the in-track relative velocity)
        Vector3D vHat = atTca.getVelocity().normalize();  // ≈ in-track
        Vector3D pos = atTca.getPosition().add(rHat.scalarMultiply(missM));
        // ADD in-track speed (raises apogee, keeps perigee at the crossing point = valid orbit);
        // subtracting would drop the perigee below the surface. Gives a sharp in-track crossing.
        Vector3D vel = atTca.getVelocity().add(vHat.scalarMultiply(relSpeedMps));
        return prop.propagatorFor(new StateVector(pos, vel, TCA, eci), List.of());
    }

    @Test
    void crossTrackBurnRaisesTheMissToTheTarget() {
        FrameService frames = frames();
        PropagationService prop = prop(frames);
        TLE deputy = deputyTle();
        PVCoordinatesProvider threat = crossingThreat(prop, frames.eci(), deputy, 500.0, 1500.0);
        double target = 5000.0;

        CollisionAvoidancePlanner planner = new CollisionAvoidancePlanner(prop, frames);
        CollisionAvoidancePlanner.CamPlan plan = planner.plan(
                deputy, List.of(), threat, deputy.getMeanMotion(),
                TCA, START, START.shiftedBy(WINDOW_SEC),
                CollisionAvoidancePlanner.Axis.CROSSTRACK, target);

        assertThat(plan.baselineMissM()).as("the constructed close pass").isCloseTo(500.0, org.assertj.core.data.Offset.offset(50.0));
        assertThat(plan.converged()).as("cross-track reaches the target for a fast crossing").isTrue();
        assertThat(plan.achievedMissM()).isGreaterThanOrEqualTo(target * 0.95);
        assertThat(plan.dvMagnitudeMps()).isBetween(0.0, CollisionAvoidancePlanner.MAX_DV_MS);
        // Cross-track = the C axis only.
        assertThat(Math.abs(plan.burn().r())).isLessThan(1.0e-9);
        assertThat(Math.abs(plan.burn().i())).isLessThan(1.0e-9);
        assertThat(Math.abs(plan.burn().c())).isGreaterThan(0.0);
    }

    @Test
    void planIsByteIdenticalOnRerun() {
        FrameService frames = frames();
        PropagationService prop = prop(frames);
        TLE deputy = deputyTle();
        double target = 5000.0;

        CollisionAvoidancePlanner planner = new CollisionAvoidancePlanner(prop, frames);
        CollisionAvoidancePlanner.CamPlan a = planner.plan(deputy, List.of(),
                crossingThreat(prop, frames.eci(), deputy, 500.0, 1500.0), deputy.getMeanMotion(),
                TCA, START, START.shiftedBy(WINDOW_SEC), CollisionAvoidancePlanner.Axis.CROSSTRACK, target);
        CollisionAvoidancePlanner.CamPlan b = planner.plan(deputy, List.of(),
                crossingThreat(prop, frames.eci(), deputy, 500.0, 1500.0), deputy.getMeanMotion(),
                TCA, START, START.shiftedBy(WINDOW_SEC), CollisionAvoidancePlanner.Axis.CROSSTRACK, target);

        assertThat(Double.doubleToLongBits(a.burn().c())).isEqualTo(Double.doubleToLongBits(b.burn().c()));
        assertThat(Double.doubleToLongBits(a.achievedMissM())).isEqualTo(Double.doubleToLongBits(b.achievedMissM()));
    }

    @Test
    void unreachableTargetFallsBackWithANote() {
        FrameService frames = frames();
        PropagationService prop = prop(frames);
        TLE deputy = deputyTle();
        // 5000 km with a small burn is not reachable → best-effort, capped, with a note.
        double target = 5_000_000.0;

        CollisionAvoidancePlanner planner = new CollisionAvoidancePlanner(prop, frames);
        CollisionAvoidancePlanner.CamPlan plan = planner.plan(deputy, List.of(),
                crossingThreat(prop, frames.eci(), deputy, 500.0, 1500.0), deputy.getMeanMotion(),
                TCA, START, START.shiftedBy(WINDOW_SEC), CollisionAvoidancePlanner.Axis.CROSSTRACK, target);

        assertThat(plan.converged()).isFalse();
        assertThat(plan.note()).isNotBlank();
        assertThat(plan.dvMagnitudeMps()).isLessThanOrEqualTo(CollisionAvoidancePlanner.MAX_DV_MS);
    }

    /**
     * The physical crux of "not too high or low": a cross-track (out-of-plane) burn leaves the
     * semi-major axis (orbital energy → altitude) essentially unchanged, while an in-track burn of
     * the SAME magnitude changes it by kilometres.
     */
    @Test
    void crossTrackKeepsSemiMajorAxisWhileInTrackChangesIt() {
        FrameService frames = frames();
        PropagationService prop = prop(frames);
        Frame eci = frames.eci();
        TLE deputy = deputyTle();
        double mu = Constants.WGS84_EARTH_MU;
        AbsoluteDate after = START.shiftedBy(1800.0);
        double dv = 5.0; // m/s

        double a0 = smaAt(prop, eci, deputy, List.of(), after, mu);
        double aCross = smaAt(prop, eci, deputy, List.of(new Impulse(START, 0.0, 0.0, dv)), after, mu);
        double aTrack = smaAt(prop, eci, deputy, List.of(new Impulse(START, 0.0, dv, 0.0)), after, mu);

        double crossChange = Math.abs(aCross - a0);
        double trackChange = Math.abs(aTrack - a0);
        assertThat(crossChange).as("cross-track leaves the altitude ~unchanged").isLessThan(100.0);
        assertThat(trackChange).as("in-track of the same ΔV shifts the orbit kilometres").isGreaterThan(2_000.0);
        assertThat(crossChange).as("cross-track is far more altitude-neutral").isLessThan(trackChange / 20.0);
    }

    /** Osculating semi-major axis (vis-viva) of the deputy with the given burns, sampled at {@code at}. */
    private static double smaAt(PropagationService prop, Frame eci, TLE deputy,
                                List<Impulse> impulses, AbsoluteDate at, double mu) {
        Propagator dep = prop.propagatorFor(deputy, Fidelity.NUMERICAL, impulses);
        PVCoordinates pv = dep.getPVCoordinates(at, eci);
        double r = pv.getPosition().getNorm();
        double v = pv.getVelocity().getNorm();
        return 1.0 / (2.0 / r - v * v / mu);
    }
}
