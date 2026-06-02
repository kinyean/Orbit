// Constellation classification by OBJECT_NAME prefix.
//
// Phase 2 reality: CelesTrak's per-constellation GROUP endpoints (the
// authoritative membership source per Decision 17) are firewall-blocked, so
// we classify by name prefix against the active catalog. This is the pragmatic
// reachable approach; Decision 17's group-based membership returns when a
// reachable group source exists.

export const CONSTELLATIONS = ['Starlink', 'OneWeb', 'GPS', 'Galileo', 'BeiDou', 'Iridium'] as const;

export type Constellation = (typeof CONSTELLATIONS)[number];

/** Map a satellite name to its constellation, or null if it isn't in one. */
export function constellationOf(name: string): Constellation | null {
  const n = name.toUpperCase();
  if (n.startsWith('STARLINK')) return 'Starlink';
  if (n.startsWith('ONEWEB')) return 'OneWeb';
  if (n.startsWith('GPS') || n.startsWith('NAVSTAR')) return 'GPS';
  if (n.startsWith('GALILEO')) return 'Galileo';
  if (n.startsWith('BEIDOU')) return 'BeiDou';
  if (n.includes('IRIDIUM')) return 'Iridium';
  return null;
}
