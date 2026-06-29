import * as THREE from 'three';
import type { MonteCarloResult } from '../store/useStore';

/**
 * Monte Carlo dispersion overlay (Phase 9C, UC-6, US-MC-01/02): the deputy's trajectory
 * cloud (faint spaghetti) + the 3-σ covariance ellipsoid envelope along the path, in the
 * chief LVLH frame. Built from the static {@link MonteCarloResult} held in Zustand (not
 * the per-frame stream), mirroring the depth-tested geometry conventions of
 * {@link ./ribbons} (lines) and {@link ./sensors} (translucent volumes).
 */
export interface MonteCarloLayer {
  group: THREE.Group;
  dispose(): void;
}

const CLOUD_COLOR = 0x8aa0bd;
const ELLIPSOID_COLOR = 0x38bdf8;

/** Build the cloud + ellipsoid group from a result (caller adds {@code group} to the scene). */
export function buildMonteCarloLayer(result: MonteCarloResult): MonteCarloLayer {
  const group = new THREE.Group();
  const disposables: { dispose(): void }[] = [];

  // Trajectory cloud — one faint depth-tested polyline per returned sample track.
  const cloudMat = new THREE.LineBasicMaterial({
    color: CLOUD_COLOR, transparent: true, opacity: 0.16, depthWrite: false,
  });
  disposables.push(cloudMat);
  for (const track of result.tracks ?? []) {
    if (!track || track.length < 6) continue;
    const pos = new Float32Array(track.length);
    pos.set(track);
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    disposables.push(geo);
    group.add(new THREE.Line(geo, cloudMat));
  }

  // 3-σ covariance ellipsoids — one translucent shell per epoch; overlapping shells read
  // as the dispersion "tube." A single unit icosahedron is shared, scaled/oriented per epoch.
  const unit = new THREE.IcosahedronGeometry(1, 2);
  disposables.push(unit);
  const shellMat = new THREE.MeshBasicMaterial({
    color: ELLIPSOID_COLOR, transparent: true, opacity: 0.1, depthWrite: false,
  });
  disposables.push(shellMat);
  for (const e of result.ellipsoids ?? []) {
    const a = e.semiAxes3Sigma;
    const c = e.center;
    const q = e.quaternion;
    if (!a || !c) continue;
    const mesh = new THREE.Mesh(unit, shellMat);
    mesh.scale.set(Math.max(a[0] ?? 0.1, 0.1), Math.max(a[1] ?? 0.1, 0.1), Math.max(a[2] ?? 0.1, 0.1));
    if (q && q.length === 4) mesh.quaternion.set(q[0], q[1], q[2], q[3]);
    mesh.position.set(c[0], c[1], c[2]);
    group.add(mesh);
  }

  return {
    group,
    dispose() {
      for (const d of disposables) d.dispose();
      group.clear();
    },
  };
}
