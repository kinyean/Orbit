package space.orbit.backend.scenario;

import java.util.List;
import java.util.UUID;
import org.orekit.propagation.analytical.tle.TLE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.prop.TleFactory;
import space.orbit.backend.security.DevUserAuthenticationFilter;

/**
 * Seeds a demo <strong>close-formation</strong> scenario at startup (idempotent)
 * so the proximity view shows a recognizable relative-motion ellipse out of the
 * box. Real catalog satellites are thousands of km apart — useless for proximity
 * ops — so this uses two <em>synthetic</em> spacecraft on a bounded Natural
 * Motion Circumnavigation (NMC): identical mean motion (equal SMA ⇒ no along-track
 * drift) with small differences in eccentricity / inclination, giving a few-km
 * relative ellipse around the chief.
 *
 * <p>The elements are exposed as static {@link #chiefRecord()}/{@link #deputyRecord()}
 * so a test can validate the formation against the same definition.
 */
@Component
@DependsOn("orekitConfig")
public class SampleScenarioSeeder {

    private static final Logger log = LoggerFactory.getLogger(SampleScenarioSeeder.class);

    private static final UUID DEV_USER = UUID.fromString(DevUserAuthenticationFilter.DEV_USER_ID);
    private static final String NAME = "Demo — close formation (NMC)";
    private static final String EPOCH = "2026-06-01T00:00:00.000";
    private static final String START = "2026-06-01T00:00:00Z";
    private static final String END = "2026-06-01T03:00:00Z"; // ~2 orbits

    private final TleFactory tleFactory;
    private final ScenarioService scenarioService;

    public SampleScenarioSeeder(TleFactory tleFactory, ScenarioService scenarioService) {
        this.tleFactory = tleFactory;
        this.scenarioService = scenarioService;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        try {
            ScenarioBody body = new ScenarioBody(1, "sgp4",
                    new ScenarioBody.TimeRange(START, END),
                    role("chief", chiefRecord()),
                    List.of(role("deputy", deputyRecord())));
            scenarioService.seedIfAbsent(DEV_USER, NAME, body);
            log.info("Sample scenario ensured: \"{}\"", NAME);
        } catch (RuntimeException e) {
            // Best-effort — never block startup on the demo seed.
            log.warn("Sample scenario seed skipped: {}", e.toString());
        }
    }

    private ScenarioBody.Role role(String roleName, GpRecord gp) {
        TLE tle = tleFactory.fromGp(gp);
        return new ScenarioBody.Role(roleName, gp.noradId(), gp.objectName(),
                new ScenarioBody.InitialState("tle",
                        new ScenarioBody.Tle(tle.getLine1(), tle.getLine2(), tle.getDate().toString())));
    }

    /** Chief: near-circular ~518 km LEO. */
    public static GpRecord chiefRecord() {
        // name, objectId, epoch, meanMotion(rev/day), ecc, inc, raan, argP, M,
        // noradId, elementSetNo, revAtEpoch, bstar, classification, ephemerisType
        // 5-digit NORAD id (the legacy TLE line format caps at 99999); high range
        // to avoid colliding with the real active catalog.
        return new GpRecord("DEMO CHIEF", "2026-001A", EPOCH,
                15.20, 0.00001, 51.600, 0.0, 0.0, 0.0,
                99001, 999, 1, 0.0, "U", 0);
    }

    /**
     * Deputy: <strong>same mean motion</strong> as the chief (equal SMA ⇒ bounded,
     * no along-track drift), a slightly larger eccentricity rotated 90° in argument
     * of perigee (→ ~2 km in-plane "football"), a 0.01° inclination offset (→ ~1 km
     * cross-track), and mean anomaly set so {@code argP + M} matches the chief's
     * argument of latitude (→ the ellipse is centered on the chief, not phased away).
     */
    public static GpRecord deputyRecord() {
        // Tight ~0.5 km circumnavigation: δe ~4e-5 (→ ~280 m radial / ~570 m
        // in-track football) and Δi 0.003° (→ ~360 m cross-track).
        return new GpRecord("DEMO DEPUTY (NMC)", "2026-001B", EPOCH,
                15.20, 0.00004, 51.603, 0.0, 90.0, 270.0,
                99002, 999, 1, 0.0, "U", 0);
    }
}
