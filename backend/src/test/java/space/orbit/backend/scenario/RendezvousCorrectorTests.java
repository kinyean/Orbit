package space.orbit.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.estimation.iod.IodLambert;
import org.orekit.frames.Frame;
import org.orekit.orbits.Orbit;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.KeplerianPropagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.AbsolutePVCoordinates;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.Impulse;
import space.orbit.backend.prop.NumericalPropagation;
import space.orbit.backend.prop.OrekitTestData;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.TleFactory;

/**
 * {@link RendezvousCorrector} (Phase 9A; the decisive R16 test). Real prop/frame stack
 * + the Orekit data bundle, no Spring/DB. Proves the differential corrector closes the
 * open-loop miss: against the SAME propagators the scenario flies (numerical deputy,
 * SGP4 chief), the raw two-body Lambert seed misses by kilometres while the corrected
 * transfer hits within a metre — and the corrected ΔV is byte-identical on rerun (R11).
 */
class RendezvousCorrectorTests {

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

    private static TLE leoTle(int norad, String name, double meanMotionRevPerDay, double meanAnomalyDeg) {
        TleFactory factory = new TleFactory();
        factory.init();
        // TLE epoch precedes the scenario window (as a frozen catalog snapshot does), so a
        // burn at the scenario start fires mid-propagation rather than at the seed instant.
        GpRecord r = new GpRecord(
                name, "1998-067A", "2024-06-01T11:00:00.000",
                meanMotionRevPerDay, 0.0006703, 51.6416, 247.4627, 130.5360, meanAnomalyDeg,
                norad, 999, 45000, 0.00010270, "U", 0);
        return factory.fromGp(r);
    }

    private static TLE leoTle(int norad, String name, double meanAnomalyDeg) {
        return leoTle(norad, name, 15.50125000, meanAnomalyDeg);
    }

    /** Two-body Lambert seed (mirrors {@link ManeuverTemplateService#rendezvous} cheapest-rev pick). */
    private static Impulse[] lambertSeed(PropagationService prop, FrameService frames,
                                         TLE depTle, TLE chiefTle, AbsoluteDate t1, AbsoluteDate t2) {
        Frame eci = frames.eci();
        PVCoordinates dep1 = prop.propagatorFor(depTle, Fidelity.SGP4).getPVCoordinates(t1, eci);
        PVCoordinates chief2 = prop.propagatorFor(chiefTle, Fidelity.SGP4).getPVCoordinates(t2, eci);
        double period = 2.0 * Math.PI / depTle.getMeanMotion();
        int maxRev = (int) Math.floor(t2.durationFrom(t1) / period);
        IodLambert lambert = new IodLambert(Constants.WGS84_EARTH_MU);
        Vector3D vDepart = null;
        Vector3D vArrive = null;
        double best = Double.POSITIVE_INFINITY;
        for (int nRev = 0; nRev <= maxRev; nRev++) {
            try {
                Orbit transfer = lambert.estimate(eci, true, nRev, dep1.getPosition(), t1, chief2.getPosition(), t2);
                if (transfer == null) {
                    continue;
                }
                Vector3D vd = transfer.getPVCoordinates().getVelocity();
                Vector3D va = new KeplerianPropagator(transfer).getPVCoordinates(t2, eci).getVelocity();
                double total = vd.subtract(dep1.getVelocity()).getNorm() + chief2.getVelocity().subtract(va).getNorm();
                if (total < best) {
                    best = total;
                    vDepart = vd;
                    vArrive = va;
                }
            } catch (RuntimeException ignore) {
                // skip infeasible revolution counts
            }
        }
        double[] ric1 = toRic(frames, vDepart.subtract(dep1.getVelocity()), dep1.getPosition(), dep1.getVelocity(), t1);
        double[] ric2 = toRic(frames, chief2.getVelocity().subtract(vArrive), chief2.getPosition(), vArrive, t2);
        return new Impulse[] {new Impulse(t1, ric1[0], ric1[1], ric1[2]), new Impulse(t2, ric2[0], ric2[1], ric2[2])};
    }

