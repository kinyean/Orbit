// Trajectory ribbons for the proximity view (Phase 6 / US-PROX-03; SRS §3.9.5).
//
// The full past+future LVLH path is already buffered client-side (relativeBuffer's
// `samples` — Decision 9, the frontend never propagates), so a ribbon is that
// polyline split at the current time: past = solid, predicted = dashed.
//
// Two shaping steps make it read as a clean trail:
//   1. WINDOW — show only ±WINDOW_SECONDS around "now" (a multi-orbit span plotted
//      whole is an unreadable spirograph that smears over the Earth — the same
//      lesson as the DistanceChart's windowing, Decision 22). Selected per frame via
//      `BufferGeometry.setDrawRange` (allocation-free; geometry built once).
//   2. SMOOTH — long scenarios are sampled coarsely (the R8 cap raises the step, e.g.
//      ~190 s → ~28 points/orbit), so the raw polyline is visibly faceted ("cutting
//      off, not smooth"). We densify with a Catmull-Rom spline toward ~30 s spacing
//      so the curve reads smoothly regardless of the backend sample step.
//
// Plain depth-tested `THREE.Line` (not the fat `Line2`): it respects the renderer's
// logarithmic depth buffer, so the Earth occludes correctly and the trail no longer
// paints over the planet.

import * as THREE from 'three';

const WINDOW_SECONDS = 7200; // ±window around "now" (~1.3 LEO orbits each side)
const TARGET_STEP_SECONDS = 30; // densify coarse sampling toward this for smooth curves
const MAX_SUBDIV = 6; // cap the spline densification per coarse segment

export interface Ribbon {
  readonly past: THREE.Line;
  readonly predicted: THREE.Line;
  /** Window the trail to sim-time `t` (seconds since the frame epoch). */
  setSplit(t: number): void;
  dispose(): void;
}

/** Uniform Catmull-Rom interpolation on one axis at parameter u∈[0,1]. */
function catmullRom(p0: number, p1: number, p2: number, p3: number, u: number): number {
  const u2 = u * u;
  const u3 = u2 * u;
  return 0.5 * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
}

/**
 * Build a ribbon for a deputy from its flat `samples` (`[t,R,I,C,(v…)]`, stride 4
 * or 7). `color` is the deputy's locked palette slot (matches the marker + globe).
 */
