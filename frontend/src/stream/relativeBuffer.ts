// Relative-state buffer for the proximity view (Phase 4B).
//
// Per Decision 5, high-frequency ephemeris lives OUTSIDE Zustand. The single
// per-scenario WebSocket (owned by Globe) fills this module singleton via the
// stream's `onRelative` callback; ProximityView reads it each frame. One stream
// serves both viewports — never open a second socket.
//
// LVLH samples are flat `[t,R,I,C, ...]` (stride 4) or `[t,R,I,C,vR,vI,vC, ...]`
// (stride 7), `t` seconds since `epoch`. R/I/C metres; the proximity view maps
// R→+X, I→+Y, C→+Z (right-handed), 1 unit = 1 m.

import type { ScenarioRelativeMessage } from './ScenarioStreamClient';

export interface DeputyRelative {
  noradId: number;
  name: string;
  stride: number; // 4 or 7
  hasVelocity: boolean;
  samples: Float64Array; // [t,R,I,C,(vR,vI,vC), ...]
  tcaEpochMs: number | null; // closest approach to the chief (Phase 5A); null if absent
  tcaDistanceM: number | null; // chief-relative range at TCA, metres
}

export interface RelativeFrameData {
  epochMs: number; // Date.parse(envelope.epoch) — the t=0 reference
  stepSeconds: number;
  frame: string; // "LVLH"
  chiefId: number;
  deputies: DeputyRelative[];
  // CW validity hints (Phase 5C): the client warns when a "cw" scenario exceeds
  // the linearization's small-separation / near-circular envelope.
  fidelity: string;
  maxSeparationM: number;
  chiefEccentricity: number;
  // Chief geocentric radius (metres, Phase 6 / US-PROX-05): the proximity view
  // places the Earth backdrop at (−chiefRadiusM, 0, 0) in the LVLH scene. 0 when
  // absent (older backend) → the view falls back to a representative LEO radius.
  chiefRadiusM: number;
}

let current: RelativeFrameData | null = null;
let version = 0; // bumped on set/clear so ProximityView can rebuild its meshes

// Subscribers (e.g. RelativeReadout, Timeline) — these live outside Zustand
// (Decision 5), so they get a tiny store-shaped subscribe/getSnapshot pair to
// drive React's useSyncExternalStore when the deputy set changes.
const listeners = new Set<() => void>();

function emit(): void {
  for (const l of listeners) l();
}

export function setRelativeData(data: RelativeFrameData): void {
  current = data;
  version++;
  emit();
}

export function clearRelativeData(): void {
  if (current === null) return;
  current = null;
  version++;
  emit();
}

export function getRelativeData(): RelativeFrameData | null {
  return current;
}

/** Bumped whenever the data is replaced/cleared — a cheap "rebuild meshes?" signal. */
export function getRelativeVersion(): number {
  return version;
}

