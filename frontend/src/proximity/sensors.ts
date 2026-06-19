// Sensor field-of-view volumes for the proximity view (Phase 7 / US-SENSE-02).
//
// A sensor is a body-fixed camera/RF/lidar: a translucent FOV volume projecting
// from the spacecraft along its boresight, out to the sensor's max range. The
// volume is attached under the craft's `root` (sibling of the model geometry), so
// it inherits the craft's position + modeled body orientation for free — and it
// stays in true metres (the near-clamp model scale never touches it).
//
// FOV-local frame: +Y is the boresight; the volume opens from the apex (at the
// craft) toward +Y. The group is rotated so local +Y aligns with the sensor's
// `mount.boresightBody` axis (default body +X). Cone (circular) and rect (a
// rectangular pyramid from the H×V half-angles) are supported (Decision 24).

import * as THREE from 'three';
import type { SensorDef } from '../stream/relativeBuffer';

export interface SensorFov {
  /** Attach under the host craft's `root`; inherits its body orientation. */
  readonly root: THREE.Group;
  setVisible(v: boolean): void;
  setOpacity(a: number): void;
  /** Light the volume green while the sensor currently has a target acquired (US-EVT-01). */
  setAcquired(on: boolean): void;
  dispose(): void;
}

/** "Target in view" highlight — same green as the timeline AOS/LOS bands. */
const ACQUIRED_COLOR = new THREE.Color(0x22c55e);

const FALLBACK_RANGE_M = 1000;

/** Build a translucent FOV volume for `def`, tinted `color` (the host's palette slot). */
export function createSensorFov(def: SensorDef, color: THREE.Color): SensorFov {
  const root = new THREE.Group();

  // Orient FOV-local +Y onto the boresight axis (body frame).
  const boresight = new THREE.Vector3(
    def.mount.boresightBody[0], def.mount.boresightBody[1], def.mount.boresightBody[2]);
  if (boresight.lengthSq() === 0) boresight.set(1, 0, 0);
  boresight.normalize();
  root.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), boresight);

  const range = def.maxRangeM > 0 ? def.maxRangeM : FALLBACK_RANGE_M;
  const fillMat = new THREE.MeshBasicMaterial({
    color,
    transparent: true,
    opacity: 0.15,
    side: THREE.DoubleSide,
    depthWrite: false, // translucent — composite over solids without writing depth
  });
  const edgeMat = new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.55 });

  const geom = def.fov.type === 'rect'
    ? rectPyramid(range, def.fov.hDeg, def.fov.vDeg)
    : coneVolume(range, def.fov.halfAngleDeg);
  const fill = new THREE.Mesh(geom, fillMat);
  root.add(fill);

  const edgeGeom = new THREE.EdgesGeometry(geom, 30);
  const edges = new THREE.LineSegments(edgeGeom, edgeMat);
  root.add(edges);

  // Style state: the volume is the craft color at the base opacity normally, and turns
  // green + brighter while the sensor has a target acquired — so "in view" is unambiguous
  // (no need to eyeball the target's depth against the cone's far face).
  let baseOpacity = 0.15;
  let acquired = false;
  const applyStyle = () => {
    fillMat.color.copy(acquired ? ACQUIRED_COLOR : color);
    edgeMat.color.copy(acquired ? ACQUIRED_COLOR : color);
    fillMat.opacity = acquired ? Math.min(0.5, baseOpacity * 2.5) : baseOpacity;
    edgeMat.opacity = acquired ? 0.95 : Math.min(1, baseOpacity * 3);
  };
  applyStyle();

  return {
    root,
    setVisible(v: boolean) {
      root.visible = v;
    },
    setOpacity(a: number) {
      baseOpacity = a;
      applyStyle();
    },
    setAcquired(on: boolean) {
      if (on !== acquired) {
        acquired = on;
        applyStyle();
      }
    },
    dispose() {
      geom.dispose();
      edgeGeom.dispose();
      fillMat.dispose();
      edgeMat.dispose();
    },
  };
}

/**
 * Spherical-sector ("ice-cream cone") of half-angle {@code halfAngleDeg}, apex at the
 * origin, opening along +Y, capped by a **spherical surface at {@code range}** — so every
 * point on the far boundary is exactly {@code range} away. This matches the acquisition
 * predicate (angle ≤ half-angle ∧ straight-line range ≤ maxRange); a flat-based cone would
 * over-represent range at its rim (at 60° the rim reaches range/cos60° = 2× the max range)
 * and make out-of-range targets look enclosed. Built as a lathe of the profile
 * apex → cone wall → spherical cap arc, revolved around +Y.
 */
function coneVolume(range: number, halfAngleDeg: number): THREE.BufferGeometry {
  const half = THREE.MathUtils.degToRad(halfAngleDeg > 0 ? halfAngleDeg : 10);
  const pts: THREE.Vector2[] = [new THREE.Vector2(0, 0)]; // apex on the axis
  const arc = 24;
  for (let i = 0; i <= arc; i++) {
    const th = half * (1 - i / arc); // cap arc from the rim (θ=half) to the pole (θ=0)
    pts.push(new THREE.Vector2(range * Math.sin(th), range * Math.cos(th)));
  }
  // (x = radius from the axis, y = distance along +Y) revolved around +Y. The first
  // segment apex→rim is the straight cone wall; the rest is the spherical cap at `range`.
  return new THREE.LatheGeometry(pts, 48);
}

/** Rectangular pyramid (H×V full angles), apex at the origin, base at +Y = range. */
function rectPyramid(range: number, hDeg: number, vDeg: number): THREE.BufferGeometry {
  const hw = range * Math.tan(THREE.MathUtils.degToRad((hDeg > 0 ? hDeg : 20) / 2));
  const hh = range * Math.tan(THREE.MathUtils.degToRad((vDeg > 0 ? vDeg : 15) / 2));
  // FOV-local: +Y = boresight; X spans H, Z spans V. Apex at origin.
  const a = [0, 0, 0];
  const c0 = [-hw, range, -hh];
  const c1 = [hw, range, -hh];
  const c2 = [hw, range, hh];
  const c3 = [-hw, range, hh];
  const verts = new Float32Array([
    ...a, ...c0, ...c1, // four side faces
    ...a, ...c1, ...c2,
    ...a, ...c2, ...c3,
    ...a, ...c3, ...c0,
    ...c0, ...c1, ...c2, // base quad
    ...c0, ...c2, ...c3,
  ]);
  const geom = new THREE.BufferGeometry();
  geom.setAttribute('position', new THREE.BufferAttribute(verts, 3));
  geom.computeVertexNormals();
  return geom;
}
