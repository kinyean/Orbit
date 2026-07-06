package space.orbit.backend.io;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.orekit.data.DataContext;
import org.orekit.errors.OrekitException;
import org.orekit.files.ccsds.definitions.BodyFacade;
import org.orekit.files.ccsds.definitions.CenterName;
import org.orekit.files.ccsds.definitions.FrameFacade;
import org.orekit.files.ccsds.definitions.OrekitCcsdsFrameMapper;
import org.orekit.files.ccsds.definitions.TimeSystem;
import org.orekit.files.ccsds.ndm.WriterBuilder;
import org.orekit.files.ccsds.ndm.odm.OdmHeader;
import org.orekit.files.ccsds.ndm.odm.oem.InterpolationMethod;
import org.orekit.files.ccsds.ndm.odm.oem.Oem;
import org.orekit.files.ccsds.ndm.odm.oem.OemData;
import org.orekit.files.ccsds.ndm.odm.oem.OemMetadata;
import org.orekit.files.ccsds.ndm.odm.oem.OemSegment;
import org.orekit.files.ccsds.ndm.odm.oem.OemWriter;
import org.orekit.files.ccsds.utils.generation.KvnGenerator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinatesProvider;
import org.orekit.utils.TimeStampedPVCoordinates;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.Impulse;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.scenario.MeasuredDataset;
import space.orbit.backend.scenario.MeasuredDatasetCodec;
import space.orbit.backend.scenario.MeasuredDatasetRepository;
import space.orbit.backend.scenario.MeasuredEphemerisFactory;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioService;
import space.orbit.backend.scenario.ScenarioValidationException;
import space.orbit.backend.stream.ScenarioStreamProperties;

/**
 * CCSDS OEM ephemeris export (Phase 11, US-IO-06 — SRS §4.2.1, UC-3 step 8 /
 * UC-8 step 3). Samples every scenario craft's <em>real</em> trajectory (the
 * same providers the stream flies: SGP4 / numerical-with-maneuvers / measured
 * tabulated ephemeris) on the scenario grid in EME2000/UTC and writes one OEM
 * message with a segment per craft, via Orekit's {@link OemWriter} (Decision 15
 * — never a hand-rolled CCSDS writer).
 *
 * <p>The {@code ScreeningService} pattern: resolve the body through the
 * owner-gated {@link ScenarioService} (non-owner → 404), rebuild providers from
 * the frozen initial states, sample — never touch the stream's encoded output.
 *
 * <p><b>Determinism (R11).</b> A fixed grid, deterministic propagation, and a
 * header creation date pinned to the latest <em>version</em> stamp (not the wall
 * clock) make the export byte-identical on rerun — proven by test.
 *
 * <p><b>Honesty at domain exits.</b> A craft that decays / leaves the propagator's
 * valid domain mid-window gets a <em>truncated</em> segment (with a COMMENT), never
 * fabricated HOLD states — this is an interchange file, not a rendering.
 */
@Service
@DependsOn("orekitConfig")
public class OemExportService {

    /** Interpolation guidance written into each segment (consumers of the file). */
    private static final int OEM_INTERPOLATION_DEGREE = 5;

    private final ScenarioService scenarioService;
    private final PropagationService propagationService;
    private final FrameService frames;
    private final MeasuredDatasetRepository measuredDatasets;
    private final ScenarioStreamProperties props;

    private TimeScale utc;

    public OemExportService(ScenarioService scenarioService, PropagationService propagationService,
                            FrameService frames, MeasuredDatasetRepository measuredDatasets,
                            ScenarioStreamProperties props) {
        this.scenarioService = scenarioService;
        this.propagationService = propagationService;
        this.frames = frames;
        this.measuredDatasets = measuredDatasets;
        this.props = props;
    }

    @PostConstruct
    void init() {
        utc = TimeScalesFactory.getUTC(); // safe: @DependsOn orekitConfig
    }

    /** A rendered OEM export: suggested filename + the KVN text. */
    public record OemExport(String fileName, String content) {}

