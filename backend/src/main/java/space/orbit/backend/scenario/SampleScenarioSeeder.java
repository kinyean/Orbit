package space.orbit.backend.scenario;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.orekit.propagation.analytical.tle.TLE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.prop.TleFactory;
import space.orbit.backend.security.DevUserAuthenticationFilter;

/**
 * Seeds the demo scenario set (idempotent) so a new user can load a sample and
 * play it back without prior training (SRS §5.6.1, US-UX-01). Real catalog
 * satellites are thousands of km apart — useless for proximity ops — so the demos
 * use <em>synthetic</em> spacecraft (high NORAD range, frozen TLEs) in curated
 * close-range geometries covering the feature surface: an NMC formation, a
 * rendezvous, a sensor/link-budget inspection, an eclipse pass, and a V-bar
 * station point (the hold/glideslope starting position).
 *
 * <p><b>Who gets seeded (Phase 11):</b> the dev user at startup (stub mode), and
 * every newly provisioned user via {@link UserProvisionedEvent} — fired once per
 * user, {@code AFTER_COMMIT} of the provisioning transaction, so real OIDC users
 * see the demos on their first request too (the scenario list is owner-scoped).
 * Each demo commits in its own transaction ({@code seedIfAbsent} is
 * {@code REQUIRES_NEW}) with a per-demo catch, so one bad demo never blocks login,
 * startup, or the other demos. Users who delete a demo are not re-seeded (seeding
 * happens once per provisioning; the dev user is re-ensured each startup).
 *
 * <p>The elements are exposed as static records so tests can validate each demo's
 * geometry against the same definition ({@code SampleScenarioFormationTests}).
 */
@Component
@DependsOn("orekitConfig")
public class SampleScenarioSeeder {

    private static final Logger log = LoggerFactory.getLogger(SampleScenarioSeeder.class);

    private static final UUID DEV_USER = UUID.fromString(DevUserAuthenticationFilter.DEV_USER_ID);
    static final String NAME = "Demo — close formation (NMC)";
    static final String RENDEZVOUS_NAME = "Demo — rendezvous (co-orbit approach)";
    static final String SENSOR_NAME = "Demo — inspection & link budget (sensors)";
    static final String ECLIPSE_NAME = "Demo — eclipse & lighting (day-night)";
    static final String VBAR_NAME = "Demo — V-bar station (hold & glideslope start)";
    private static final String EPOCH = "2026-06-01T00:00:00.000";
    private static final String START = "2026-06-01T00:00:00Z";
    private static final String END = "2026-06-01T03:00:00Z"; // ~2 orbits
    /** The eclipse demo spans ~4 orbits so umbra passes are guaranteed in-window. */
    private static final String END_6H = "2026-06-01T06:00:00Z";

    private final TleFactory tleFactory;
    private final ScenarioService scenarioService;

    public SampleScenarioSeeder(TleFactory tleFactory, ScenarioService scenarioService) {
        this.tleFactory = tleFactory;
        this.scenarioService = scenarioService;
    }

