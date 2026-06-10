package space.orbit.backend.prop;

import org.hipparchus.ode.nonstiff.DormandPrince853Integrator;
import org.orekit.bodies.CelestialBody;
import org.orekit.bodies.CelestialBodyFactory;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.forces.drag.DragForce;
import org.orekit.forces.drag.IsotropicDrag;
import org.orekit.forces.gravity.HolmesFeatherstoneAttractionModel;
import org.orekit.forces.gravity.ThirdBodyAttraction;
import org.orekit.forces.gravity.potential.GravityFieldFactory;
import org.orekit.forces.gravity.potential.NormalizedSphericalHarmonicsProvider;
import org.orekit.forces.radiation.IsotropicRadiationSingleCoefficient;
import org.orekit.forces.radiation.SolarRadiationPressure;
import org.orekit.frames.Frame;
import org.orekit.models.earth.atmosphere.NRLMSISE00;
import org.orekit.models.earth.atmosphere.data.CssiSpaceWeatherData;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.orbits.OrbitType;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.ToleranceProvider;
import org.orekit.propagation.numerical.NumericalPropagator;
import org.orekit.utils.PVCoordinates;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

/**
 * High-fidelity numerical propagator (SRS §3.1.2–6, US-PROP-02): DP8(7)
 * integrator over a full force model — non-spherical gravity (≥J4), NRLMSISE-00
 * drag, solar radiation pressure, and Sun + Moon third-body attraction.
 *
 * <p>Thin wrapper over Orekit's {@link NumericalPropagator} (R1): callers seed
 * it with an ECI {@link StateVector} and pinned {@link PropagationSettings},
 * and get back a reusable propagator. Sampling to {@link StateVector} is done by
 * {@link PropagationService#sample}, uniform with the SGP4 path.
 *
 * <p><b>Central attraction.</b> {@link HolmesFeatherstoneAttractionModel}
 * supplies only the <em>non-central</em> field; Orekit's
 * {@link NumericalPropagator#setInitialState} auto-adds the Newtonian central
 * term from {@code orbit.getMu()}. To keep the two consistent (and propagation
 * deterministic, R11) the seed orbit is built with the gravity field's own μ,
 * not WGS84 μ — so the monopole and the harmonics share one coefficient.
 */
@Service
@DependsOn("orekitConfig")
public class NumericalPropagation {

    private final FrameService frames;

    public NumericalPropagation(FrameService frames) {
        this.frames = frames;
    }

    /**
     * Build a reusable numerical propagator seeded from an ECI (EME2000) state.
     *
     * @param seed     initial state; position/velocity must be expressed in
     *                 {@link FrameService#eci()}
     * @param settings pinned, deterministic propagation settings
     */
    public NumericalPropagator build(StateVector seed, PropagationSettings settings) {
        if (seed.frame() != frames.eci()) {
            throw new IllegalArgumentException(
                    "Numerical propagator seed must be in ECI (EME2000); got " + seed.frame().getName());
        }

        // Gravity field carries its own μ; seed the orbit with the SAME μ so the
        // auto-added Newtonian central term agrees with the harmonic perturbations.
        NormalizedSphericalHarmonicsProvider gravity =
                GravityFieldFactory.getNormalizedProvider(settings.gravityDegree(), settings.gravityOrder());
        double mu = gravity.getMu();

        Frame eci = frames.eci();
        OneAxisEllipsoid earth = frames.earth();
        CelestialBody sun = CelestialBodyFactory.getSun();
        CelestialBody moon = CelestialBodyFactory.getMoon();

        PVCoordinates pv = new PVCoordinates(seed.position(), seed.velocity());
        CartesianOrbit orbit = new CartesianOrbit(pv, eci, seed.date(), mu);

        double[][] tol = ToleranceProvider.getDefaultToleranceProvider(settings.positionToleranceM())
                .getTolerances(orbit, OrbitType.CARTESIAN);
        DormandPrince853Integrator integrator =
                new DormandPrince853Integrator(settings.minStepS(), settings.maxStepS(), tol[0], tol[1]);

        NumericalPropagator propagator = new NumericalPropagator(integrator);
        propagator.setOrbitType(OrbitType.CARTESIAN);

        // Non-central gravity (the central monopole is the auto-added Newtonian term).
        propagator.addForceModel(new HolmesFeatherstoneAttractionModel(frames.ecef(), gravity));
        // Atmospheric drag — NRLMSISE-00 driven by CSSI space-weather indices.
        NRLMSISE00 atmosphere =
                new NRLMSISE00(new CssiSpaceWeatherData("SpaceWeather-All-v1.2.txt"), sun, earth);
        propagator.addForceModel(new DragForce(atmosphere, new IsotropicDrag(settings.areaM2(), settings.cd())));
        // Solar radiation pressure — earth is the occulting body for umbra/penumbra.
        propagator.addForceModel(new SolarRadiationPressure(sun, earth,
                new IsotropicRadiationSingleCoefficient(settings.areaM2(), settings.cr())));
        // Third-body gravity from Sun and Moon.
        propagator.addForceModel(new ThirdBodyAttraction(sun));
        propagator.addForceModel(new ThirdBodyAttraction(moon));

        // Sets the start date and (since no NewtonianAttraction is present yet)
        // adds the central attraction from orbit.getMu().
        propagator.setInitialState(new SpacecraftState(orbit).withMass(settings.massKg()));
        return propagator;
    }
}
