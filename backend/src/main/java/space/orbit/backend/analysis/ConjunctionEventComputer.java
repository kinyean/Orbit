package space.orbit.backend.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Intra-scenario conjunction detection (Phase 8, US-EVT-02 / SRS §3.12.1): the
 * pairwise closest approach of every unordered pair of scenario craft, reported when
 * it falls below the miss-distance threshold. Like the other Phase-7/8 computers it
 * runs on the <strong>already-sampled</strong> LVLH trajectories — range between two
 * craft is frame-invariant, so the chief-relative samples suffice — with a fixed
 * golden-section refine on the samples (no re-propagation), so it is deterministic
 * (R11) and consistent with the rendered scene.
 *
 * <p>Distinct from {@code ScenarioStreamService.refineTca} (the per-deputy
 * chief-relative TCA), which refines on the live propagators; this refines on the
 * sampled arrays so a maneuvered deputy stays consistent (Decision 24).
 */
public class ConjunctionEventComputer {

    private static final int GOLDEN_ITERS = 60; // matches refineTca

    /**
     * @param crafts      every craft (chief {@code pos == null} → LVLH origin)
     * @param thresholdM  report a pair only when its in-window minimum is below this
     * @param firstT      first sample time (s, relative to {@code epoch}); may be negative (margin)
     * @param step        sample spacing (s)
     * @param steps       number of steps (samples = {@code steps + 1})
     * @param durationSec scenario window length; only minima inside {@code [0, durationSec]} count
     */
    public List<ConjunctionEvent> compute(List<SampledCraft> crafts, double thresholdM,
                                          double firstT, int step, int steps,
                                          Instant epoch, long durationSec) {
        List<ConjunctionEvent> out = new ArrayList<>();
        for (int i = 0; i < crafts.size(); i++) {
            for (int j = i + 1; j < crafts.size(); j++) {
                SampledCraft a = crafts.get(i);
                SampledCraft b = crafts.get(j);
                double coarseMin = Double.POSITIVE_INFINITY;
                double coarseT = 0.0;
                for (int k = 0; k <= steps; k++) {
                    double t = firstT + (double) k * step;
                    if (t < 0.0 || t > durationSec) {
                        continue;
                    }
                    double d = range(a, b, t);
                    if (d < coarseMin) {
                        coarseMin = d;
                        coarseT = t;
                    }
                }
                if (coarseMin >= thresholdM || !Double.isFinite(coarseMin)) {
                    continue;
                }
                double[] tca = refine(a, b, Math.max(0.0, coarseT - step),
                        Math.min((double) durationSec, coarseT + step), coarseT, coarseMin);
                int lo = Math.min(a.noradId(), b.noradId());
                int hi = Math.max(a.noradId(), b.noradId());
                out.add(new ConjunctionEvent(lo, hi,
                        epoch.plusMillis(Math.round(tca[0] * 1000.0)), tca[1]));
            }
        }
        return out;
    }

    /** Golden-section refine of the pair range minimum over {@code [lo,hi]} (fixed iters → R11). */
    private double[] refine(SampledCraft a, SampledCraft b, double lo, double hi,
                            double coarseT, double coarseDist) {
        if (!(hi > lo)) {
            return new double[] {coarseT, coarseDist};
        }
        final double gr = (Math.sqrt(5.0) - 1.0) / 2.0;
        double aL = lo;
        double bH = hi;
        double c = bH - gr * (bH - aL);
        double d = aL + gr * (bH - aL);
        double fc = range(a, b, c);
        double fd = range(a, b, d);
        for (int it = 0; it < GOLDEN_ITERS; it++) {
            if (fc < fd) {
                bH = d;
                d = c;
                fd = fc;
                c = bH - gr * (bH - aL);
                fc = range(a, b, c);
            } else {
                aL = c;
                c = d;
                fc = fd;
                d = aL + gr * (bH - aL);
                fd = range(a, b, d);
            }
        }
        double tBest = 0.5 * (aL + bH);
        double fBest = range(a, b, tBest);
        return fBest <= coarseDist ? new double[] {tBest, fBest} : new double[] {coarseT, coarseDist};
    }

    /** Separation (m) between two craft at {@code t}, in the LVLH scene (frame-invariant). */
    private static double range(SampledCraft a, SampledCraft b, double t) {
        double[] pa = new double[3];
        double[] pb = new double[3];
        posAt(a.pos(), a.posStride(), t, pa);
        posAt(b.pos(), b.posStride(), t, pb);
        double dx = pa[0] - pb[0];
        double dy = pa[1] - pb[1];
        double dz = pa[2] - pb[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Linear interpolation of {@code [R,I,C]} at {@code t} into {@code out3}; null/short → origin. */
    private static void posAt(double[] s, int stride, double t, double[] out3) {
        if (s == null || stride < 4 || s.length < stride) {
            out3[0] = out3[1] = out3[2] = 0.0; // chief origin (or absent)
            return;
        }
        int n = s.length / stride;
        double tFirst = s[0];
        double tLast = s[(n - 1) * stride];
        if (t <= tFirst || n == 1) {
            out3[0] = s[1];
            out3[1] = s[2];
            out3[2] = s[3];
            return;
        }
        if (t >= tLast) {
            int base = (n - 1) * stride;
            out3[0] = s[base + 1];
            out3[1] = s[base + 2];
            out3[2] = s[base + 3];
            return;
        }
        int lo = 0;
        int hi = n - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (s[mid * stride] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        int ba = lo * stride;
        int bb = hi * stride;
        double ta = s[ba];
        double tb = s[bb];
        double f = tb > ta ? (t - ta) / (tb - ta) : 0.0;
        out3[0] = s[ba + 1] + (s[bb + 1] - s[ba + 1]) * f;
        out3[1] = s[ba + 2] + (s[bb + 2] - s[ba + 2]) * f;
        out3[2] = s[ba + 3] + (s[bb + 3] - s[ba + 3]) * f;
    }
}
