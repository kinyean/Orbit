export interface TLE {
  name: string;
  noradId: number;
  line1: string;
  line2: string;
}

export interface SatellitePosition {
  noradId: number;
  latitude: number;
  longitude: number;
  altitude: number;
  timestamp: number;
}

export type ObjectType = 'PAYLOAD' | 'DEBRIS' | 'ROCKET_BODY' | 'UNKNOWN';

export interface Satellite {
  noradId: number;
  name: string;
  objectType: ObjectType;
  country?: string;
  launchDate?: string;
  tle: TLE;
}
