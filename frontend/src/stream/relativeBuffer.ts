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
}

export interface RelativeFrameData {
  epochMs: number; // Date.parse(envelope.epoch) — the t=0 reference
  stepSeconds: number;
  frame: string; // "LVLH"
  chiefId: number;
  deputies: DeputyRelative[];
}

let current: RelativeFrameData | null = null;
let version = 0; // bumped on set/clear so ProximityView can rebuild its meshes

export function setRelativeData(data: RelativeFrameData): void {
  current = data;
  version++;
}

export function clearRelativeData(): void {
  if (current === null) return;
  current = null;
  version++;
}

export function getRelativeData(): RelativeFrameData | null {
  return current;
}

/** Bumped whenever the data is replaced/cleared — a cheap "rebuild meshes?" signal. */
export function getRelativeVersion(): number {
  return version;
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

/** Parse a `scenario-relative` envelope into buffer data; null if malformed. */
export function parseRelativeMessage(msg: ScenarioRelativeMessage): RelativeFrameData | null {
  const epochMs = Date.parse(String(msg.epoch));
  if (Number.isNaN(epochMs)) return null;
  const stride = typeof msg.stride === 'number' ? msg.stride : 4;
  const rawDeputies = Array.isArray(msg.deputies) ? msg.deputies : [];

  const deputies: DeputyRelative[] = [];
  for (const raw of rawDeputies) {
    const d = raw as { noradId?: unknown; name?: unknown; samples?: unknown };
    if (typeof d.noradId !== 'number' || !Array.isArray(d.samples)) continue;
    deputies.push({
      noradId: d.noradId,
      name: typeof d.name === 'string' ? d.name : `NORAD ${d.noradId}`,
      stride,
      hasVelocity: stride >= 7,
      samples: Float64Array.from(d.samples as number[]),
    });
  }

  return {
    epochMs,
    stepSeconds: typeof msg.stepSeconds === 'number' ? msg.stepSeconds : 30,
    frame: typeof msg.frame === 'string' ? msg.frame : 'LVLH',
    chiefId: typeof msg.chiefId === 'number' ? msg.chiefId : -1,
    deputies,
  };
}