    /** Export the scenario's propagated ephemerides as one CCSDS OEM message (KVN). */
    public OemExport export(UUID id) {
        ScenarioService.ExportView view = scenarioService.exportView(id);
        ScenarioBody body = view.body();
        Fidelity fidelity = Fidelity.fromString(body.fidelity());

        Instant start = parseInstant(body.timeRange().start());
        Instant end = parseInstant(body.timeRange().end());
        long durationSec = Math.max(1, end.getEpochSecond() - start.getEpochSecond());
        AbsoluteDate startDate = new AbsoluteDate(start, utc);
        // The stream's effective-step formula: honor the configured step, capped so no
        // craft exceeds maxSamplesPerSat samples over the window (R8).
        long step = Math.max(props.stepSeconds(),
                (long) Math.ceil(durationSec / (double) (props.maxSamplesPerSat() - 1)));
        int steps = (int) (durationSec / step);

        List<ScenarioBody.Role> roles = new ArrayList<>();
        roles.add(body.chief());
        roles.addAll(body.deputies());

        List<OemSegment> segments = new ArrayList<>(roles.size());
        int totalSamples = 0;
        for (ScenarioBody.Role role : roles) {
            OemSegment segment = buildSegment(role, fidelity, startDate, step, steps);
            totalSamples += segment.getData().getEphemeridesDataLines().size();
            segments.add(segment);
        }

        OdmHeader header = new OdmHeader();
        // Deterministic creation stamp: the scenario content's last-changed instant (R11).
        header.setCreationDate(new AbsoluteDate(view.versionCreatedAt().toInstant(), utc));
        header.setOriginator("ORBIT-PROJECT");
        header.addComment("Scenario \"" + view.name() + "\" (version " + view.versionNo() + ")");
        header.addComment("Fidelity: " + body.fidelity() + " · grid step " + step + " s");
        header.addComment("OBJECT_ID carries the (scenario) NORAD catalog number");

        Oem oem = new Oem(header, segments, IERSConventions.IERS_2010,
                DataContext.getDefault(), Constants.WGS84_EARTH_MU);
        String fileName = slugify(view.name()) + ".oem";

        StringBuilder out = new StringBuilder(64 * 1024);
        try {
            KvnGenerator generator = new KvnGenerator(out, OemWriter.KVN_PADDING_WIDTH, fileName,
                    Constants.JULIAN_DAY, 0);
            new WriterBuilder().buildOemWriter().writeMessage(generator, oem);
            generator.close();
        } catch (IOException e) {
            // A StringBuilder Appendable cannot actually fail — surface loudly if it does.
            throw new IllegalStateException("OEM serialization failed", e);
        }

        scenarioService.recordOemExport(id, "Exported CCSDS OEM (" + segments.size() + " craft, "
                + totalSamples + " states @ " + step + " s)");
        return new OemExport(fileName, out.toString());
    }

    /** One craft's segment: provider from its frozen initial state, sampled on the grid. */
    private OemSegment buildSegment(ScenarioBody.Role role, Fidelity fidelity,
                                    AbsoluteDate startDate, long step, int steps) {
        ScenarioBody.InitialState state = role.initialState();
        PVCoordinatesProvider provider;
        List<String> comments = new ArrayList<>();
        AbsoluteDate lo = startDate;
        AbsoluteDate hi = startDate.shiftedBy((double) (steps * step));

        if (state != null && "ephemeris".equals(state.kind())) {
            MeasuredEphemerisFactory.Built built = buildMeasured(role, state.datasetId());
            provider = built.ephemeris();
            // Intersect the grid with the measured span — never fabricate states
            // outside the data (this is truth, not a model).
            if (built.ephemeris().getMinDate().isAfter(lo)) {
                lo = built.ephemeris().getMinDate();
            }
            if (built.ephemeris().getMaxDate().isBefore(hi)) {
                hi = built.ephemeris().getMaxDate();
            }
            comments.add("Source: measured ephemeris (tabulated, dataset " + state.datasetId() + ")");
        } else {
            TLE tle = rebuildTle(role);
            // CW is a *relative* model with no authoritative absolute trajectory; export
            // the craft's absolute states on SGP4 (the ScreeningService precedent).
            Fidelity f = fidelity == Fidelity.CW ? Fidelity.SGP4 : fidelity;
            List<Impulse> impulses = toImpulses(role.maneuvers());
            provider = propagationService.propagatorFor(tle, f, impulses);
            if (fidelity == Fidelity.CW) {
                comments.add("Scenario fidelity cw (linearized relative model): absolute states are SGP4");
            }
            if (!impulses.isEmpty()) {
                comments.add("Includes " + impulses.size() + " maneuver(s); trajectory is the maneuvered numerical propagation");
            }
        }

        OemData data = new OemData();
        for (String c : comments) {
            data.addComment(c);
        }
        AbsoluteDate first = null;
        AbsoluteDate last = null;
        for (int k = 0; k <= steps; k++) {
            AbsoluteDate date = startDate.shiftedBy((double) (k * step));
            if (date.isBefore(lo) || date.isAfter(hi)) {
                continue;
            }
            TimeStampedPVCoordinates pv;
            try {
                pv = provider.getPVCoordinates(date, frames.eci());
            } catch (OrekitException leftDomain) {
                // Decay / below-surface: truncate honestly (bail — past a decay every
                // further step re-fails at full propagation cost; mirrors the stream).
                if (last != null) {
                    data.addComment("Truncated at " + last + ": propagation left the model's valid domain");
                }
                break;
            }
            data.addData(pv, false);
            if (first == null) {
                first = date;
            }
            last = date;
        }
        if (first == null || data.getEphemeridesDataLines().size() < 2) {
            throw new ScenarioValidationException("Role " + role.role() + " (" + role.noradId()
                    + ") yields no valid trajectory over the scenario window — cannot export OEM");
        }

        OemMetadata metadata = new OemMetadata(OEM_INTERPOLATION_DEGREE, new OrekitCcsdsFrameMapper());
        metadata.setObjectName(role.name());
        metadata.setObjectID(String.valueOf(role.noradId()));
        metadata.setCenter(BodyFacade.create(CenterName.EARTH));
        metadata.setReferenceFrame(FrameFacade.map(frames.eci()));
        metadata.setTimeSystem(TimeSystem.UTC);
        metadata.setStartTime(first);
        metadata.setStopTime(last);
        metadata.setInterpolationMethod(InterpolationMethod.LAGRANGE);
        metadata.setInterpolationDegree(OEM_INTERPOLATION_DEGREE);
        return new OemSegment(metadata, data, Constants.WGS84_EARTH_MU);
    }

