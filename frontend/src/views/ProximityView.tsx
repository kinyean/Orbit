import { useEffect, useRef } from 'react';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { useStore } from '../store/useStore';
import {
  getRelativeData,
  getRelativeVersion,
  simTimeToT,
  deputyPositionAt,
} from '../stream/relativeBuffer';

// Marker colors. Chief = amber (matches the globe's CHIEF_RGB); deputies cycle a
// palette matching the globe's SCENARIO_DEPUTY_PALETTE so a deputy is the same
// color in both views.
const CHIEF_COLOR = new THREE.Color(0xffd166);
const DEPUTY_COLORS = [
  0x38bdf8, 0xff922b, 0xa3e635, 0xe879f9, 0x2dd4bf, 0xf472b6, 0x818cf8, 0xfacc15,
].map((h) => new THREE.Color(h));

function fmtDistance(m: number): string {
  return m >= 1000 ? `${(m / 1000).toFixed(m >= 100000 ? 0 : 1)} km` : `${m.toFixed(0)} m`;
}

/**
 * Proximity view (Phase 4B, US-VIEW-01). A three.js scene in the chief's LVLH
 * frame: chief at the origin, deputies at their relative positions, animating in
 * lockstep with the global view via the ONE shared clock (it READS
 * `store.currentTime` each frame and never writes it — mirrors Globe's preRender).
 * Axis map R→+X, I→+Y, C→+Z; 1 scene unit = 1 m. Markers are fixed-pixel points so
 * they stay visible across the 1 m–100 km+ range; the camera auto-frames on load.
 */
export default function ProximityView() {
  const containerRef = useRef<HTMLDivElement>(null);
  const readoutRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setSize(container.clientWidth, container.clientHeight);
    container.appendChild(renderer.domElement);

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x0a0e1a);

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

    // R/I/C axes (R→+X red, I→+Y green, C→+Z blue) + a faint R-I-plane grid.
    let axes = new THREE.AxesHelper(10000);
    scene.add(axes);
    let grid = new THREE.GridHelper(20000, 10, 0x334155, 0x1e293b);
    grid.rotation.x = Math.PI / 2; // GridHelper is X-Z by default → lay it on R-I (X-Y)
    scene.add(grid);

    // Chief + deputies as one fixed-pixel Points cloud (index 0 = chief origin).
    const pointsMaterial = new THREE.PointsMaterial({
      size: 9,
      sizeAttenuation: false,
      vertexColors: true,
    });
    let points: THREE.Points | null = null;
    let geometry: THREE.BufferGeometry | null = null;
    let builtVersion = -1;

    const rebuild = () => {
      const data = getRelativeData();
      builtVersion = getRelativeVersion();
      if (points) {
        scene.remove(points);
        points.geometry.dispose();
        points = null;
        geometry = null;
      }
      const n = (data?.deputies.length ?? 0) + 1; // +1 chief
      const positions = new Float32Array(n * 3); // chief at origin (0,0,0)
      const colors = new Float32Array(n * 3);
      colors[0] = CHIEF_COLOR.r;
      colors[1] = CHIEF_COLOR.g;
      colors[2] = CHIEF_COLOR.b;

      let maxDist = 1000; // ≥1 km so the initial framing isn't degenerate
      const out: [number, number, number] = [0, 0, 0];
      data?.deputies.forEach((dep, i) => {
        const c = DEPUTY_COLORS[i % DEPUTY_COLORS.length];
        const ci = (i + 1) * 3;
        colors[ci] = c.r;
        colors[ci + 1] = c.g;
        colors[ci + 2] = c.b;
        // Seed at the first sample for framing.
        deputyPositionAt(dep.samples, dep.stride, dep.samples[0] ?? 0, out);
        const pi = (i + 1) * 3;
        positions[pi] = out[0];
        positions[pi + 1] = out[1];
        positions[pi + 2] = out[2];
        maxDist = Math.max(maxDist, Math.hypot(out[0], out[1], out[2]));
      });

      geometry = new THREE.BufferGeometry();
      geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
      geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));
      points = new THREE.Points(geometry, pointsMaterial);
      scene.add(points);

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

      let maxDistNow = 1000;
      if (data && geometry) {
        const t = simTimeToT(data.epochMs, useStore.getState().currentTime);
        const pos = geometry.attributes.position.array as Float32Array;
        data.deputies.forEach((dep, i) => {
          deputyPositionAt(dep.samples, dep.stride, t, out);
          const b = (i + 1) * 3;
          pos[b] = out[0];
          pos[b + 1] = out[1];
          pos[b + 2] = out[2];
          maxDistNow = Math.max(maxDistNow, Math.hypot(out[0], out[1], out[2]));
        });
        geometry.attributes.position.needsUpdate = true;

        // ΔV glyphs: read maneuvers from the loaded scenario (store), place each at
        // the deputy's interpolated position at the burn epoch, oriented along ΔV
        // in LVLH (R→+X, I→+Y, C→+Z). Length scaled to the scene, clamped.
        const body = useStore.getState().loadedScenario?.body;
        const nowMs = useStore.getState().currentTime.getTime();
        const glyphLen = Math.max(maxDistNow * 0.25, 200);
        let gi = 0;
        if (body?.deputies && data) {
          for (const dep of data.deputies) {
            const role = body.deputies.find((d) => d.noradId === dep.noradId);
            for (const m of role?.maneuvers ?? []) {
              const epochMs = m.epoch ? Date.parse(m.epoch) : NaN;
              const dv = m.deltaV;
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

      controls.update();
      renderer.render(scene, camera);

      const now = performance.now();
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
      if (points) points.geometry.dispose();
      pointsMaterial.dispose();
      axes.dispose();
      grid.geometry.dispose();
      (grid.material as THREE.Material).dispose();
      renderer.dispose();
      if (renderer.domElement.parentElement === container) {
        container.removeChild(renderer.domElement);
      }
    };
  }, []);

  return (
    <div className="proximity-view">
      <div ref={containerRef} style={{ width: '100%', height: '100%' }} />
      <div className="proximity-legend">
        chief <span style={{ color: '#ffd166' }}>●</span> · R<span style={{ color: '#f87171' }}>x</span>{' '}
        I<span style={{ color: '#4ade80' }}>y</span> C<span style={{ color: '#60a5fa' }}>z</span>
        <span ref={readoutRef} className="proximity-scale" />
      </div>
    </div>
  );
}
