import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { useStore } from '../store/useStore';
import {
  getRelativeData,
  getRelativeVersion,
  simTimeToT,
  deputyPositionAt,
  deputyStateAt,
  subscribeRelative,
  type SensorDef,
  type SensorEvent,
} from '../stream/relativeBuffer';
import { createSpacecraftModel, type SpacecraftModel } from '../proximity/spacecraftModel';
import { bodyOrientationAt } from '../proximity/orientation';
import { createRibbon, type Ribbon } from '../proximity/ribbons';
import { CameraRig, type CameraFocus } from '../proximity/cameraModes';
import { createEarthBackdrop, type BackdropMode } from '../proximity/earthBackdrop';
import { createSensorFov, type SensorFov } from '../proximity/sensors';

// Marker / model colors. Chief = amber (matches the globe's CHIEF_RGB); deputies
// cycle a palette matching the globe's SCENARIO_DEPUTY_PALETTE so a deputy is the
// same color in both views and across the model, marker, and ribbon.
const CHIEF_COLOR = new THREE.Color(0xffd166);
const DEPUTY_COLORS = [
  0x38bdf8, 0xff922b, 0xa3e635, 0xe879f9, 0x2dd4bf, 0xf472b6, 0x818cf8, 0xfacc15,
].map((h) => new THREE.Color(h));

// LOD crossfade (apparent model radius, CSS px): marker-only when far, model when
// near, crossfade between. Keeps the dots visible at 100 km (no regression).
const FADE_LO = 6;
const FADE_HI = 20;

function fmtDistance(m: number): string {
  return m >= 1000 ? `${(m / 1000).toFixed(m >= 100000 ? 0 : 1)} km` : `${m.toFixed(0)} m`;
}

/** Pair acquisition→loss events into in-view windows (ms) per sensorId (US-EVT-01). */
function buildSensorWindows(events: SensorEvent[], loMs: number, hiMs: number): Map<string, [number, number][]> {
  const byKey = new Map<string, SensorEvent[]>();
  for (const e of events) {
    const k = `${e.hostId}|${e.sensorId}|${e.targetId}`;
    (byKey.get(k) ?? byKey.set(k, []).get(k)!).push(e);
  }
  const out = new Map<string, [number, number][]>();
  const push = (sid: string, a: number, b: number) => (out.get(sid) ?? out.set(sid, []).get(sid)!).push([a, b]);
  for (const [k, list] of byKey) {
    const sensorId = k.split('|')[1];
    list.sort((a, b) => a.epochMs - b.epochMs);
    let open: number | null = null;
    for (const e of list) {
      if (e.type === 'acquisition') open = e.epochMs;
      else if (open !== null) {
        push(sensorId, open, e.epochMs);
        open = null;
      } else {
        push(sensorId, loMs, e.epochMs);
      }
    }
    if (open !== null) push(sensorId, open, hiMs);
  }
  return out;
}

function inWindow(wins: [number, number][] | undefined, t: number): boolean {
  if (!wins) return false;
  for (const [a, b] of wins) {
    if (t >= a && t <= b) return true;
  }
  return false;
}

/**
 * Proximity view (Phase 4B → Phase 6). A three.js scene in the chief's LVLH frame:
 * chief at the origin, deputies at their relative positions, animating in lockstep
 * with the global view via the ONE shared clock (it READS `store.currentTime` each
 * frame and never writes it — mirrors Globe's preRender). Axis map R→+X, I→+Y,
 * C→+Z; 1 scene unit = 1 m.
 *
 * Phase 6 (US-PROX-01..05): procedural spacecraft models (with a GLTF-swap seam)
 * replacing the bare points (a fixed-pixel marker is the far-LOD fallback);
 * derived ram/LVLH orientation (estimated — attitude is Phase 7); past/predicted
 * trajectory ribbons; chief-body / deputy-body / external camera modes; and an
 * Earth backdrop placed from the chief's geocentric radius. ΔV glyphs (Phase 5B)
 * and the lockstep clock are unchanged.
 */
