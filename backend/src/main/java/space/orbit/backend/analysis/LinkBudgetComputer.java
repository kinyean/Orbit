package space.orbit.backend.analysis;

import java.util.ArrayList;
import java.util.List;
import space.orbit.backend.scenario.ScenarioBody;

/**
 * Link-budget / SNR analysis (Phase 9D, US-EVT-05 / SRS §3.6). For every sensor that
 * carries a {@link ScenarioBody.LinkBudget}, against every other craft, computes the SNR
 * over the window from the sensor↔target range — on the <strong>already-sampled</strong>
 * chief-LVLH trajectory the stream rendered (no re-propagation), the same
 * consistency-by-construction pattern as {@link SensorEventComputer} (Decision 24).
 *
 * <p>Concise satellite-link model: {@code SNR(r) = EIRP + G/T − Lfs(r) + 228.6 −
 * 10·log10(B)} with free-space path loss {@code Lfs = 20·log10(4π r f / c)} — so SNR drops
 * ~6.02 dB per range-doubling. Deterministic (R11): a pure function of the sampled range,
 * no RNG / wall-clock, increasing-time grid scan.
 */
public class LinkBudgetComputer {

    /** Speed of light (m/s). */
    private static final double C = 299_792_458.0;
    /** {@code −10·log10(k)}, k = Boltzmann constant — the standard thermal-noise constant. */
    private static final double BOLTZMANN_TERM_DB = 228.6;
    /** Cap on series points (the SNR series is strided to keep the payload bounded). */
    private static final int MAX_POINTS = 120;

    /**
     * One SNR series per (link-budget sensor, target craft) over the (margined) grid
     * {@code firstT + k·step}, k ∈ [0, steps].
     */
    public List<LinkBudgetSeries> compute(List<SampledCraft> crafts, double firstT, int step, int steps) {
        List<LinkBudgetSeries> out = new ArrayList<>();
        int seriesStride = Math.max(1, (steps + 1 + MAX_POINTS - 1) / MAX_POINTS);
        for (SampledCraft host : crafts) {
            if (host.sensors() == null || host.sensors().isEmpty()) {
                continue;
            }
            for (ScenarioBody.Sensor sensor : host.sensors()) {
                ScenarioBody.LinkBudget lb = sensor.linkBudget();
                if (lb == null) {
                    continue;
                }
                for (SampledCraft target : crafts) {
                    if (target.noradId() == host.noradId()) {
                        continue;
                    }
                    double[] series = sampleSeries(host, target, lb, firstT, step, steps, seriesStride);
                    out.add(new LinkBudgetSeries(host.noradId(), sensor.id(), target.noradId(),
                            lb.kind(), lb.thresholdDb(), series));
                }
            }
        }
        return out;
    }

    private double[] sampleSeries(SampledCraft host, SampledCraft target, ScenarioBody.LinkBudget lb,
                                  double firstT, int step, int steps, int seriesStride) {
        List<Double> vals = new ArrayList<>();
        double[] h = new double[3];
        double[] tg = new double[3];
        for (int k = 0; k <= steps; k += seriesStride) {
            double t = firstT + (double) k * step;
            posAt(host.pos(), host.posStride(), t, h);
            posAt(target.pos(), target.posStride(), t, tg);
            double dx = tg[0] - h[0];
            double dy = tg[1] - h[1];
            double dz = tg[2] - h[2];
            double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
            vals.add(t);
            vals.add(snr(lb, range));
        }
        double[] arr = new double[vals.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = vals.get(i);
        }
        return arr;
    }

    /** SNR (dB) at slant range {@code r} (m). */
    static double snr(ScenarioBody.LinkBudget lb, double r) {
        if (r <= 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double f = Math.max(1.0, lb.frequencyGhz() * 1.0e9);
        double lfs = 20.0 * Math.log10(4.0 * Math.PI * r * f / C);
        return lb.eirpDbw() + lb.gOverTdbK() - lfs + BOLTZMANN_TERM_DB
                - 10.0 * Math.log10(Math.max(1.0, lb.bandwidthHz()));
    }

    /** Linear interpolation of {@code [R,I,C]} at {@code t}; HOLD-clamp at the ends (mirrors
     *  {@link SensorEventComputer}). {@code null}/short {@code s} → the chief origin. */
    private static void posAt(double[] s, int stride, double t, double[] out3) {
        if (s == null || stride < 4 || s.length < stride) {
            out3[0] = out3[1] = out3[2] = 0.0;
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
            int b = (n - 1) * stride;
            out3[0] = s[b + 1];
            out3[1] = s[b + 2];
            out3[2] = s[b + 3];
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
