// Shared display formatters for relative-state UI (readout + distance chart),
// so a distance reads identically wherever it appears.

/** Distance: km past 1 km (2 dp, or 0 dp past 100 km), else whole metres. */
export function fmtDistance(m: number): string {
  if (m >= 1000) return `${(m / 1000).toFixed(m >= 100_000 ? 0 : 2)} km`;
  return `${m.toFixed(0)} m`;
}
