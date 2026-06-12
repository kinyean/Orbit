package space.orbit.backend.stream;

/**
 * The encoded, ready-to-send payloads for one scenario stream connect. The
 * handler gzips each and sends them as binary frames.
 *
 * @param czml     the {@code scenario-czml} envelope (global-view layer); always present
 * @param relative the {@code scenario-relative} envelope (proximity view); {@code null}
 *                 in slice 4A, populated in 4B
 * @param effectiveStepSeconds the step actually used (may exceed the requested
 *                 step when the sample cap bites — echoed for diagnostics/tests)
 */
public record EncodedScenario(String czml, String relative, int effectiveStepSeconds) {
}
