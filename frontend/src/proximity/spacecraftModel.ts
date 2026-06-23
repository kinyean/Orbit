// Procedural spacecraft model for the proximity view (Phase 6 / US-PROX-01, -02).
//
// R6 (model sourcing): we ship a generic procedural craft — box bus + two solar
// arrays on named hinge joints + a dish on a gimbal — and ALSO wire a GLTFLoader
// path that swaps in a real `.glb` from `/public/models/spacecraft.glb` when one
// is dropped in (falling back to the primitive otherwise). Never blocked on art.
//
// Articulation is a static DEPLOYED pose (Phase 6 decision): the joints exist with
// a rotation API ready for Phase 7 attitude / Phase 8 sun-tracking, but nothing
// drives them yet — implying motion/physics we don't have would mislead.
//
// Scale: 1 unit = 1 m. Across the 1 m–100 km range a model is sub-pixel when far,
// so a fixed-pixel `marker` child is the far-LOD representation (identical to the
// pre-Phase-6 dots); the geometry fades in as you zoom (ProximityView drives it).
// Body axes: +Y nose/ram, +Z up/anti-nadir, ±X array boom (see orientation.ts).

import * as THREE from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';

export interface SpacecraftModel {
  /** Positioned + oriented each frame by ProximityView. */
  readonly root: THREE.Group;
  /** Named articulable joints (parked at the deployed pose; API ready for Phase 7). */
  readonly joints: { arrayPort: THREE.Group; arrayStarboard: THREE.Group; dish: THREE.Group };
  /** Approximate model radius (m) for LOD / near-clamp math. */
  readonly radius: number;
  setMarkerOpacity(a: number): void;
  setModelVisible(v: boolean): void;
  setMarkerVisible(v: boolean): void;
  /**
   * Show/hide the body-axis triad (red +X / green +Y / blue +Z). Rides the body
   * orientation (child of {@code root}), so it's the zoom-independent read of which
   * way the craft points — the model itself is too symmetric to read at a glance,
   * and a far craft is just the marker dot (Phase 7 / measured-attitude slice 2).
   */
  setAxesVisible(v: boolean): void;
  /**
   * Set the triad's world length (metres). The view drives this from the camera
   * distance so the triad keeps a roughly constant on-screen size — otherwise a
   * fixed-length triad vanishes when the camera zooms out to fit a km-scale FOV cone.
   */
  setAxesWorldLength(len: number): void;
  /** Uniform scale on the geometry only (the marker stays fixed-pixel). */
  setModelScale(s: number): void;
  dispose(): void;
}

const MODEL_URL = '/models/spacecraft.glb';
const MODEL_RADIUS = 10; // ~half the deployed array span

// Load the optional GLTF template once (shared across all craft). Resolves to the
// loaded scene, or null when the file is absent/unparseable — the procedural
// fallback then stands. (No `.glb` ships in the repo; this is the swap seam.)
let templatePromise: Promise<THREE.Object3D | null> | undefined;
function loadTemplate(): Promise<THREE.Object3D | null> {
  if (!templatePromise) {
    templatePromise = new GLTFLoader()
      .loadAsync(MODEL_URL)
      .then((g) => g.scene)
      .catch(() => null);
  }
  return templatePromise;
}

