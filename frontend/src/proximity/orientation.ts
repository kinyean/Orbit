// Spacecraft orientation for the proximity view (Phase 6 / US-PROX-01, -04).
//
// The relative-state stream carries NO attitude (that arrives in Phase 7), so we
// DERIVE a placeholder orientation from the streamed LVLH velocity:
//   - ram-pointing when velocity is present (stride 7): nose (+Y) along the
//     LVLH-frame velocity, top (+Z) toward radial-out (+R), side (+X) completes
//     the right-handed basis;
//   - a fixed LVLH pose when velocity is absent (stride 4) or for the chief (the
//     origin has no relative velocity): nose along +I (in-track), top along +R.
//
// This is an ESTIMATE, clearly labeled in the UI — not measured attitude. It also
// drives the body-frame cameras so there is one orientation source of truth.
//
// Scene axis map (matches ProximityView): R→+X, I→+Y, C→+Z (right-handed),
// 1 unit = 1 m. The model's body axes: +Y = nose/ram, +Z = up/anti-nadir,
// ±X = solar-array boom.

import * as THREE from 'three';

const nose = new THREE.Vector3();
const top = new THREE.Vector3();
const side = new THREE.Vector3();
const basis = new THREE.Matrix4();

const RADIAL_OUT = new THREE.Vector3(1, 0, 0); // +R in the scene
const IN_TRACK = new THREE.Vector3(0, 1, 0); // +I in the scene
const MIN_SPEED = 1e-6;

/**
 * Write the spacecraft's derived body orientation into `outQuat` from its relative
 * state `out6 = [R, I, C, vR, vI, vC]` (metres, m/s). Allocation-free.
 */
export function deriveBodyQuaternion(
  out6: Float64Array | number[],
  hasVelocity: boolean,
  outQuat: THREE.Quaternion,
): void {
  const speed = hasVelocity ? Math.hypot(out6[3], out6[4], out6[5]) : 0;
  if (hasVelocity && speed > MIN_SPEED) {
    // Ram-pointing: nose along velocity, top toward radial-out.
    nose.set(out6[3], out6[4], out6[5]).multiplyScalar(1 / speed);
    top.copy(RADIAL_OUT);
  } else {
    // Fixed LVLH pose (chief, or stride-4 deputies): nose along in-track.
    nose.copy(IN_TRACK);
    top.copy(RADIAL_OUT);
  }
  // Orthonormalize `top` against `nose`; guard the degenerate parallel case.
  top.addScaledVector(nose, -nose.dot(top));
  if (top.lengthSq() < 1e-9) {
    top.set(0, 0, 1); // velocity ~parallel to radial → use cross-track as up
    top.addScaledVector(nose, -nose.dot(top));
  }
  top.normalize();
  side.copy(nose).cross(top).normalize(); // +X (body) = nose × top
  // makeBasis(xAxis, yAxis, zAxis): local +X→side, +Y→nose, +Z→top.
  basis.makeBasis(side, nose, top);
  outQuat.setFromRotationMatrix(basis);
}
