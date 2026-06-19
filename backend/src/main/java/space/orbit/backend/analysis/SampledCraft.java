package space.orbit.backend.analysis;

import java.util.List;
import space.orbit.backend.scenario.ScenarioBody;

/**
 * A craft as seen by {@link SensorEventComputer}: its <em>already-sampled</em>
 * relative trajectory + body-attitude in the chief-LVLH scene frame, plus its
 * body-fixed sensors. Events are computed from these same samples that drive the
 * rendered scene — NOT a second propagation — so acquisition/loss windows are
 * consistent by construction with the drawn FOV and the closest-approach (Decision
 * 24; fixes the maneuvered-deputy re-propagation bug). Every craft is both a
 * potential sensor host and a potential target.
 *
 * @param noradId   NORAD id
 * @param pos       flat position samples in the chief-LVLH scene, {@code [t,R,I,C, ...]}
 *                  at {@code posStride} (4 or 7). {@code null} for the chief (it is the
 *                  LVLH origin — position is always (0,0,0)).
 * @param posStride sample stride of {@code pos} (4 = position only, 7 = with velocity)
 * @param att       body-orientation quaternion samples {@code [t,qx,qy,qz,qw, ...]}
 *                  (stride 5, three.js convention) in the chief-LVLH scene frame
 * @param sensors   this craft's body-fixed sensors (may be empty)
 */
public record SampledCraft(
        int noradId,
        double[] pos,
        int posStride,
        double[] att,
        List<ScenarioBody.Sensor> sensors) {
}