/** Subscribe to data replacement/clear (for useSyncExternalStore). */
export function subscribeRelative(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Seconds since the frame epoch for a sim time. */
export function simTimeToT(epochMs: number, currentTime: Date): number {
  return (currentTime.getTime() - epochMs) / 1000;
}

/**
 * Write the deputy's [R, I, C] (metres) at time `t` (seconds since epoch) into
 * `out`. Linear interpolation between the bracketing samples; HOLD-clamps at the
 * ends (matching the CZML forward/backward HOLD so both views agree at edges).
 * Allocation-free — runs per deputy per frame.
 */
export function deputyPositionAt(
  samples: Float64Array,
  stride: number,
  t: number,
  out: [number, number, number],
): void {
  const n = Math.floor(samples.length / stride);
  if (n === 0) {
    out[0] = out[1] = out[2] = 0;
    return;
  }
  const tFirst = samples[0];
  const tLast = samples[(n - 1) * stride];
  if (t <= tFirst) {
    out[0] = samples[1];
    out[1] = samples[2];
    out[2] = samples[3];
    return;
  }
  if (t >= tLast) {
    const b = (n - 1) * stride;
    out[0] = samples[b + 1];
    out[1] = samples[b + 2];
    out[2] = samples[b + 3];
    return;
  }
  // Binary search for the last sample with time <= t.
  let lo = 0;
  let hi = n - 1;
  while (lo + 1 < hi) {
    const mid = (lo + hi) >> 1;
    if (samples[mid * stride] <= t) lo = mid;
    else hi = mid;
  }
  const ba = lo * stride;
  const bb = hi * stride;
  const ta = samples[ba];
  const tb = samples[bb];
  const f = tb > ta ? (t - ta) / (tb - ta) : 0;
  out[0] = samples[ba + 1] + (samples[bb + 1] - samples[ba + 1]) * f;
  out[1] = samples[ba + 2] + (samples[bb + 2] - samples[ba + 2]) * f;
  out[2] = samples[ba + 3] + (samples[bb + 3] - samples[ba + 3]) * f;
}

/**
 * Write the deputy's full relative state at time `t` into `out6`:
 * `[R, I, C, vR, vI, vC]` (metres, m/s). Velocities are 0 when the stride is 4.
 * Linear interpolation with end HOLD-clamp, mirroring {@link deputyPositionAt}.
 * Allocation-free — safe to call per deputy per frame.
 */
export function deputyStateAt(
  samples: Float64Array,
  stride: number,
  hasVelocity: boolean,
  t: number,
  out6: Float64Array | number[],
): void {
  out6[3] = out6[4] = out6[5] = 0;
  const n = Math.floor(samples.length / stride);
  if (n === 0) {
    out6[0] = out6[1] = out6[2] = 0;
    return;
  }
  const tFirst = samples[0];
  const tLast = samples[(n - 1) * stride];
  if (t <= tFirst || t >= tLast || n === 1) {
    const b = t <= tFirst ? 0 : (n - 1) * stride;
    out6[0] = samples[b + 1];
    out6[1] = samples[b + 2];
    out6[2] = samples[b + 3];
    if (hasVelocity && stride >= 7) {
      out6[3] = samples[b + 4];
      out6[4] = samples[b + 5];
      out6[5] = samples[b + 6];
    }
    return;
  }
  let lo = 0;
  let hi = n - 1;
  while (lo + 1 < hi) {
    const mid = (lo + hi) >> 1;
    if (samples[mid * stride] <= t) lo = mid;
    else hi = mid;
  }
  const ba = lo * stride;
  const bb = hi * stride;
  const ta = samples[ba];
  const tb = samples[bb];
  const f = tb > ta ? (t - ta) / (tb - ta) : 0;
  out6[0] = samples[ba + 1] + (samples[bb + 1] - samples[ba + 1]) * f;
  out6[1] = samples[ba + 2] + (samples[bb + 2] - samples[ba + 2]) * f;
  out6[2] = samples[ba + 3] + (samples[bb + 3] - samples[ba + 3]) * f;
  if (hasVelocity && stride >= 7) {
    out6[3] = samples[ba + 4] + (samples[bb + 4] - samples[ba + 4]) * f;
    out6[4] = samples[ba + 5] + (samples[bb + 5] - samples[ba + 5]) * f;
    out6[5] = samples[ba + 6] + (samples[bb + 6] - samples[ba + 6]) * f;
  }
}

/** Parse a `scenario-relative` envelope into buffer data; null if malformed. */
export function parseRelativeMessage(msg: ScenarioRelativeMessage): RelativeFrameData | null {
  const epochMs = Date.parse(String(msg.epoch));
  if (Number.isNaN(epochMs)) return null;
  const stride = typeof msg.stride === 'number' ? msg.stride : 4;
  const rawDeputies = Array.isArray(msg.deputies) ? msg.deputies : [];

  const deputies: DeputyRelative[] = [];
  for (const raw of rawDeputies) {
    const d = raw as {
      noradId?: unknown;
      name?: unknown;
      samples?: unknown;
      tcaEpoch?: unknown;
      tcaDistanceM?: unknown;
    };
    if (typeof d.noradId !== 'number' || !Array.isArray(d.samples)) continue;
    const tcaMs = typeof d.tcaEpoch === 'string' ? Date.parse(d.tcaEpoch) : NaN;
    deputies.push({
      noradId: d.noradId,
      name: typeof d.name === 'string' ? d.name : `NORAD ${d.noradId}`,
      stride,
      hasVelocity: stride >= 7,
      samples: Float64Array.from(d.samples as number[]),
      tcaEpochMs: Number.isNaN(tcaMs) ? null : tcaMs,
      tcaDistanceM: typeof d.tcaDistanceM === 'number' ? d.tcaDistanceM : null,
    });
  }

  return {
    epochMs,
    stepSeconds: typeof msg.stepSeconds === 'number' ? msg.stepSeconds : 30,
    frame: typeof msg.frame === 'string' ? msg.frame : 'LVLH',
    chiefId: typeof msg.chiefId === 'number' ? msg.chiefId : -1,
    deputies,
    fidelity: typeof msg.fidelity === 'string' ? msg.fidelity : 'sgp4',
    maxSeparationM: typeof msg.maxSeparationM === 'number' ? msg.maxSeparationM : 0,
    chiefEccentricity: typeof msg.chiefEccentricity === 'number' ? msg.chiefEccentricity : 0,
    chiefRadiusM: typeof msg.chiefRadiusM === 'number' ? msg.chiefRadiusM : 0,
  };
}