export default function ProximityView() {
  const containerRef = useRef<HTMLDivElement>(null);
  const readoutRef = useRef<HTMLDivElement>(null);

  // Low-frequency, user-driven UI state (Decision 5 governs HIGH-freq data only —
  // camera mode / backdrop are fine in React). Mirrored to refs the long-lived
  // render-loop closure reads each frame.
  const [camFocus, setCamFocus] = useState<CameraFocus>('external');
  const [camDeputy, setCamDeputy] = useState(0);
  const [backdrop, setBackdrop] = useState<BackdropMode>('earth');
  const [showSensors, setShowSensors] = useState(true);
  const [fovOpacity, setFovOpacity] = useState(15); // percent
  const [showAxes, setShowAxes] = useState(false); // body-axis triad (orientation read)
  const [camSensorKey, setCamSensorKey] = useState(''); // selected sensor option (US-SENSE-05)
  const camFocusRef = useRef(camFocus);
  const camDeputyRef = useRef(camDeputy);
  const backdropRef = useRef(backdrop);
  const showSensorsRef = useRef(showSensors);
  const fovOpacityRef = useRef(fovOpacity);
  const camSensorKeyRef = useRef(camSensorKey);
  // The resolved host model index + body-frame boresight for the selected sensor.
  const camSensorRef = useRef<{ modelIdx: number; boresight: [number, number, number] } | null>(null);
  camFocusRef.current = camFocus;
  camDeputyRef.current = camDeputy;
  backdropRef.current = backdrop;
  showSensorsRef.current = showSensors;
  fovOpacityRef.current = fovOpacity;
  camSensorKeyRef.current = camSensorKey;
  const showAxesRef = useRef(showAxes);
  showAxesRef.current = showAxes;

  // Re-render (refresh the focus dropdown) when the deputy set changes; reset the
  // selectors on any scenario change so a stale deputy index can't linger.
  const relVersion = useSyncExternalStore(subscribeRelative, getRelativeVersion);
  const relData = getRelativeData();
  const deputies = relData?.deputies ?? [];
  // Phase 7: the legend reads "modeled" once the stream carries attitude, else
  // "estimated" (the derived fallback). Re-evaluated on relVersion change.
  const orientationModeled = !!(relData?.chief?.attitude || deputies.some((d) => d.attitude));
  // Measured-data slice 2: when any role flies real telemetry attitude
  // (AttitudeProfile.mode === "measured" in the loaded scenario body), the legend
  // reads "measured" instead of "modeled" — read from the body, not the stream.
  const orientationMeasured = useStore((s) => {
    const body = s.loadedScenario?.body;
    if (!body) return false;
    return [body.chief, ...(body.deputies ?? [])].some((r) => r?.attitude?.mode === 'measured');
  });
  const orientationLabel = orientationMeasured ? 'measured' : orientationModeled ? 'modeled' : 'estimated';
  useEffect(() => {
    setCamFocus('external');
    setCamDeputy(0);
    setCamSensorKey('');
    camSensorRef.current = null;
  }, [relVersion]);

  // Composer click → ride that craft (US-PROX-04). A deputy → deputy mode at its
  // index; anything else (the chief, which isn't in the deputy list) → chief mode
  // (the LVLH origin). Guard on the nonce so a stale request can't fire on mount.
  const proximityFocus = useStore((s) => s.proximityFocus);
  const lastProxNonce = useRef(proximityFocus?.nonce ?? 0);
  useEffect(() => {
    if (!proximityFocus || proximityFocus.nonce === lastProxNonce.current) return;
    lastProxNonce.current = proximityFocus.nonce;
    const deps = getRelativeData()?.deputies ?? [];
    const idx = deps.findIndex((d) => d.noradId === proximityFocus.noradId);
    if (idx >= 0) {
      setCamFocus('deputy');
      setCamDeputy(idx);
    } else {
      setCamFocus('chief');
    }
  }, [proximityFocus]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    // logarithmicDepthBuffer: the scene spans 1 m to ~100,000 km (Earth backdrop),
    // which obliterates a normal depth buffer's precision → z-fighting. Log depth
    // fixes it across the whole range (core materials + Line2/Line all honor it).
    const renderer = new THREE.WebGLRenderer({ antialias: true, logarithmicDepthBuffer: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setSize(container.clientWidth, container.clientHeight);
    container.appendChild(renderer.domElement);

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x0a0e1a);

    // Non-physical flat light rig (Phase 6) — makes the MeshStandard models + Earth
    // visible. The real Sun vector / terminator arrives in Phase 8.
    scene.add(new THREE.AmbientLight(0xffffff, 0.55));
    scene.add(new THREE.HemisphereLight(0x9bb8ff, 0x202838, 0.7));
    const keyLight = new THREE.DirectionalLight(0xffffff, 0.6);
    keyLight.position.set(1, 1, 1);
    scene.add(keyLight);

    const camera = new THREE.PerspectiveCamera(
      50,
      container.clientWidth / Math.max(1, container.clientHeight),
      0.1,
      2e8, // far plane covers ~100,000 km
    );
    camera.position.set(8000, 5000, 8000);

    const controls = new OrbitControls(camera, renderer.domElement);
    controls.target.set(0, 0, 0); // the chief, at the LVLH origin
    controls.enableDamping = true;
    controls.minDistance = 1; // 1 m
    controls.maxDistance = 2e8;
    const rig = new CameraRig(camera, controls);

    // Earth backdrop (US-PROX-05) — created once; radius set from the stream,
    // mode driven by the toggle.
    const earth = createEarthBackdrop();
    earth.setMode(backdropRef.current);
    scene.add(earth.root);

    // R/I/C axes (R→+X red, I→+Y green, C→+Z blue) + a faint R-I-plane grid.
    let axes = new THREE.AxesHelper(10000);
    scene.add(axes);
    let grid = new THREE.GridHelper(20000, 10, 0x334155, 0x1e293b);
    grid.rotation.x = Math.PI / 2; // GridHelper is X-Z by default → lay it on R-I (X-Y)
    scene.add(grid);

    // Spacecraft models (index 0 = chief) + per-deputy trajectory ribbons.
    let models: SpacecraftModel[] = [];
    let ribbons: Ribbon[] = [];
    let sensorFovs: { fov: SensorFov; sensorId: string }[] = []; // flattened across all craft (Phase 7)
    let sensorWindows = new Map<string, [number, number][]>(); // per-sensor in-view windows (ms)
    let builtVersion = -1;

    // LOD projection scale: apparent_px(radius) = radius / camDist * projScale.
    let projScale = 600 / (2 * Math.tan((50 * Math.PI) / 360));
    const recomputeProjScale = (h: number) => {
      projScale = h / (2 * Math.tan((camera.fov * Math.PI) / 360));
    };

    const quat = new THREE.Quaternion();
    const out6 = new Float64Array(6);

    // Build a craft's sensor FOV volumes, attach them under its root (so they ride
    // the body orientation), and collect them for toggling + disposal (Phase 7).
    const buildSensors = (host: SpacecraftModel, defs: SensorDef[], color: THREE.Color) => {
      for (const def of defs) {
        const fov = createSensorFov(def, color);
        fov.setVisible(showSensorsRef.current);
        fov.setOpacity(fovOpacityRef.current / 100);
        host.root.add(fov.root);
        sensorFovs.push({ fov, sensorId: def.id });
      }
    };

    const disposeModelsAndRibbons = () => {
      for (const f of sensorFovs) {
        f.fov.root.parent?.remove(f.fov.root);
        f.fov.dispose();
      }
      sensorFovs = [];
      for (const m of models) {
        scene.remove(m.root);
        m.dispose();
      }
      for (const r of ribbons) {
        scene.remove(r.past);
        scene.remove(r.predicted);
        r.dispose();
      }
      models = [];
      ribbons = [];
    };

    const rebuild = () => {
      const data = getRelativeData();
      builtVersion = getRelativeVersion();
      disposeModelsAndRibbons();
      rig.reset();
      lastFocus = 'external';
      lastDeputy = 0;

      const h = Math.max(1, container.clientHeight);

      // Chief model at the origin. Seed its orientation from the streamed attitude
      // (Phase 7) at t=0, falling back to the fixed LVLH pose; the loop keeps it live.
      const chief = createSpacecraftModel(CHIEF_COLOR);
      out6[0] = out6[1] = out6[2] = out6[3] = out6[4] = out6[5] = 0;
      bodyOrientationAt(data?.chief?.attitude ?? null, data?.chief?.attitude ? data.chief.attitude[0] : 0,
        out6, false, quat);
      chief.root.quaternion.copy(quat);
      scene.add(chief.root);
      models.push(chief);
      if (data?.chief?.sensors?.length) buildSensors(chief, data.chief.sensors, CHIEF_COLOR);

      // Working scale for the auto-frame. Seed from a craft-scale floor (a lone
      // measured chief with no deputy must not fall back to a 1 km default — at
      // that distance the ~10 m model is a sub-pixel marker dot, forcing a manual
      // zoom) and from the chief's own sensor cones (so a single craft + FOV still
      // frames). Deputy positions expand it below.
      let maxDist = chief.radius * 4; // ~40 m → the 10 m model renders as geometry on load
      for (const s of data?.chief?.sensors ?? []) {
        maxDist = Math.max(maxDist, s.maxRangeM > 0 ? s.maxRangeM : 1000);
      }
      const out: [number, number, number] = [0, 0, 0];
      data?.deputies.forEach((dep, i) => {
        const color = DEPUTY_COLORS[i % DEPUTY_COLORS.length];
        const m = createSpacecraftModel(color);
        // Seed at the first sample so there's no one-frame flash at the origin.
        deputyPositionAt(dep.samples, dep.stride, dep.samples[0] ?? 0, out);
        m.root.position.set(out[0], out[1], out[2]);
        scene.add(m.root);
        models.push(m);
        ribbons.push(createRibbon(dep.samples, dep.stride, color));
        if (dep.sensors.length) buildSensors(m, dep.sensors, color);
        maxDist = Math.max(maxDist, Math.hypot(out[0], out[1], out[2]));
      });
      for (const r of ribbons) {
        scene.add(r.predicted);
        scene.add(r.past);
      }

      // Earth backdrop distance from the chief's geocentric radius (US-PROX-05).
      earth.setChiefRadius(data?.chiefRadiusM ?? 0);

      // Per-sensor in-view windows (ms) for the acquisition highlight (US-EVT-01).
      if (data?.events && data.events.length > 0) {
        const loMs = data.epochMs;
        let maxT = 0;
        for (const d of data.deputies) {
          if (d.samples.length >= d.stride) maxT = Math.max(maxT, d.samples[d.samples.length - d.stride]);
        }
        sensorWindows = buildSensorWindows(data.events, loMs, loMs + maxT * 1000);
      } else {
        sensorWindows = new Map();
      }

      // Auto-frame: resize axes/grid + place the camera so the deputies fit.
      scene.remove(axes);
      axes.dispose();
      axes = new THREE.AxesHelper(maxDist);
      scene.add(axes);
      scene.remove(grid);
      grid.geometry.dispose();
      (grid.material as THREE.Material).dispose();
      grid = new THREE.GridHelper(maxDist * 2, 10, 0x334155, 0x1e293b);
      grid.rotation.x = Math.PI / 2;
      scene.add(grid);

      recomputeProjScale(h);
      const fit = maxDist * 1.8 + 100;
      camera.position.set(fit, fit * 0.6, fit);
      controls.maxDistance = Math.max(2e5, fit * 4);
      controls.update();
    };

    // ΔV maneuver glyphs (US-MAN-04): one arrow per maneuver, shown when the clock
    // is within MANEUVER_WINDOW_S of the burn epoch. Built lazily and reused.
    const MANEUVER_WINDOW_S = 120;
    const glyphs: THREE.ArrowHelper[] = [];
    const ensureGlyphs = (count: number) => {
      while (glyphs.length < count) {
        const a = new THREE.ArrowHelper(
          new THREE.Vector3(0, 1, 0), new THREE.Vector3(), 1, 0xffe066, undefined, undefined);
        a.visible = false;
        scene.add(a);
        glyphs.push(a);
      }
    };

    // Apply the marker↔model LOD crossfade + near-plane scale clamp for one model.
    const applyLod = (m: SpacecraftModel, camDist: number) => {
      const px = camDist > 1e-3 ? (m.radius / camDist) * projScale : Infinity;
      if (px <= FADE_LO) {
        m.setModelVisible(false);
        m.setMarkerVisible(true);
        m.setMarkerOpacity(1);
      } else if (px >= FADE_HI) {
        m.setModelVisible(true);
        m.setMarkerVisible(false);
      } else {
        m.setModelVisible(true);
        m.setMarkerVisible(true);
        m.setMarkerOpacity((FADE_HI - px) / (FADE_HI - FADE_LO));
      }
      // Shrink only when the camera is inside ~1.5× the model radius (avoids
      // enveloping/clipping the camera); true-to-scale otherwise.
      m.setModelScale(Math.min(1, camDist / (m.radius * 1.5)));
    };

    // Camera focus tracking (re-synced when the selector changes).
    let lastFocus: CameraFocus = 'external';
    let lastDeputy = 0;
    let lastBackdrop: BackdropMode = backdropRef.current;
    let lastShowSensors = showSensorsRef.current;
    let lastFovOpacity = fovOpacityRef.current;
    let lastSensorKey = camSensorKeyRef.current;
    const focusPos = new THREE.Vector3();
    const focusQuat = new THREE.Quaternion();
    const UP_Y = new THREE.Vector3(0, 1, 0);
    const SENSOR_BACK = new THREE.Vector3(0, -1, 0); // sit behind the apex along -boresight
    const bvec = new THREE.Vector3();
    const resolveFocus = (data: ReturnType<typeof getRelativeData>, pos: THREE.Vector3, q: THREE.Quaternion) => {
      const focus = camFocusRef.current;
      if (focus === 'chief' && models[0]) {
        pos.copy(models[0].root.position);
        q.copy(models[0].root.quaternion);
      } else if (focus === 'deputy' && data && data.deputies.length > 0) {
        const idx = Math.min(camDeputyRef.current, data.deputies.length - 1);
        const m = models[idx + 1];
        if (m) {
          pos.copy(m.root.position);
          q.copy(m.root.quaternion);
          return;
        }
        pos.set(0, 0, 0);
        q.identity();
      } else if (focus === 'sensor' && camSensorRef.current) {
        const m = models[camSensorRef.current.modelIdx];
        if (m) {
          pos.copy(m.root.position);
          const b = camSensorRef.current.boresight;
          // World boresight = host body orientation · body-frame boresight; aim local +Y at it.
          bvec.set(b[0], b[1], b[2]);
          if (bvec.lengthSq() === 0) bvec.set(1, 0, 0);
          bvec.normalize().applyQuaternion(m.root.quaternion);
          q.setFromUnitVectors(UP_Y, bvec);
          return;
        }
        pos.set(0, 0, 0);
        q.identity();
      } else {
        pos.set(0, 0, 0);
        q.identity();
      }
    };

    // Render loop — the lockstep seam. Reads the shared clock; never writes it.
    let rafId = 0;
    let lastReadout = 0;
    const out: [number, number, number] = [0, 0, 0];
    const dvDir = new THREE.Vector3();
    const glyphPos = new THREE.Vector3();
    const renderFrame = () => {
      rafId = requestAnimationFrame(renderFrame);
      const data = getRelativeData();
      if (getRelativeVersion() !== builtVersion) rebuild();
      const now = performance.now();

      // Backdrop toggle (polled from the ref — changes are rare).
      if (backdropRef.current !== lastBackdrop) {
        lastBackdrop = backdropRef.current;
        earth.setMode(lastBackdrop);
      }

      // Sensor FOV visibility / opacity (polled from refs — changes are rare).
      if (showSensorsRef.current !== lastShowSensors || fovOpacityRef.current !== lastFovOpacity) {
        lastShowSensors = showSensorsRef.current;
        lastFovOpacity = fovOpacityRef.current;
        for (const f of sensorFovs) {
          f.fov.setVisible(lastShowSensors);
          f.fov.setOpacity(lastFovOpacity / 100);
        }
      }
      // Light each FOV green while its sensor currently has a target acquired (US-EVT-01).
      if (sensorFovs.length) {
        const nowMs = useStore.getState().currentTime.getTime();
        for (const f of sensorFovs) {
          f.fov.setAcquired(inWindow(sensorWindows.get(f.sensorId), nowMs));
        }
      }

      let maxDistNow = 1000;
      if (data && models.length) {
        const t = simTimeToT(data.epochMs, useStore.getState().currentTime);
        // Chief: keep its modeled body orientation live (varies for eccentric orbits).
        if (data.chief?.attitude && models[0]) {
          bodyOrientationAt(data.chief.attitude, t, out6, false, quat);
          models[0].root.quaternion.copy(quat);
        }
        // Deputies: position + orientation (modeled if streamed, else derived) + ribbon.
        data.deputies.forEach((dep, i) => {
          deputyStateAt(dep.samples, dep.stride, dep.hasVelocity, t, out6);
          const m = models[i + 1];
          if (m) {
            m.root.position.set(out6[0], out6[1], out6[2]);
            bodyOrientationAt(dep.attitude, t, out6, dep.hasVelocity, quat);
            m.root.quaternion.copy(quat);
          }
          ribbons[i]?.setSplit(t);
          maxDistNow = Math.max(maxDistNow, Math.hypot(out6[0], out6[1], out6[2]));
        });
        // LOD crossfade + near-clamp for every model (chief + deputies); the body-axis
        // triad rides each craft's orientation and is shown only when toggled on. Its
        // length tracks the camera distance so it stays a readable on-screen size even
        // when the camera is zoomed out to fit a km-scale FOV cone (a fixed-length triad
        // would vanish there). Floor at the craft size so it never shrinks below the bus.
        for (const m of models) {
          const camDist = camera.position.distanceTo(m.root.position);
          applyLod(m, camDist);
          m.setAxesVisible(showAxesRef.current);
          if (showAxesRef.current) m.setAxesWorldLength(Math.max(camDist * 0.12, m.radius * 1.5));
        }

        // ΔV glyphs: read maneuvers from the loaded scenario (store), place each at
        // the deputy's interpolated position at the burn epoch, oriented along ΔV
        // in LVLH (R→+X, I→+Y, C→+Z). Length scaled to the scene, clamped.
        const body = useStore.getState().loadedScenario?.body;
        const nowMs = useStore.getState().currentTime.getTime();
        const glyphLen = Math.max(maxDistNow * 0.25, 200);
        let gi = 0;
        if (body?.deputies) {
          for (const dep of data.deputies) {
            const role = body.deputies.find((d) => d.noradId === dep.noradId);
            for (const mvr of role?.maneuvers ?? []) {
              const epochMs = mvr.epoch ? Date.parse(mvr.epoch) : NaN;
              const dv = mvr.deltaV;
              if (Number.isNaN(epochMs) || !dv) continue;
              ensureGlyphs(gi + 1);
              const glyph = glyphs[gi++];
              if (Math.abs(nowMs - epochMs) > MANEUVER_WINDOW_S * 1000) {
                glyph.visible = false;
                continue;
              }
              const mt = (epochMs - data.epochMs) / 1000;
              deputyPositionAt(dep.samples, dep.stride, mt, out);
              dvDir.set(dv.r ?? 0, dv.i ?? 0, dv.c ?? 0);
              if (dvDir.lengthSq() === 0) {
                glyph.visible = false;
                continue;
              }
              dvDir.normalize();
              glyphPos.set(out[0], out[1], out[2]);
              glyph.position.copy(glyphPos);
              glyph.setDirection(dvDir);
              glyph.setLength(glyphLen, glyphLen * 0.25, glyphLen * 0.12);
              glyph.visible = true;
            }
          }
        }
        for (let k = gi; k < glyphs.length; k++) glyphs[k].visible = false;
      }

      // Camera mode: re-sync the rig when the selector (or selected sensor) changes.
      if (camFocusRef.current !== lastFocus || camDeputyRef.current !== lastDeputy
          || camSensorKeyRef.current !== lastSensorKey) {
        lastFocus = camFocusRef.current;
        lastDeputy = camDeputyRef.current;
        lastSensorKey = camSensorKeyRef.current;
        resolveFocus(data, focusPos, focusQuat);
        // Sensor mode starts behind the apex looking along the boresight.
        rig.setFocus(lastFocus, focusPos, lastFocus === 'sensor' ? SENSOR_BACK : undefined);
      }
      resolveFocus(data, focusPos, focusQuat);
      rig.update(now, focusPos, focusQuat); // calls controls.update() internally

      renderer.render(scene, camera);

      if (readoutRef.current && now - lastReadout > 200) {
        lastReadout = now;
        readoutRef.current.textContent = `scale ${fmtDistance(controls.getDistance())}`;
      }
    };
    rebuild();
    renderFrame();

    const resize = new ResizeObserver(() => {
      const w = container.clientWidth;
      const h = Math.max(1, container.clientHeight);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      renderer.setSize(w, h);
      recomputeProjScale(h);
    });
    resize.observe(container);

    return () => {
      cancelAnimationFrame(rafId);
      resize.disconnect();
      controls.dispose();
      glyphs.forEach((g) => {
        scene.remove(g);
        g.dispose();
      });
      disposeModelsAndRibbons();
      scene.remove(earth.root);
      earth.dispose();
      axes.dispose();
      grid.geometry.dispose();
      (grid.material as THREE.Material).dispose();
      renderer.dispose();
      if (renderer.domElement.parentElement === container) {
        container.removeChild(renderer.domElement);
      }
    };
  }, []);

  // Flat list of sensor camera options (US-SENSE-05): chief sensors, then each
  // deputy's. modelIdx is the index into the proximity `models[]` array (0 = chief).
  const sensorOptions: { value: string; label: string; modelIdx: number; boresight: [number, number, number] }[] = [];
  (relData?.chief?.sensors ?? []).forEach((s, j) =>
    sensorOptions.push({ value: `sensor:0:${j}`, label: `Chief — ${s.name}`, modelIdx: 0, boresight: s.mount.boresightBody }),
  );
  deputies.forEach((d, i) =>
    d.sensors.forEach((s, j) =>
      sensorOptions.push({
        value: `sensor:${i + 1}:${j}`,
        label: `${d.name} — ${s.name}`,
        modelIdx: i + 1,
        boresight: s.mount.boresightBody,
      }),
    ),
  );

  const focusValue =
    camFocus === 'deputy' ? `deputy:${camDeputy}` : camFocus === 'sensor' ? camSensorKey : camFocus;
  const onFocusChange = (v: string) => {
    if (v === 'external' || v === 'chief') {
      setCamFocus(v);
    } else if (v.startsWith('deputy:')) {
      setCamFocus('deputy');
      setCamDeputy(Number(v.slice('deputy:'.length)) || 0);
    } else if (v.startsWith('sensor:')) {
      const opt = sensorOptions.find((o) => o.value === v);
      if (opt) {
        camSensorRef.current = { modelIdx: opt.modelIdx, boresight: opt.boresight };
        setCamSensorKey(v);
        setCamFocus('sensor');
      }
    }
  };

  return (
    <div className="proximity-view">
      <div ref={containerRef} style={{ width: '100%', height: '100%' }} />

      <div className="proximity-controls">
        <label className="prox-ctrl">
          <span>Camera</span>
          <select value={focusValue} onChange={(e) => onFocusChange(e.target.value)}>
            <option value="external">External (free)</option>
            <option value="chief">Chief body</option>
            {deputies.map((d, i) => (
              <option key={d.noradId} value={`deputy:${i}`}>
                Deputy: {d.name}
              </option>
            ))}
            {sensorOptions.map((o) => (
              <option key={o.value} value={o.value}>
                Sensor: {o.label}
              </option>
            ))}
          </select>
        </label>
        <div className="prox-ctrl">
          <span>Backdrop</span>
          <div className="prox-seg">
            {(['earth', 'stars', 'off'] as BackdropMode[]).map((m) => (
              <button
                key={m}
                className={backdrop === m ? 'active' : ''}
                onClick={() => setBackdrop(m)}
                title={m === 'off' ? 'Pure space' : m === 'stars' ? 'Starfield only' : 'Earth + stars'}
              >
                {m === 'earth' ? 'Earth' : m === 'stars' ? 'Stars' : 'Off'}
              </button>
            ))}
          </div>
        </div>
        <div className="prox-ctrl">
          <span>Sensors</span>
          <div className="prox-seg">
            <button className={showSensors ? 'active' : ''} onClick={() => setShowSensors(true)}>View</button>
            <button className={!showSensors ? 'active' : ''} onClick={() => setShowSensors(false)}>Off</button>
          </div>
        </div>
        <div className="prox-ctrl">
          <span>Body axes</span>
          <div className="prox-seg">
            <button
              className={showAxes ? 'active' : ''}
              onClick={() => setShowAxes(true)}
              title="Show each craft's body-axis triad (X red / Y nose-green / Z top-blue) — the orientation read"
            >
              On
            </button>
            <button className={!showAxes ? 'active' : ''} onClick={() => setShowAxes(false)}>Off</button>
          </div>
        </div>
        {showSensors && (
          <label className="prox-ctrl">
            <span>FOV opacity</span>
            <input
              type="range"
              min={5}
              max={60}
              value={fovOpacity}
              onChange={(e) => setFovOpacity(Number(e.target.value))}
            />
          </label>
        )}
      </div>

      <div className="proximity-legend">
        chief <span style={{ color: '#ffd166' }}>●</span> · R<span style={{ color: '#f87171' }}>x</span>{' '}
        I<span style={{ color: '#4ade80' }}>y</span> C<span style={{ color: '#60a5fa' }}>z</span>
        <span className="proximity-caveat"> · orientation: {orientationLabel}</span>
        <span ref={readoutRef} className="proximity-scale" />
      </div>
    </div>
  );
}
