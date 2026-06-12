package space.orbit.backend.stream;

/**
 * One deputy's relative-state samples in the chief's LVLH frame (proximity view,
 * Phase 4B / US-STREAM-02). Plain data — no Orekit types — so the encoder stays
 * decoupled from the propagation engine (mirrors {@link ScenarioSatelliteSamples}).
 * The chief is the LVLH origin and is not included.
 *
 * @param noradId            NORAD catalog id
 * @param name               display label
 * @param interpolationDegree clamped {@code min(5, sampleCount-1)} (client hint)
 * @param samples            flat interleaved samples relative to the scenario epoch:
 *                           {@code [t,R,I,C, ...]} (stride 4) or
 *                           {@code [t,R,I,C,vR,vI,vC, ...]} (stride 7) — R/I/C are
 *                           radial / in-track / cross-track metres, v* metres/s
 */
public record RelativeSamples(
        int noradId,
        String name,
        int interpolationDegree,
        double[] samples) {
}
