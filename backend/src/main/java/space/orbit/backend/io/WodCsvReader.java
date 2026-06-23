package space.orbit.backend.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads a "Whole-Orbit Data" (WOD) telemetry CSV into a {@link MeasuredEphemeris}
 * (Decision: measured-data ingestion). Mirrors {@link GpCatalogParser}'s
 * parse-an-InputStream shape, but the WOD format is not a flat table — it is
 * <em>stacked blocks</em>, one per telemetry channel:
 *
 * <pre>
 *   Satellite:,TELEOS-2
 *   Telemetry Mnemonic:,SW_TM_ODCS_GNSS_ECI_POS_X
 *   Data Order:,1,Data Mnemonic:,...
 *   Data Value,Data Value Type,...,Onboard Timestamp (UTC)
 *   "6469.83987","Double",...,"01/01/2026 00:02:56"
 *   ... (tens of thousands of rows) ...
 *   Telemetry Mnemonic:,SW_TM_ODCS_GNSS_ECI_POS_Y
 *   ...
 * </pre>
 *
 * <p><b>Streaming, single-pass</b> (the files run to hundreds of MB): we read
 * line-by-line, track the current {@code Telemetry Mnemonic:} block, and keep
 * only the six channels we need — ECI position X/Y/Z (km) and velocity X/Y/Z
 * (km/s). Each channel's rows are collected keyed by onboard timestamp, then the
 * six are aligned by timestamp into states. Invalid GNSS fixes (zero-filled
 * {@code (0,0,0)}, i.e. |r| &lt; 6000 km) are dropped. Output is metres / m/s in
 * EME2000, time-ordered.
 *
 * <p>Slice 2 also picks up the estimated-attitude quaternion
 * ({@code SW_TM_ADCS_EST_ATTD_Q1..Q4_8}) as a <em>parallel</em> series (its own
 * timestamps — the ADCS cadence can differ from GNSS, so it is not intersected
 * with position). Components are kept raw; the convention conversion lives
 * downstream. The remaining engineering/diagnostic channels are ignored.
 */
@Component
public class WodCsvReader {

    private static final DateTimeFormatter ONBOARD_TS =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final double MIN_VALID_RADIUS_M = 6_000_000.0; // drop (0,0,0) GNSS dropouts
    private static final double KM = 1000.0;
    /** Reject attitude rows whose quaternion norm strays from unit (ADCS dropouts / all-zero). */
    private static final double QUAT_NORM_TOL = 0.1;

    // The six ECI position/velocity channels (slots 0..5) followed by the four
    // estimated-attitude quaternion channels (slots 6..9), in a fixed slot order.
    private static final int POS_CHANNELS = 6;
    private static final String[] CHANNELS = {
            "SW_TM_ODCS_GNSS_ECI_POS_X", "SW_TM_ODCS_GNSS_ECI_POS_Y", "SW_TM_ODCS_GNSS_ECI_POS_Z",
            "SW_TM_ODCS_GNSS_ECI_VEL_X", "SW_TM_ODCS_GNSS_ECI_VEL_Y", "SW_TM_ODCS_GNSS_ECI_VEL_Z",
            "SW_TM_ADCS_EST_ATTD_Q1_8", "SW_TM_ADCS_EST_ATTD_Q2_8",
            "SW_TM_ADCS_EST_ATTD_Q3_8", "SW_TM_ADCS_EST_ATTD_Q4_8"
    };

