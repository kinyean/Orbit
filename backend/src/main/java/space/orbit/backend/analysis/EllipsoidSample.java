package space.orbit.backend.analysis;

/**
 * A covariance (dispersion) ellipsoid of the deputy's relative-position cloud at one
 * epoch (Phase 9C, US-MC-02), in the chief LVLH frame. The 3×3 sample covariance is
 * eigendecomposed: {@code semiAxes} are σ-scaled √eigenvalues (the principal extents),
 * {@code quaternion} orients the principal axes (three.js {@code x,y,z,w}, matching the
 * streamed attitude convention). Both the 1-σ and 3-σ extents are carried (3-σ = 3×1-σ).
 *
 * @param tSeconds       seconds from the scenario epoch
 * @param center         mean relative position [x,y,z] (m, LVLH)
 * @param semiAxes1Sigma 1-σ principal semi-axes [a,b,c] (m)
 * @param semiAxes3Sigma 3-σ principal semi-axes [a,b,c] (m)
 * @param quaternion     principal-axes orientation [x,y,z,w]
 */
public record EllipsoidSample(
        double tSeconds,
        double[] center,
        double[] semiAxes1Sigma,
        double[] semiAxes3Sigma,
        double[] quaternion) {
}
