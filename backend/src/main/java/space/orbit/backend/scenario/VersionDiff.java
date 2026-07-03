package space.orbit.backend.scenario;

import java.util.List;

/**
 * Structured diff of a scenario version against its immediate predecessor
 * (Phase 10 governance, US-INFRA-06 follow-up). Returned by
 * {@code GET /scenarios/{id}/versions/{v}/diff}.
 *
 * <p>Every {@link ScenarioVersion} stores the full {@link ScenarioBody}, so
 * "what changed between v(n-1) and v(n)" is fully recoverable by comparing two
 * bodies — <b>retroactively</b>, even for scenarios saved before this endpoint
 * existed, with no schema change. Unlike the flat hand-written
 * {@code audit_log.diff_summary} string, the diff carries the actual structure
 * and numbers (ΔV components, epochs, resolved role names, thresholds). The
 * backend does the domain-aware formatting; the UI just renders lines.
 *
 * <p>{@code fromVersionNo} is null for v1 (the initial version — every element
 * is an {@code add} relative to an empty scenario).
 */
public record VersionDiff(int versionNo, Integer fromVersionNo, List<Change> changes) {

    /**
     * One change between the two versions.
     *
     * @param op       {@code add} / {@code remove} / {@code change}
     * @param category {@code maneuver} / {@code sensor} / {@code constraint} /
     *                 {@code attitude} / {@code settings} / {@code roster}
     * @param detail   human-readable text carrying the actual numbers
     */
    public record Change(String op, String category, String detail) {}
}