    private MeasuredEphemerisFactory.Built buildMeasured(ScenarioBody.Role role, String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new ScenarioValidationException("ephemeris role " + role.noradId() + " has no datasetId");
        }
        MeasuredDataset ds;
        try {
            ds = measuredDatasets.findById(UUID.fromString(datasetId))
                    .orElseThrow(() -> new ScenarioValidationException("measured dataset " + datasetId + " not found"));
        } catch (IllegalArgumentException badUuid) {
            throw new ScenarioValidationException("invalid datasetId \"" + datasetId + "\"");
        }
        List<MeasuredEphemeris.Sample> samples = MeasuredDatasetCodec.decode(ds.getSamples()).samples();
        if (samples.size() < 2) {
            throw new ScenarioValidationException("measured dataset " + datasetId + " has too few states");
        }
        return MeasuredEphemerisFactory.build(samples, frames.eci(), utc);
    }

    private List<Impulse> toImpulses(List<ScenarioBody.Maneuver> maneuvers) {
        if (maneuvers == null || maneuvers.isEmpty()) {
            return List.of();
        }
        List<Impulse> impulses = new ArrayList<>(maneuvers.size());
        for (ScenarioBody.Maneuver m : maneuvers) {
            if (m.deltaV() == null || m.epoch() == null) {
                continue;
            }
            impulses.add(new Impulse(new AbsoluteDate(parseInstant(m.epoch()), utc),
                    m.deltaV().r(), m.deltaV().i(), m.deltaV().c(), m.thrustN(), m.ispSec()));
        }
        return impulses;
    }

    private TLE rebuildTle(ScenarioBody.Role role) {
        ScenarioBody.InitialState state = role.initialState();
        if (state == null || state.tle() == null || state.tle().line1() == null || state.tle().line2() == null) {
            throw new ScenarioValidationException(
                    "Role " + role.role() + " (" + role.noradId() + ") has no TLE initial state to export");
        }
        try {
            return new TLE(state.tle().line1(), state.tle().line2(), utc);
        } catch (RuntimeException e) {
            throw new ScenarioValidationException(
                    "Role " + role.role() + " (" + role.noradId() + ") TLE failed to parse: " + e.getMessage());
        }
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            throw new ScenarioValidationException("scenario time range is incomplete");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException e1) {
            try {
                return Instant.parse(value);
            } catch (DateTimeException e2) {
                throw new ScenarioValidationException("scenario time is not ISO-8601: " + value);
            }
        }
    }

    /** "Demo — close formation (NMC)" → "demo-close-formation-nmc" (filename-safe). */
    static String slugify(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return s.isEmpty() ? "scenario" : s;
    }
}