    public MeasuredEphemeris parse(InputStream csv) throws IOException {
        // One timestamp→value map per channel slot (0..5). ~56k entries each.
        @SuppressWarnings("unchecked")
        Map<Long, Double>[] byTs = new Map[CHANNELS.length];
        for (int i = 0; i < byTs.length; i++) {
            byTs[i] = new HashMap<>(65_536);
        }
        String satelliteName = null;
        int channel = -1; // current block's slot, or -1 if we don't want it

        try (BufferedReader r = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) == '"') { // a data row
                    if (channel >= 0) {
                        ingestRow(line, byTs[channel]);
                    }
                    continue;
                }
                // a header/metadata line: "Key:,value,..."
                if (line.startsWith("Satellite:")) {
                    String[] f = line.split(",", -1);
                    if (f.length > 1 && !f[1].isBlank()) {
                        satelliteName = f[1].trim();
                    }
                } else if (line.startsWith("Telemetry Mnemonic:")) {
                    String[] f = line.split(",", -1);
                    channel = f.length > 1 ? channelSlot(f[1].trim()) : -1;
                }
                // Data Order / column-header lines are ignored (each wanted channel
                // is its own single-order block).
            }
        }

        List<MeasuredEphemeris.Sample> samples = alignPosition(byTs);
        List<MeasuredEphemeris.AttitudeSample> attitude = alignAttitude(byTs);
        return new MeasuredEphemeris(satelliteName, "EME2000", samples, attitude);
    }

    private static int channelSlot(String mnemonic) {
        for (int i = 0; i < CHANNELS.length; i++) {
            if (CHANNELS[i].equals(mnemonic)) {
                return i;
            }
        }
        return -1;
    }

    /** Parse one quoted data row: field 0 is the value, field 8 the onboard UTC timestamp. */
    private static void ingestRow(String line, Map<Long, Double> sink) {
        // Values/timestamps contain no commas, so stripping quotes and splitting is safe.
        String[] f = line.replace("\"", "").split(",", -1);
        if (f.length < 9) {
            return; // malformed row — skip
        }
        try {
            double value = Double.parseDouble(f[0].trim());
            long epochMillis = LocalDateTime.parse(f[8].trim(), ONBOARD_TS)
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
            sink.put(epochMillis, value);
        } catch (RuntimeException skip) {
            // unparseable value/timestamp — skip the row
        }
    }

    /** Intersect the six position/velocity channels by timestamp, drop invalid fixes, sort by time. */
    private static List<MeasuredEphemeris.Sample> alignPosition(Map<Long, Double>[] byTs) {
        List<Long> times = new ArrayList<>(byTs[0].keySet());
        times.sort(Long::compare);

        List<MeasuredEphemeris.Sample> out = new ArrayList<>(times.size());
        for (long t : times) {
            Double px = byTs[0].get(t), py = byTs[1].get(t), pz = byTs[2].get(t);
            Double vx = byTs[3].get(t), vy = byTs[4].get(t), vz = byTs[5].get(t);
            if (px == null || py == null || pz == null || vx == null || vy == null || vz == null) {
                continue; // not present in all six channels at this instant
            }
            double xm = px * KM, ym = py * KM, zm = pz * KM;
            if (Math.sqrt(xm * xm + ym * ym + zm * zm) < MIN_VALID_RADIUS_M) {
                continue; // zero-filled (0,0,0) invalid GNSS fix
            }
            out.add(new MeasuredEphemeris.Sample(t, xm, ym, zm, vx * KM, vy * KM, vz * KM));
        }
        return out;
    }

    /**
     * Intersect the four attitude-quaternion channels (slots 6..9) by timestamp,
     * drop non-unit rows (ADCS dropouts / all-zero), sort by time. Components are
     * kept raw — interpretation is downstream (slice 2). Empty when the file
     * carries no attitude (e.g. a position-only fixture).
     */
    private static List<MeasuredEphemeris.AttitudeSample> alignAttitude(Map<Long, Double>[] byTs) {
        List<Long> times = new ArrayList<>(byTs[POS_CHANNELS].keySet());
        times.sort(Long::compare);

        List<MeasuredEphemeris.AttitudeSample> out = new ArrayList<>(times.size());
        for (long t : times) {
            Double q1 = byTs[6].get(t), q2 = byTs[7].get(t), q3 = byTs[8].get(t), q4 = byTs[9].get(t);
            if (q1 == null || q2 == null || q3 == null || q4 == null) {
                continue; // not present in all four channels at this instant
            }
            double norm = Math.sqrt(q1 * q1 + q2 * q2 + q3 * q3 + q4 * q4);
            if (Math.abs(norm - 1.0) > QUAT_NORM_TOL) {
                continue; // not a unit quaternion (ADCS not in a valid estimate)
            }
            out.add(new MeasuredEphemeris.AttitudeSample(t, q1, q2, q3, q4));
        }
        return out;
    }
}