export function createRibbon(samples: Float64Array, stride: number, color: THREE.Color): Ribbon {
  const n = Math.floor(samples.length / stride);

  // Coarse points (R→x, I→y, C→z) + their times.
  const cx = new Float64Array(Math.max(1, n));
  const cy = new Float64Array(Math.max(1, n));
  const cz = new Float64Array(Math.max(1, n));
  const ct = new Float64Array(Math.max(1, n));
  for (let i = 0; i < n; i++) {
    const b = i * stride;
    ct[i] = samples[b];
    cx[i] = samples[b + 1];
    cy[i] = samples[b + 2];
    cz[i] = samples[b + 3];
  }

  // Densify with a Catmull-Rom spline when the sampling is coarse, so the curve is
  // smooth; `times` stays monotonic (linear within each coarse segment) for windowing.
  const step = n > 1 ? ct[1] - ct[0] : TARGET_STEP_SECONDS;
  const subdiv = Math.max(1, Math.min(MAX_SUBDIV, Math.round((step > 0 ? step : TARGET_STEP_SECONDS) / TARGET_STEP_SECONDS)));

  let times: Float64Array;
  let xyz: Float32Array;
  let dn: number; // dense point count
  if (subdiv <= 1 || n < 3) {
    dn = Math.max(1, n);
    times = ct;
    xyz = new Float32Array(Math.max(2, n) * 3);
    for (let i = 0; i < n; i++) {
      xyz[i * 3] = cx[i];
      xyz[i * 3 + 1] = cy[i];
      xyz[i * 3 + 2] = cz[i];
    }
  } else {
    dn = (n - 1) * subdiv + 1;
    times = new Float64Array(dn);
    xyz = new Float32Array(dn * 3);
    let w = 0;
    for (let i = 0; i < n - 1; i++) {
      const i0 = Math.max(0, i - 1);
      const i3 = Math.min(n - 1, i + 2);
      for (let s = 0; s < subdiv; s++) {
        const u = s / subdiv;
        xyz[w * 3] = catmullRom(cx[i0], cx[i], cx[i + 1], cx[i3], u);
        xyz[w * 3 + 1] = catmullRom(cy[i0], cy[i], cy[i + 1], cy[i3], u);
        xyz[w * 3 + 2] = catmullRom(cz[i0], cz[i], cz[i + 1], cz[i3], u);
        times[w] = ct[i] + (ct[i + 1] - ct[i]) * u;
        w++;
      }
    }
    xyz[w * 3] = cx[n - 1];
    xyz[w * 3 + 1] = cy[n - 1];
    xyz[w * 3 + 2] = cz[n - 1];
    times[w] = ct[n - 1];
  }

  // Separate geometries so each carries its own drawRange (it's a geometry, not a
  // line, property), but they share one position buffer to halve memory.
  const posAttr = new THREE.BufferAttribute(xyz, 3);
  const pastGeom = new THREE.BufferGeometry();
  pastGeom.setAttribute('position', posAttr);
  const predGeom = new THREE.BufferGeometry();
  predGeom.setAttribute('position', posAttr);

  const pastMat = new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.95 });
  const predMat = new THREE.LineDashedMaterial({ color, transparent: true, opacity: 0.6 });

  const past = new THREE.Line(pastGeom, pastMat);
  past.frustumCulled = false; // the polyline spans the whole window; don't cull
  const predicted = new THREE.Line(predGeom, predMat);
  predicted.frustumCulled = false;
  predicted.computeLineDistances(); // dashes (static geometry → compute once)
  // Dash size ~1.5 dense segments so dashes read at any sampling.
  const seg = predicted.geometry.attributes.lineDistance;
  if (seg && seg.count > 1) {
    const dist = seg.getX(seg.count - 1);
    const d = Math.max(1, (dist / Math.max(1, dn - 1)) * 1.5);
    predMat.dashSize = d;
    predMat.gapSize = d;
  }

  pastGeom.setDrawRange(0, 0);
  predGeom.setDrawRange(0, 0);

  const idxAtOrBefore = (t: number): number => {
    if (t <= times[0]) return 0;
    if (t >= times[dn - 1]) return dn - 1;
    let lo = 0;
    let hi = dn - 1;
    while (lo + 1 < hi) {
      const mid = (lo + hi) >> 1;
      if (times[mid] <= t) lo = mid;
      else hi = mid;
    }
    return lo;
  };
  const idxAtOrAfter = (t: number): number => {
    if (t <= times[0]) return 0;
    if (t >= times[dn - 1]) return dn - 1;
    let lo = 0;
    let hi = dn - 1;
    while (lo + 1 < hi) {
      const mid = (lo + hi) >> 1;
      if (times[mid] < t) lo = mid;
      else hi = mid;
    }
    return hi;
  };

  return {
    past,
    predicted,
    setSplit(t: number) {
      if (dn < 2) return;
      const now = idxAtOrBefore(t);
      const start = idxAtOrAfter(t - WINDOW_SECONDS);
      const end = idxAtOrBefore(t + WINDOW_SECONDS);
      const pastCount = now - start + 1; // [start .. now]
      pastGeom.setDrawRange(start, pastCount >= 2 ? pastCount : 0);
      const predCount = end - now + 1; // [now .. end] (shares the `now` vertex)
      predGeom.setDrawRange(now, predCount >= 2 ? predCount : 0);
    },
    dispose() {
      pastGeom.dispose();
      predGeom.dispose();
      pastMat.dispose();
      predMat.dispose();
    },
  };
}
