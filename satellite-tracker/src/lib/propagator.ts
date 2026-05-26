import {
  twoline2satrec,
  propagate,
  gstime,
  eciToGeodetic,
  degreesLat,
  degreesLong,
} from 'satellite.js';
import type { TLE, SatellitePosition } from '../types/satellite';

export function propagateAtTime(tle: TLE, time: Date): SatellitePosition | null {
  const satrec = twoline2satrec(tle.line1, tle.line2);
  const posVel = propagate(satrec, time);

  if (!posVel.position || typeof posVel.position === 'boolean') {
    return null;
  }

  const gmst = gstime(time);
  const geodetic = eciToGeodetic(posVel.position, gmst);

  return {
    noradId: tle.noradId,
    latitude: degreesLat(geodetic.latitude),
    longitude: degreesLong(geodetic.longitude),
    altitude: geodetic.height,
    timestamp: time.getTime(),
  };
}