    private static double[] toRic(FrameService frames, Vector3D dvEci, Vector3D r, Vector3D v, AbsoluteDate date) {
        Frame eci = frames.eci();
        Frame ric = frames.ric(new AbsolutePVCoordinates(eci, date, new PVCoordinates(r, v)));
        Vector3D out = eci.getTransformTo(ric, date).transformVector(dvEci);
        return new double[] {out.getX(), out.getY(), out.getZ()};
    }

    /** Real-propagator miss: deputy (numerical, departure burn) vs chief (SGP4) at arrival. */
    private static double realMiss(PropagationService prop, FrameService frames,
                                   TLE depTle, TLE chiefTle, Impulse depart, AbsoluteDate t2) {
        Propagator dep = prop.propagatorFor(depTle, Fidelity.NUMERICAL, java.util.List.of(depart));
        Vector3D depArr = prop.sample(dep, t2).position();
        Vector3D chiefArr = prop.sample(prop.propagatorFor(chiefTle, Fidelity.SGP4), t2).position();
        return depArr.subtract(chiefArr).getNorm();
    }

    @Test
    void coOrbitalCaseConvergesAndClosesTheOpenLoopMiss() {
        FrameService frames = frames();
        PropagationService prop = prop(frames);
        // Chief ~0.5° (~60 km) ahead, ~0.4-orbit transfer (~144° angle, clear of the
        // 0°/180°/360° Lambert singularities). A small separation keeps the transfer gentle
        // and prograde-dominated (well-conditioned) rather than a radial "cut-across"; the
        // open-loop miss is still km-scale because it is dominated by J2 model-divergence
        // over the arc, not by the burn size — exactly the R16 effect.
        TLE chief = leoTle(25544, "CHIEF", 0.5);
        TLE deputy = leoTle(25545, "DEPUTY", 0.0);
        AbsoluteDate t1 = new AbsoluteDate("2024-06-01T12:00:00.000", TimeScalesFactory.getUTC());
        AbsoluteDate t2 = t1.shiftedBy(2230.0);               // ~0.4 chief orbit

        Impulse[] seed = lambertSeed(prop, frames, deputy, chief, t1, t2);

        // The open-loop Lambert seed, executed against the REAL propagators, misses.
        double rawMiss = realMiss(prop, frames, deputy, chief, seed[0], t2);

        RendezvousCorrector corrector = new RendezvousCorrector(prop, frames);
        RendezvousCorrector.Correction c = corrector.correct(
                deputy, prop.propagatorFor(chief, Fidelity.SGP4), t1, t2, seed[0], seed[1]);

        assertThat(c.converged()).as("corrector converges for a co-orbital catch-up").isTrue();
        assertThat(c.missM()).as("converged arrival miss").isLessThan(RendezvousCorrector.CONVERGE_MISS_M);

        // The decisive R16 assertion: corrected hits <1 m against the same real stack,
        // where the raw open-loop seed missed by far more.
        double correctedMiss = realMiss(prop, frames, deputy, chief, c.depart(), t2);
        assertThat(correctedMiss).as("corrected real-propagator miss").isLessThan(1.0);
        assertThat(rawMiss).as("raw open-loop miss is the R16 model error").isGreaterThan(50.0);
        assertThat(correctedMiss).as("corrected is far better than open-loop").isLessThan(rawMiss / 50.0);
    }

    @Test
    void correctionIsByteIdenticalOnRerun() {
        FrameService frames = frames();
        PropagationService prop = prop(frames);
        TLE chief = leoTle(25544, "CHIEF", 0.5);
        TLE deputy = leoTle(25545, "DEPUTY", 0.0);
        AbsoluteDate t1 = new AbsoluteDate("2024-06-01T12:00:00.000", TimeScalesFactory.getUTC());
        AbsoluteDate t2 = t1.shiftedBy(2230.0);
        Impulse[] seed = lambertSeed(prop, frames, deputy, chief, t1, t2);

        RendezvousCorrector corrector = new RendezvousCorrector(prop, frames);
        RendezvousCorrector.Correction a = corrector.correct(deputy, prop.propagatorFor(chief, Fidelity.SGP4), t1, t2, seed[0], seed[1]);
        RendezvousCorrector.Correction b = corrector.correct(deputy, prop.propagatorFor(chief, Fidelity.SGP4), t1, t2, seed[0], seed[1]);

        assertThat(Double.doubleToLongBits(a.depart().r())).isEqualTo(Double.doubleToLongBits(b.depart().r()));
        assertThat(Double.doubleToLongBits(a.depart().i())).isEqualTo(Double.doubleToLongBits(b.depart().i()));
        assertThat(Double.doubleToLongBits(a.depart().c())).isEqualTo(Double.doubleToLongBits(b.depart().c()));
        assertThat(Double.doubleToLongBits(a.arrive().r())).isEqualTo(Double.doubleToLongBits(b.arrive().r()));
        assertThat(Double.doubleToLongBits(a.arrive().i())).isEqualTo(Double.doubleToLongBits(b.arrive().i()));
        assertThat(Double.doubleToLongBits(a.arrive().c())).isEqualTo(Double.doubleToLongBits(b.arrive().c()));
    }

