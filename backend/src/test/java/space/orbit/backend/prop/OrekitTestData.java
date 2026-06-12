package space.orbit.backend.prop;

import java.io.File;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;

/**
 * Test helper: loads the Orekit data bundle into the default context using the
 * {@code orekit.data.path} system property set by the Gradle test task
 * (build/orekit-data/orekit-data-main). Idempotent — safe to call from each
 * test class's {@code @BeforeAll}.
 */
public final class OrekitTestData {

    private static boolean loaded = false;

    private OrekitTestData() {}

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        String path = System.getProperty("orekit.data.path");
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                    "orekit.data.path system property not set — run via Gradle "
                    + "(the test task provisions Orekit data and sets the property).");
        }
        File dir = new File(path);
        if (!dir.isDirectory()) {
            throw new IllegalStateException("Orekit data dir missing: " + dir.getAbsolutePath());
        }
        DataContext.getDefault().getDataProvidersManager().addProvider(new DirectoryCrawler(dir));
        loaded = true;
    }
}