/** Build a spacecraft model tinted with `color` (chief amber / deputy palette slot). */
export function createSpacecraftModel(color: THREE.Color): SpacecraftModel {
  const root = new THREE.Group();
  const modelGroup = new THREE.Group(); // the geometry (scaled for near-clamp)
  root.add(modelGroup);

  const disposables: { dispose(): void }[] = [];
  const track = <T extends THREE.BufferGeometry | THREE.Material>(x: T): T => {
    disposables.push(x);
    return x;
  };

  // --- Bus -----------------------------------------------------------------
  const busMat = track(
    new THREE.MeshStandardMaterial({ color: color.clone().multiplyScalar(0.75), metalness: 0.3, roughness: 0.6 }),
  );
  const bus = new THREE.Mesh(track(new THREE.BoxGeometry(2.6, 3.6, 2.2)), busMat);
  modelGroup.add(bus);

  // --- Solar arrays on hinge joints (deployed along ±X) --------------------
  const panelMat = track(
    new THREE.MeshStandardMaterial({
      color: 0x1b2a55,
      emissive: 0x16306b,
      emissiveIntensity: 0.4,
      metalness: 0.1,
      roughness: 0.5,
      side: THREE.DoubleSide,
    }),
  );
  const panelGeom = track(new THREE.BoxGeometry(7.5, 0.06, 2.4));
  const makeArray = (sign: 1 | -1): THREE.Group => {
    const hinge = new THREE.Group();
    hinge.position.set(sign * 1.4, 0, 0); // bus side face
    const panel = new THREE.Mesh(panelGeom, panelMat);
    panel.position.set(sign * 4.1, 0, 0); // inner edge meets the hinge
    hinge.add(panel);
    modelGroup.add(hinge);
    return hinge;
  };
  const arrayPort = makeArray(-1);
  const arrayStarboard = makeArray(1);

  // --- Dish on a gimbal joint (parked pointing +Z, anti-nadir) -------------
  const dish = new THREE.Group();
  dish.position.set(0, 0, 1.3);
  const dishMat = track(new THREE.MeshStandardMaterial({ color: 0xb8c0cc, metalness: 0.4, roughness: 0.5, side: THREE.DoubleSide }));
  const dishMesh = new THREE.Mesh(track(new THREE.ConeGeometry(0.9, 0.5, 20, 1, true)), dishMat);
  dishMesh.rotation.x = -Math.PI / 2; // open face toward +Z
  dishMesh.position.z = 0.25;
  dish.add(dishMesh);
  modelGroup.add(dish);

  // --- Body-axis triad (orientation read; off by default) ------------------
  // A child of `root` (NOT modelGroup) so it rides the body orientation but is
  // independent of the model's near-plane scale clamp — a stable gnomon. Red +X,
  // green +Y (nose/ram), blue +Z (top/anti-nadir). ~2.5× the model radius so it
  // pokes clearly out of the bus.
  const AXES_BASE_LEN = MODEL_RADIUS * 2.5;
  const axes = new THREE.AxesHelper(AXES_BASE_LEN);
  axes.visible = false;
  (axes.material as THREE.Material).depthTest = false; // always legible over the bus
  axes.renderOrder = 2;
  root.add(axes);

  // --- Far-LOD marker: a single fixed-pixel point (the pre-Phase-6 dot) -----
  const markerGeom = track(new THREE.BufferGeometry());
  markerGeom.setAttribute('position', new THREE.BufferAttribute(new Float32Array([0, 0, 0]), 3));
  const markerMat = track(
    new THREE.PointsMaterial({ color, size: 9, sizeAttenuation: false, transparent: true }),
  );
  const marker = new THREE.Points(markerGeom, markerMat);
  root.add(marker);

  // --- Optional GLTF swap (R6 seam) ----------------------------------------
  let disposed = false;
  let swapped: THREE.Object3D | null = null;
  loadTemplate().then((tpl) => {
    if (disposed || !tpl) return;
    // Hide the primitives, show the real model. Its own materials/articulation
    // rig wiring is a Phase 7+ refinement; here we just display it.
    bus.visible = false;
    arrayPort.visible = false;
    arrayStarboard.visible = false;
    dish.visible = false;
    swapped = tpl.clone(true);
    modelGroup.add(swapped);
  });

  return {
    root,
    joints: { arrayPort, arrayStarboard, dish },
    radius: MODEL_RADIUS,
    setMarkerOpacity(a: number) {
      markerMat.opacity = a;
    },
    setModelVisible(v: boolean) {
      modelGroup.visible = v;
    },
    setMarkerVisible(v: boolean) {
      marker.visible = v;
    },
    setAxesVisible(v: boolean) {
      axes.visible = v;
    },
    setAxesWorldLength(len: number) {
      axes.scale.setScalar(len / AXES_BASE_LEN);
    },
    setModelScale(s: number) {
      modelGroup.scale.setScalar(s);
    },
    dispose() {
      disposed = true;
      axes.dispose();
      if (swapped) {
        swapped.traverse((o) => {
          const mesh = o as THREE.Mesh;
          if (mesh.geometry) mesh.geometry.dispose();
          const mat = mesh.material;
          if (Array.isArray(mat)) mat.forEach((m) => m.dispose());
          else if (mat) (mat as THREE.Material).dispose();
        });
      }
      for (const d of disposables) d.dispose();
    },
  };
}
