// Proximity-view camera modes (Phase 6 / US-PROX-04; SRS §3.9.8).
//
//   - external   : free orbit around the chief at the LVLH origin (the pre-Phase-6
//                  behavior).
//   - chief      : rides the chief's derived body frame — as the chief's ram
//                  direction rotates over the orbit, the view rotates with it.
//   - deputy     : follows a selected deputy (target re-centers on it) AND rides
//                  its body frame.
//
// One camera + one OrbitControls throughout. In a body mode we keep the camera's
// offset-from-target expressed in the focus BODY frame and re-apply it each frame
// (`camera.position = target + bodyQuat · offsetBody`), then read the user's
// drag/zoom back into body space after `controls.update()` so orbiting still
// works. On a mode/focus switch we ease the target over ~0.4 s to avoid a jump.
//
// Body orientation comes from orientation.ts (a labeled estimate until Phase 7).
// Known trade-off: like the globe's ENU follow (Decision 18), a body ride can
// slowly roll over a long pass; acceptable, and switchable back to `external`.
// The switch easing uses `performance.now()` — presentation-only, decoupled from
// the sim clock, so it never violates the single-clock invariant (Decision 11).

import * as THREE from 'three';
import type { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';

export type CameraFocus = 'external' | 'chief' | 'deputy';
const TRANSITION_MS = 400;

export class CameraRig {
  private mode: CameraFocus = 'external';
  private offsetBody = new THREE.Vector3();
  private hasOffset = false;
  private transitioning = false;
  private transStart = 0;
  private transFrom = new THREE.Vector3();
  private transTo = new THREE.Vector3();
  private readonly tmpV = new THREE.Vector3();
  private readonly tmpQInv = new THREE.Quaternion();

  constructor(
    private readonly camera: THREE.PerspectiveCamera,
    private readonly controls: OrbitControls,
  ) {}

  /** Begin a transition to a new focus (or a new deputy under the same focus). */
  setFocus(mode: CameraFocus, focusPos: THREE.Vector3): void {
    this.mode = mode;
    this.transFrom.copy(this.controls.target);
    this.transTo.copy(focusPos);
    this.transStart = performance.now();
    this.transitioning = true;
    this.hasOffset = false; // re-init the body offset at the new focus (no jump)
  }

  /**
   * Per-frame update. `focusPos`/`focusQuat` are the current world position and
   * derived body orientation of the focused craft (origin/identity for external).
   */
  update(now: number, focusPos: THREE.Vector3, focusQuat: THREE.Quaternion): void {
    const { controls, camera } = this;

    // 1. Target tracks the focus (eased while transitioning).
    if (this.transitioning) {
      const f = Math.min(1, (now - this.transStart) / TRANSITION_MS);
      controls.target.copy(this.transFrom).lerp(this.transTo, easeInOut(f));
      if (f >= 1) this.transitioning = false;
    } else {
      controls.target.copy(focusPos);
    }

    // 2. Body ride (chief/deputy): place the camera from the body-frame offset.
    if (this.mode !== 'external') {
      if (!this.hasOffset) {
        this.tmpQInv.copy(focusQuat).invert();
        this.offsetBody.copy(camera.position).sub(controls.target).applyQuaternion(this.tmpQInv);
        this.hasOffset = true;
      }
      this.tmpV.copy(this.offsetBody).applyQuaternion(focusQuat);
      camera.position.copy(controls.target).add(this.tmpV);
    }

    controls.update(); // user orbit/zoom + damping, around the (moving) target

    // 3. Capture the user-driven offset back into the body frame so drag sticks.
    if (this.mode !== 'external' && this.hasOffset) {
      this.tmpQInv.copy(focusQuat).invert();
      this.offsetBody.copy(camera.position).sub(controls.target).applyQuaternion(this.tmpQInv);
    }
  }

  reset(): void {
    this.mode = 'external';
    this.transitioning = false;
    this.hasOffset = false;
  }
}

function easeInOut(t: number): number {
  return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
}
