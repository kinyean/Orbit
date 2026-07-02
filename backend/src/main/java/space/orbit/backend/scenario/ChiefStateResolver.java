package space.orbit.backend.scenario;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.UUID;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.io.MeasuredEphemeris;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.PropagationService;

/**
 * Resolves a scenario chief role to an Orekit state provider regardless of how the chief is
 * backed — a frozen TLE (propagated at the scenario fidelity) OR a measured ephemeris
 * ({@code initialState.kind == "ephemeris"}, e.g. an imported TELEOS-2 dataset). This is
 * what lets the maneuver templates and the rendezvous search plan against a <em>measured</em>
 * chief: they only ever need the chief's state (a {@link Propagator}/{@code PVCoordinatesProvider})
 * and its mean motion (for the CW close-range templates), both of which an ephemeris supplies.
 *
 * <p>Deputies are <em>not</em> resolved here — a maneuvering deputy must be a TLE-backed role
 * (you don't maneuver the measured truth); the templates keep their own deputy TLE rebuild.
 */
@Service
@DependsOn("orekitConfig")
public class ChiefStateResolver {

    private final PropagationService propagationService;
    private final FrameService frames;
    private final MeasuredDatasetRepository measuredDatasets;

    private TimeScale utc;

    public ChiefStateResolver(PropagationService propagationService, FrameService frames,
                              MeasuredDatasetRepository measuredDatasets) {
        this.propagationService = propagationService;
        this.frames = frames;
        this.measuredDatasets = measuredDatasets;
    }

    @PostConstruct
    public void init() {
        utc = TimeScalesFactory.getUTC();
    }

    /** The chief's state provider + its mean motion (rad/s). {@code provider} is sampled for
     *  PV; {@code meanMotionRadPerSec} feeds the CW templates' {@code n}. */
    public record ChiefState(Propagator provider, double meanMotionRadPerSec) {}

    /** True when the role is backed by a measured ephemeris (vs a TLE). */
    public boolean isMeasured(ScenarioBody.Role role) {
        ScenarioBody.InitialState state = role == null ? null : role.initialState();
        return state != null && "ephemeris".equals(state.kind());
    }

    /**
     * Resolve a chief role. For a TLE chief the provider is propagated at {@code fidelity};
     * for a measured chief the ephemeris IS the truth, so {@code fidelity} is ignored.
     */
    public ChiefState resolve(ScenarioBody.Role chief, Fidelity fidelity) {
        if (isMeasured(chief)) {
            return resolveEphemeris(chief, chief.initialState().datasetId());
        }
        TLE tle = rebuildTle(chief);
        return new ChiefState(propagationService.propagatorFor(tle, fidelity), tle.getMeanMotion());
    }

    private ChiefState resolveEphemeris(ScenarioBody.Role chief, String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new ScenarioValidationException(
                    "measured chief " + chief.noradId() + " has no datasetId");
        }
        MeasuredDataset ds;
        try {
            ds = measuredDatasets.findById(UUID.fromString(datasetId))
                    .orElseThrow(() -> new ScenarioValidationException(
                            "measured dataset " + datasetId + " not found"));
        } catch (IllegalArgumentException badUuid) {
            throw new ScenarioValidationException("invalid datasetId \"" + datasetId + "\"");
        }
        List<MeasuredEphemeris.Sample> samples = MeasuredDatasetCodec.decode(ds.getSamples()).samples();
        if (samples.size() < 2) {
            throw new ScenarioValidationException(
                    "measured dataset " + datasetId + " has too few states for a maneuver plan");
        }
        MeasuredEphemerisFactory.Built built = MeasuredEphemerisFactory.build(samples, frames.eci(), utc);
        return new ChiefState(built.ephemeris(), built.firstOrbit().getKeplerianMeanMotion());
    }

    private TLE rebuildTle(ScenarioBody.Role role) {
        ScenarioBody.InitialState state = role == null ? null : role.initialState();
        if (state == null || state.tle() == null
                || state.tle().line1() == null || state.tle().line2() == null) {
            throw new ScenarioValidationException(
                    "Chief " + (role == null ? "?" : role.noradId()) + " has no TLE or measured ephemeris");
        }
        try {
            return new TLE(state.tle().line1(), state.tle().line2(), utc);
        } catch (RuntimeException e) {
            throw new ScenarioValidationException(
                    "Chief " + role.noradId() + " TLE failed to parse: " + e.getMessage());
        }
    }
}
