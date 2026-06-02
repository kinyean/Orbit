package space.orbit.backend.prop;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.Frame;
import org.orekit.time.AbsoluteDate;

/**
 * A position + velocity state, explicitly tagged with its reference frame
 * (Decision 12, R15: a bare position/velocity tuple flowing through frame
 * math is the dominant error class in this domain — the {@link Frame} tag
 * makes a mismatch a visible field, not a silent bug).
 *
 * <p>Position in metres, velocity in m/s, both in {@link #frame()} at
 * {@link #date()}.
 */
public record StateVector(Vector3D position, Vector3D velocity, AbsoluteDate date, Frame frame) {}
