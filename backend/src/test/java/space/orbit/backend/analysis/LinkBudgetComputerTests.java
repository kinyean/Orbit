package space.orbit.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;
import space.orbit.backend.scenario.ScenarioBody;

/**
 * {@link LinkBudgetComputer} (Phase 9D, US-EVT-05). Pure — no Orekit. Verifies the Friis
 * SNR model (6 dB per range-doubling), that a series is produced per (link-budget sensor,
 * target) on the sampled range, the gate when no sensor carries a budget, and determinism.
 */
class LinkBudgetComputerTests {

    private static ScenarioBody.Sensor sensorWithLink() {
        return new ScenarioBody.Sensor("s1", "rf", "dish",
                new ScenarioBody.Fov("cone", 10, 0, 0), 0.0, 1.0e12,
                new ScenarioBody.Mount(new double[] {1, 0, 0}, 0.0),
                new ScenarioBody.LinkBudget("rf", 20.0, 5.0, 2.2, 1.0e6, 10.0));
    }

    private static ScenarioBody.Sensor sensorNoLink() {
        return new ScenarioBody.Sensor("s2", "rf", "dish",
                new ScenarioBody.Fov("cone", 10, 0, 0), 0.0, 1.0e9,
                new ScenarioBody.Mount(new double[] {1, 0, 0}, 0.0)); // 7-arg → null link budget
    }

    @Test
    void snrFalls6dbPerRangeDoubling() {
        ScenarioBody.LinkBudget lb = sensorWithLink().linkBudget();
        double s1 = LinkBudgetComputer.snr(lb, 1.0e6);
        double s2 = LinkBudgetComputer.snr(lb, 2.0e6);
        assertThat(s1 - s2).isCloseTo(6.0206, within(0.01)); // 20·log10(2)
    }

    @Test
    void computesSeriesPerPairOnSampledRange() {
        SampledCraft chief = new SampledCraft(1, null, 4, new double[] {0, 0, 0, 0, 1}, List.of(sensorWithLink()));
        // Target fixed at (1000 km, 0, 0) → constant range 1e6 m from the chief origin.
        double[] pos = {0, 1.0e6, 0, 0, 600, 1.0e6, 0, 0};
        SampledCraft target = new SampledCraft(2, pos, 4, null, List.of());

        List<LinkBudgetSeries> out = new LinkBudgetComputer().compute(List.of(chief, target), 0, 300, 2);

        assertThat(out).hasSize(1);
        LinkBudgetSeries s = out.get(0);
        assertThat(s.hostNoradId()).isEqualTo(1);
        assertThat(s.targetNoradId()).isEqualTo(2);
        assertThat(s.thresholdDb()).isEqualTo(10.0);
        double expected = LinkBudgetComputer.snr(sensorWithLink().linkBudget(), 1.0e6);
        for (int i = 1; i < s.series().length; i += 2) {
            assertThat(s.series()[i]).isCloseTo(expected, within(1.0e-6));
        }
    }

    @Test
    void noSeriesWithoutALinkBudget() {
        SampledCraft chief = new SampledCraft(1, null, 4, new double[] {0, 0, 0, 0, 1}, List.of(sensorNoLink()));
        SampledCraft target = new SampledCraft(2, new double[] {0, 1.0e6, 0, 0}, 4, null, List.of());
        assertThat(new LinkBudgetComputer().compute(List.of(chief, target), 0, 300, 1)).isEmpty();
    }

    @Test
    void deterministic() {
        SampledCraft chief = new SampledCraft(1, null, 4, new double[] {0, 0, 0, 0, 1}, List.of(sensorWithLink()));
        double[] pos = {0, 5.0e5, 1.0e5, 0, 600, 6.0e5, 1.0e5, 0};
        SampledCraft target = new SampledCraft(2, pos, 4, null, List.of());
        double[] a = new LinkBudgetComputer().compute(List.of(chief, target), 0, 200, 3).get(0).series();
        double[] b = new LinkBudgetComputer().compute(List.of(chief, target), 0, 200, 3).get(0).series();
        assertThat(b).containsExactly(a);
    }
}
