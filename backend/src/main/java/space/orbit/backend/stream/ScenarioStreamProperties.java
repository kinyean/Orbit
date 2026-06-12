package space.orbit.backend.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-scenario stream configuration ({@code orbit.scenario.*}). Defaults live in
 * application.yml; every field is environment-overridable (12-factor). Picked up
 * by {@code @ConfigurationPropertiesScan} on the application class.
 *
 * @param stepSeconds          requested spacing between ephemeris samples
 * @param maxSamplesPerSat     hard cap on samples per spacecraft; the service
 *                             raises the effective step to stay under it and
 *                             echoes the effective step in the envelope (R8)
 * @param includeRelativeVelocity emit relative velocity in the 4B relative block
 * @param includePaths         draw orbit-path trails in the scenario CZML
 */
@ConfigurationProperties(prefix = "orbit.scenario")
public record ScenarioStreamProperties(
        int stepSeconds,
        int maxSamplesPerSat,
        boolean includeRelativeVelocity,
        boolean includePaths) {

    public ScenarioStreamProperties {
        if (stepSeconds <= 0) {
            stepSeconds = 30;
        }
        if (maxSamplesPerSat <= 1) {
            maxSamplesPerSat = 5000;
        }
    }
}
