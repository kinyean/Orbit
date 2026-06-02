package space.orbit.backend.prop;

import jakarta.annotation.PostConstruct;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.io.GpRecord;

/**
 * Builds an Orekit {@link TLE} from a {@link GpRecord} (CelesTrak GP / OMM mean
 * elements). The reachable catalog mirrors serve OMM JSON, not TLE line
 * strings, so we use Orekit's full-element constructor.
 *
 * <p>Unit conversions to Orekit's conventions:
 * <ul>
 *   <li>mean motion: rev/day &rarr; rad/s ({@code &times; 2&pi; / 86400}).</li>
 *   <li>angles (i, raan, argP, M): degrees &rarr; radians.</li>
 *   <li>eccentricity, B*: as-is.</li>
 * </ul>
 *
 * <p>The mean-motion 1st/2nd derivatives are set to 0: SGP4 does not use them
 * (they are historical TLE fields), so this avoids the only genuinely
 * error-prone unit conversion with zero impact on propagation.
 */
@Service
@DependsOn("orekitConfig")
public class TleFactory {

    private static final double REV_PER_DAY_TO_RAD_PER_S = 2.0 * Math.PI / 86400.0;

    private TimeScale utc;

    @PostConstruct
    void init() {
        // Safe: @DependsOn ensures Orekit data is loaded first.
        utc = TimeScalesFactory.getUTC();
    }

    /** Convert one GP record to an Orekit TLE. */
    public TLE fromGp(GpRecord r) {
        AbsoluteDate epoch = new AbsoluteDate(r.epoch(), utc);
        double meanMotionRadPerSec = r.meanMotion() * REV_PER_DAY_TO_RAD_PER_S;

        Launch launch = parseObjectId(r.objectId());
        char classification = (r.classificationType() != null && !r.classificationType().isBlank())
                ? r.classificationType().charAt(0)
                : 'U';

        return new TLE(
                r.noradId(),
                classification,
                launch.year(),
                launch.number(),
                launch.piece(),
                r.ephemerisType(),
                Math.max(0, r.elementSetNo()),
                epoch,
                meanMotionRadPerSec,
                0.0,                 // mean-motion 1st derivative — unused by SGP4
                0.0,                 // mean-motion 2nd derivative — unused by SGP4
                r.eccentricity(),
                Math.toRadians(r.inclination()),
                Math.toRadians(r.argPericenter()),
                Math.toRadians(r.raan()),
                Math.toRadians(r.meanAnomaly()),
                Math.max(0, r.revAtEpoch()),
                r.bstar(),
                utc);
    }

    /**
     * Parse a COSPAR international designator like {@code 1998-067A} into launch
     * year (4-digit), launch number, and piece. Falls back to sensible defaults
     * for missing/malformed ids so a few odd records don't break the catalog.
     */
    static Launch parseObjectId(String objectId) {
        if (objectId == null) {
            return new Launch(2000, 1, "A");
        }
        int dash = objectId.indexOf('-');
        if (dash <= 0 || dash >= objectId.length() - 1) {
            return new Launch(2000, 1, "A");
        }
        int year;
        try {
            year = Integer.parseInt(objectId.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            return new Launch(2000, 1, "A");
        }
        String rest = objectId.substring(dash + 1).trim();
        int splitAt = 0;
        while (splitAt < rest.length() && Character.isDigit(rest.charAt(splitAt))) {
            splitAt++;
        }
        int number;
        try {
            number = splitAt > 0 ? Integer.parseInt(rest.substring(0, splitAt)) : 1;
        } catch (NumberFormatException e) {
            number = 1;
        }
        String piece = splitAt < rest.length() ? rest.substring(splitAt) : "A";
        if (piece.isBlank()) {
            piece = "A";
        }
        return new Launch(year, number, piece);
    }

    record Launch(int year, int number, String piece) {}
}
