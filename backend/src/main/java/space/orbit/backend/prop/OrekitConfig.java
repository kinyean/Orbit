package space.orbit.backend.prop;

import jakarta.annotation.PostConstruct;
import java.io.File;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.time.TimeScalesFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Loads the Orekit data bundle (IERS Earth-orientation, leap seconds,
 * ephemerides, gravity models) into the default {@link DataContext} at
 * startup. This MUST happen before any frame/time/propagation call, so every
 * Orekit-using bean declares {@code @DependsOn("orekitConfig")}.
 *
 * <p>Path resolution order:
 * <ol>
 *   <li>system property {@code orekit.data.path} — set by the Gradle test task
 *       to {@code build/orekit-data/orekit-data-main};</li>
 *   <li>{@code orbit.orekit.data-path} (bound from {@code OREKIT_DATA_PATH};
 *       the container sets {@code /opt/orekit-data}).</li>
 * </ol>
 *
 * <p>The data is baked into the image at Docker build time (CelesTrak and the
 * Orekit GitLab are not assumed reachable at runtime). See Decision 7 and the
 * Phase 2 plan.
 */
@Configuration
public class OrekitConfig {

    private static final Logger log = LoggerFactory.getLogger(OrekitConfig.class);

    @Value("${orbit.orekit.data-path:/opt/orekit-data}")
    private String configuredDataPath;

    @PostConstruct
    public void loadOrekitData() {
        String sysProp = System.getProperty("orekit.data.path");
        String path = (sysProp != null && !sysProp.isBlank()) ? sysProp : configuredDataPath;
        File dataDir = new File(path);
        if (!dataDir.isDirectory()) {
            throw new IllegalStateException(
                    "Orekit data directory not found: " + dataDir.getAbsolutePath()
                    + " — set OREKIT_DATA_PATH / orbit.orekit.data-path, or run the "
                    + "provisionOrekitData Gradle task.");
        }
        DataContext.getDefault().getDataProvidersManager().addProvider(new DirectoryCrawler(dataDir));
        // Force a data-dependent call to fail fast if the bundle is incomplete.
        TimeScalesFactory.getUTC();
        log.info("Orekit data loaded from {}", dataDir.getAbsolutePath());
    }
}