    /** Startup: ensure the dev user's demo set (stub mode; the dev user comes from V2). */
    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        seedAll(DEV_USER);
    }

    /**
     * First sight of a new user (Phase 11): seed their demo set. {@code AFTER_COMMIT}
     * so the user row is durably there; no {@code @Transactional} here — each
     * {@code seedIfAbsent} opens its own {@code REQUIRES_NEW} transaction (a joined
     * transaction in an after-commit listener would silently not persist).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserProvisioned(UserProvisionedEvent event) {
        log.info("Seeding demo scenarios for new user {}", event.email());
        seedAll(event.userId());
    }

    /** Ensure the full demo set for {@code ownerId} (idempotent per owner + name). */
    public void seedAll(UUID ownerId) {
        seedOne(ownerId, NAME, this::formationBody);
        seedOne(ownerId, RENDEZVOUS_NAME, this::rendezvousBody);
        seedOne(ownerId, SENSOR_NAME, this::sensorBody);
        seedOne(ownerId, ECLIPSE_NAME, this::eclipseBody);
        seedOne(ownerId, VBAR_NAME, this::vbarBody);
    }

    /** Best-effort per demo — one failure never blocks startup/login or the other demos. */
    private void seedOne(UUID ownerId, String name, Supplier<ScenarioBody> body) {
        try {
            scenarioService.seedIfAbsent(ownerId, name, body.get());
            log.info("Sample scenario ensured: \"{}\"", name);
        } catch (RuntimeException e) {
            log.warn("Sample scenario \"{}\" seed skipped: {}", name, e.toString());
        }
    }

    // --- demo bodies -----------------------------------------------------------

    /** A tight bounded NMC circumnavigation — the signature proximity-view shape. */
    private ScenarioBody formationBody() {
        return new ScenarioBody(1, "sgp4", new ScenarioBody.TimeRange(START, END),
                role("chief", chiefRecord()),
                List.of(role("deputy", deputyRecord())));
    }

    /**
     * Built for the rendezvous template: the chaser shares the chief's orbit ~1°
     * behind (~120 km along-track) — well-conditioned for a Lambert transfer, unlike
     * the ultra-close NMC (where two near-coincident positions make absolute Lambert
     * degenerate). A good arrival is ~01:50Z. The template's default
     * {@code corrected=true} (Phase 9A, {@code RendezvousCorrector}) then iterates the
     * two burns against the <em>real</em> propagators, so the transfer genuinely
     * converges on the chief (R16 resolved) — the open-loop two-body seed alone would
     * miss by the SGP4-vs-numerical model difference. Some arrival times still hit
     * Orekit IodLambert's expensive branch (tens of km/s) — flagged by the panel's ΔV
     * warning and refused by the corrector's seed guard.
     */
    private ScenarioBody rendezvousBody() {
        return new ScenarioBody(1, "sgp4", new ScenarioBody.TimeRange(START, END),
                role("chief", chiefRecord()),
                List.of(role("deputy", rendezvousChaserRecord())));
    }

    /**
     * Gita's demo (UC-4): the NMC pair, with an inspection imager + RF link budget on
     * the chief. Boresight = body +Y (the ram/nose axis under the default LVLH
     * attitude), so the circumnavigating deputy sweeps through the cone once per
     * orbit → recurring AOS/LOS windows on the timeline + an SNR band.
     */
    private ScenarioBody sensorBody() {
        ScenarioBody.Sensor imager = demoImager();
        ScenarioBody.Role chief = new ScenarioBody.Role("chief",
                chiefRecordId(), "DEMO CHIEF", initialState(chiefRecord()),
                List.of(), List.of(imager), null, List.of());
        return new ScenarioBody(1, "sgp4", new ScenarioBody.TimeRange(START, END),
                chief, List.of(role("deputy", deputyRecord())));
    }

    /** Frank's UC-5 demo: the NMC pair over ~4 orbits, so umbra/penumbra bands and
     *  Sun-consistent lighting/dimming show up without any editing. */
    private ScenarioBody eclipseBody() {
        return new ScenarioBody(1, "sgp4", new ScenarioBody.TimeRange(START, END_6H),
                role("chief", chiefRecord()),
                List.of(role("deputy", deputyRecord())));
    }

    /** Maya's close-range start point: a deputy parked ~2 km behind the chief on the
     *  V-bar — the well-conditioned launchpad for the hold / glideslope templates. */
    private ScenarioBody vbarBody() {
        return new ScenarioBody(1, "sgp4", new ScenarioBody.TimeRange(START, END),
                role("chief", chiefRecord()),
                List.of(role("deputy", vbarStationRecord())));
    }

    private ScenarioBody.Role role(String roleName, GpRecord gp) {
        return new ScenarioBody.Role(roleName, gp.noradId(), gp.objectName(), initialState(gp));
    }

    private ScenarioBody.InitialState initialState(GpRecord gp) {
        TLE tle = tleFactory.fromGp(gp);
        return new ScenarioBody.InitialState("tle",
                new ScenarioBody.Tle(tle.getLine1(), tle.getLine2(), tle.getDate().toString()));
    }

    private static int chiefRecordId() {
        return chiefRecord().noradId();
    }

    // --- synthetic elements (exposed for the formation tests) -------------------

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

    /**
     * Rendezvous chaser: <strong>identical orbit to the chief</strong> but 1° behind
     * in mean anomaly (~120 km along-track). Same plane and altitude ⇒ they hold that
     * gap (no drift), and the two positions are well separated, so a two-impulse
     * Lambert transfer (US-MAN-03/06) is well-conditioned — the geometry the template
     * is designed for. Best demo arrival ≈ {@code 2026-06-01T01:50:00Z}; with the
     * default differential correction (Phase 9A) the transfer converges on the chief.
     */
    public static GpRecord rendezvousChaserRecord() {
        return new GpRecord("DEMO CHASER", "2026-002B", EPOCH,
                15.20, 0.00001, 51.600, 0.0, 0.0, 359.0,
                99003, 999, 1, 0.0, "U", 0);
    }

    /**
     * V-bar station keeper: the chief's exact orbit, mean anomaly −0.0167°
     * (≈ 2 km behind along the velocity vector, the TLE format's 4-decimal
     * resolution). Same SMA/plane ⇒ it holds the point passively — a clean, static
     * relative geometry to aim the hold / glideslope / station-keep templates at.
     */
    public static GpRecord vbarStationRecord() {
        return new GpRecord("DEMO STATION (V-BAR)", "2026-003A", EPOCH,
                15.20, 0.00001, 51.600, 0.0, 0.0, 359.9833,
                99004, 999, 1, 0.0, "U", 0);
    }

    /** The sensor demo's imager definition (exposed for the formation tests). */
    public static ScenarioBody.Sensor demoImager() {
        return new ScenarioBody.Sensor(
                "demo-imager", "optical", "Inspection imager",
                new ScenarioBody.Fov("cone", 35.0, 0.0, 0.0),
                50.0, 5_000.0,
                new ScenarioBody.Mount(new double[] {0.0, 1.0, 0.0}, 0.0),
                new ScenarioBody.LinkBudget("rf", 10.0, 5.0, 26.0, 1.0e6, 6.0));
    }
}
