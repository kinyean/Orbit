// Earth backdrop + starfield for the proximity view (Phase 6 / US-PROX-05).
//
// In the chief-centered LVLH scene Earth lies along −R (nadir) at the chief's
// geocentric radius — supplied by the backend's additive `chiefRadiusM` field
// (Decision 23; falls back to a representative LEO radius when absent). We render
// a true-scale Earth sphere centered at (−Rc, 0, 0) so its near limb sits at the
// chief's altitude below the origin: a big curved horizon at LEO, a small distant
// disc at GEO — correct orientation context either way.
//
// Lighting is NON-PHYSICAL flat (ambient/hemisphere added by ProximityView): the
// real Sun vector / terminator arrives in Phase 8, so we do not imply one. No
// Earth texture ships (firewalled env, R6) — a procedural blue material + a faint
// atmosphere rim stand in; a real texture is a later drop-in. Modes: earth / stars
// / off (Frank's "pure space"). Earth is backface-culled and modest-poly — only
// the near limb is ever seen, so far-side z-fighting is invisible.

import * as THREE from 'three';

const EARTH_RADIUS_M = 6_371_000;
const FALLBACK_CHIEF_RADIUS_M = 6_771_000; // ~400 km LEO, used when the field is absent
const STAR_COUNT = 1500;
const STAR_SHELL_M = 1.5e8; // inside the camera far plane (2e8)

export type BackdropMode = 'earth' | 'stars' | 'off';

export interface EarthBackdrop {
  readonly root: THREE.Group;
  /** Place Earth's center at (−radiusM, 0, 0); ≤0 → representative LEO fallback. */
  setChiefRadius(radiusM: number): void;
  setMode(mode: BackdropMode): void;
  dispose(): void;
}

export function createEarthBackdrop(): EarthBackdrop {
  const root = new THREE.Group();
  const disposables: { dispose(): void }[] = [];
  const track = <T extends THREE.BufferGeometry | THREE.Material>(x: T): T => {
    disposables.push(x);
    return x;
  };

  // Earth (procedural) — its center moves along −X with the chief radius. A single
  // convex sphere only (no additive atmosphere shell — that shell z-fought the
  // surface at planetary depth and read as a flickering blue haze). The renderer's
  // logarithmic depth buffer keeps the limb crisp against the close-range scene.
  const earth = new THREE.Group();
  const earthMat = track(
    new THREE.MeshStandardMaterial({ color: 0x1a3a6b, roughness: 1, metalness: 0, emissive: 0x081a3a, emissiveIntensity: 0.6 }),
  );
  earth.add(new THREE.Mesh(track(new THREE.SphereGeometry(EARTH_RADIUS_M, 48, 32)), earthMat));
  earth.position.x = -FALLBACK_CHIEF_RADIUS_M;
  root.add(earth);

  // Starfield — a fixed scatter on a large shell (cosmetic; deterministic seed).
  const starPos = new Float32Array(STAR_COUNT * 3);
  let seed = 0x6d2b79f5;
  const rand = () => {
    // Small deterministic PRNG so the sky is stable across reloads/scrubs.
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  for (let i = 0; i < STAR_COUNT; i++) {
    const u = rand() * 2 - 1;
    const theta = rand() * Math.PI * 2;
    const r = Math.sqrt(1 - u * u);
    starPos[i * 3] = STAR_SHELL_M * r * Math.cos(theta);
    starPos[i * 3 + 1] = STAR_SHELL_M * r * Math.sin(theta);
    starPos[i * 3 + 2] = STAR_SHELL_M * u;
  }
  const starGeom = track(new THREE.BufferGeometry());
  starGeom.setAttribute('position', new THREE.BufferAttribute(starPos, 3));
  const starMat = track(new THREE.PointsMaterial({ color: 0xc8d4f0, size: 1.4, sizeAttenuation: false }));
  const stars = new THREE.Points(starGeom, starMat);
  root.add(stars);

  return {
    root,
    setChiefRadius(radiusM: number) {
      earth.position.x = -(radiusM > 0 ? radiusM : FALLBACK_CHIEF_RADIUS_M);
    },
    setMode(mode: BackdropMode) {
      earth.visible = mode === 'earth';
      stars.visible = mode !== 'off';
    },
    dispose() {
      for (const d of disposables) d.dispose();
    },
  };
}
