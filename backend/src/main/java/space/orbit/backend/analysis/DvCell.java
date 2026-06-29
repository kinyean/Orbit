package space.orbit.backend.analysis;

/**
 * One cell of the rendezvous arrival-time × revolution ΔV map (Phase 9A): the
 * two-body Lambert cost of departing at the scenario start and arriving at the chief
 * at {@code arrivalEpoch} after {@code nRev} revolutions. A coarse <em>selector</em> —
 * the chosen cell feeds the differential corrector ({@link space.orbit.backend.scenario.RendezvousCorrector}).
 *
 * @param arrivalEpoch ISO-8601 UTC arrival instant
 * @param nRev         revolution count of the transfer
 * @param dv1Ms        departure ΔV magnitude (m/s)
 * @param dv2Ms        arrival ΔV magnitude (m/s)
 * @param totalDvMs    {@code dv1Ms + dv2Ms} (m/s)
 */
public record DvCell(String arrivalEpoch, int nRev, double dv1Ms, double dv2Ms, double totalDvMs) {
}