    @Test
    void divergentGeometryFallsBackToTheSeed() {
        FrameService frames = frames();
        PropagationService prop = prop(frames);
        TLE chief = leoTle(25544, "CHIEF", 180.0);   // half an orbit away
        TLE deputy = leoTle(25545, "DEPUTY", 0.0);
        AbsoluteDate t1 = new AbsoluteDate("2024-06-01T12:00:00.000", TimeScalesFactory.getUTC());
        AbsoluteDate t2 = t1.shiftedBy(120.0);        // absurdly short → huge / unconvergeable ΔV
        Impulse[] seed = lambertSeed(prop, frames, deputy, chief, t1, t2);

        RendezvousCorrector corrector = new RendezvousCorrector(prop, frames);
        RendezvousCorrector.Correction c = corrector.correct(deputy, prop.propagatorFor(chief, Fidelity.SGP4), t1, t2, seed[0], seed[1]);

        // On a pathological geometry it must not silently store a garbage burn: either it
        // converged within the ΔV cap, or it fell back to the seed with a note.
        if (!c.converged()) {
            assertThat(c.note()).isNotBlank();
            assertThat(c.depart().r()).isEqualTo(seed[0].r());
            assertThat(c.depart().i()).isEqualTo(seed[0].i());
            assertThat(c.depart().c()).isEqualTo(seed[0].c());
        }
    }

    /**
     * A ΔV-dominated (e.g. cross-plane) rendezvous is refused immediately, not ground through
     * the iteration budget. Regression for the observed 16 s hang on a cross-plane pair: the
     * seed ΔV (here 3.1 km/s) exceeds {@link RendezvousCorrector#MAX_SEED_DV_MS}, so the
     * corrector returns the open-loop seed with a clear note before running a single
     * (expensive) numerical propagation.
     */
    @Test
    void deltaVDominatedSeedIsRefusedImmediately() {
        FrameService frames = frames();
        PropagationService prop = prop(frames);
        TLE chief = leoTle(25544, "CHIEF", 0.0);
        TLE deputy = leoTle(25545, "DEPUTY", 0.0);
        AbsoluteDate t1 = new AbsoluteDate("2024-06-01T12:00:00.000", TimeScalesFactory.getUTC());
        AbsoluteDate t2 = t1.shiftedBy(3000.0);
        // 200 m/s in-track + 2900 m/s cross-track ≈ 3.1 km/s — a cross-plane plan, well past the cap.
        Impulse bigDepart = new Impulse(t1, 0.0, 200.0, 0.0);
        Impulse bigArrive = new Impulse(t2, 0.0, 0.0, 2900.0);

        RendezvousCorrector corrector = new RendezvousCorrector(prop, frames);
        long startNanos = System.nanoTime();
        RendezvousCorrector.Correction c =
                corrector.correct(deputy, prop.propagatorFor(chief, Fidelity.SGP4), t1, t2, bigDepart, bigArrive);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(c.converged()).as("can't converge a cross-plane plan").isFalse();
        assertThat(c.iterations()).as("bailed before the Newton loop").isZero();
        assertThat(c.note()).contains("ΔV-dominated");
        // The open-loop seed is returned unchanged (honest, just flagged).
        assertThat(c.depart().i()).isEqualTo(200.0);
        assertThat(c.arrive().c()).isEqualTo(2900.0);
        // And it did so without grinding through any numerical propagation (≪ the time fuse).
        assertThat(elapsedMs).as("refused without propagating").isLessThan(RendezvousCorrector.RUNTIME_CAP_MS);
    }
}
