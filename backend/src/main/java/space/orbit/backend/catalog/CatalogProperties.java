package space.orbit.backend.catalog;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Catalog-mode configuration ({@code orbit.catalog.*}). Defaults live in
 * application.yml; every field is environment-overridable (12-factor).
 *
 * @param seedFile             path to the bundled offline GP/TLE seed JSON
 * @param sources              refresh source URLs, tried in order
 * @param refreshCron          cron for periodic TLE refresh
 * @param propagationIntervalMs delay between catalog propagation passes
 * @param windowSeconds        propagation window length per pass
 * @param stepSeconds          spacing between samples within the window
 * @param fetchTimeoutMs       per-source HTTP timeout for refresh
 * @param maxSatellites        cap on tracked satellites (0 = no cap)
 */
@ConfigurationProperties(prefix = "orbit.catalog")
public record CatalogProperties(
        String seedFile,
        List<String> sources,
        String refreshCron,
        long propagationIntervalMs,
        int windowSeconds,
        int stepSeconds,
        int fetchTimeoutMs,
        int maxSatellites) {
}
