import type { TLE } from '../types/satellite';

const BASE_URL = 'https://celestrak.org/NORAD/elements/gp.php';

export type CelestrakGroup =
  | 'active'
  | 'starlink'
  | 'oneweb'
  | 'gps-ops'
  | 'galileo'
  | 'beidou'
  | 'iridium'
  | 'geo'
  | 'stations';

export interface CelestrakRecord {
  OBJECT_NAME: string;
  NORAD_CAT_ID: number;
  EPOCH: string;
  TLE_LINE1: string;
  TLE_LINE2: string;
  MEAN_MOTION?: number;
  ECCENTRICITY?: number;
  INCLINATION?: number;
}

export async function fetchTLEGroup(
  group: CelestrakGroup = 'active'
): Promise<CelestrakRecord[]> {
  const url = `${BASE_URL}?GROUP=${group}&FORMAT=json`;
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`CelesTrak responded with ${response.status}`);
  }
  return response.json();
}

export function recordToTLE(r: CelestrakRecord): TLE {
  return {
    name: r.OBJECT_NAME,
    noradId: r.NORAD_CAT_ID,
    line1: r.TLE_LINE1,
    line2: r.TLE_LINE2,
  };
}
