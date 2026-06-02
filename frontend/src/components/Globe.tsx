import { useEffect, useRef } from 'react';
import {
  Viewer,
  Ion,
  CzmlDataSource,
  ScreenSpaceEventHandler,
  ScreenSpaceEventType,
  CallbackPositionProperty,
  CallbackProperty,
  Cartesian2,
  Cartesian3,
  Cartographic,
  Color,
  Entity,
  JulianDate,
  Math as CesiumMath,
  Matrix4,
  NearFarScalar,
  Transforms,
  defined,
} from 'cesium';
import 'cesium/Build/Cesium/Widgets/widgets.css';
import { useStore, type SatIndexEntry, type SelectedSatellite } from '../store/useStore';
import { constellationOf } from '../lib/constellations';
import { CatalogStreamClient } from '../stream/CatalogStreamClient';
import { STREAM_CONTRACT_VERSION } from '../api/contract';

const cesiumToken = import.meta.env.VITE_CESIUM_ION_TOKEN;
if (cesiumToken) {
  Ion.defaultAccessToken = cesiumToken;
}

const HIT_PAD_PX = 5;

/** Parse "sat-25544" → 25544, or null. */
function noradFromEntityId(id: unknown): number | null {
  if (typeof id !== 'string' || !id.startsWith('sat-')) return null;
  const n = Number.parseInt(id.slice(4), 10);
  return Number.isFinite(n) ? n : null;
}

/** Apply constellation filters to all catalog entities (show/hide). */
function applyFilters(ds: CzmlDataSource, activeConstellations: string[]) {
  const active = new Set(activeConstellations);
  const entities = ds.entities.values;
  for (let i = 0; i < entities.length; i++) {
    const e = entities[i];
    if (e.id === 'document') continue;
    const c = e.name ? constellationOf(e.name) : null;
    e.show = c === null || active.has(c);
  }
}

/** Build the searchable catalog index from a CZML packet array. */
function buildIndex(czml: unknown[]): SatIndexEntry[] {
  const index: SatIndexEntry[] = [];
  for (const packet of czml) {
    const p = packet as { id?: string; name?: string };
    const norad = noradFromEntityId(p.id);
    if (norad === null) continue;
    const name = p.name ?? `NORAD ${norad}`;
    index.push({ noradId: norad, name, constellation: constellationOf(name) });
  }
  return index;
}

/** Read a numeric custom property off an entity at a given time. */
function readNumberProp(entity: Entity, key: string, time: JulianDate): number | null {
  const props = entity.properties as { [k: string]: { getValue?: (t: JulianDate) => unknown } } | undefined;
  const prop = props?.[key];
  if (!prop?.getValue) return null;
  const v = prop.getValue(time);
  return typeof v === 'number' ? v : null;
}

/** Build the SelectedSatellite detail object from a picked entity. */
function describeEntity(entity: Entity, time: JulianDate): SelectedSatellite | null {
  const norad = noradFromEntityId(entity.id);
  if (norad === null) return null;
  const pos = entity.position?.getValue(time);
  let latitudeDeg = 0;
  let longitudeDeg = 0;
  let altitudeKm = 0;
  if (pos) {
    const carto = Cartographic.fromCartesian(pos);
    if (carto) {
      latitudeDeg = CesiumMath.toDegrees(carto.latitude);
      longitudeDeg = CesiumMath.toDegrees(carto.longitude);
      altitudeKm = carto.height / 1000;
    }
  }
  return {
    noradId: norad,
    name: entity.name ?? `NORAD ${norad}`,
    inclinationDeg: readNumberProp(entity, 'inclinationDeg', time),
    periodMinutes: readNumberProp(entity, 'periodMinutes', time),
    latitudeDeg,
    longitudeDeg,
    altitudeKm,
  };
}

/** Pick a satellite entity at the click point, padding the search radius. */
function pickSatellite(viewer: Viewer, position: Cartesian2): Entity | null {
  const tryPick = (x: number, y: number): Entity | null => {
    const picked = viewer.scene.pick(new Cartesian2(x, y));
    if (defined(picked) && picked.id instanceof Entity && noradFromEntityId(picked.id.id) !== null) {
      return picked.id;
    }
    return null;
  };
  const direct = tryPick(position.x, position.y);
  if (direct) return direct;
  // Ring of offsets for click forgiveness on tiny moving dots.
  for (const [dx, dy] of [
    [HIT_PAD_PX, 0], [-HIT_PAD_PX, 0], [0, HIT_PAD_PX], [0, -HIT_PAD_PX],
    [HIT_PAD_PX, HIT_PAD_PX], [-HIT_PAD_PX, -HIT_PAD_PX], [HIT_PAD_PX, -HIT_PAD_PX], [-HIT_PAD_PX, HIT_PAD_PX],
  ]) {
    const hit = tryPick(position.x + dx, position.y + dy);
    if (hit) return hit;
  }
  return null;
}

export default function Globe() {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<Viewer | null>(null);
  const dataSourceRef = useRef<CzmlDataSource | null>(null);
  const indexBuiltRef = useRef(false);

  // Reactive store slices that drive imperative Cesium updates.
  const activeConstellations = useStore((s) => s.filters.constellations);
  const focus = useStore((s) => s.focus);
  const cameraResetNonce = useStore((s) => s.cameraResetNonce);

  // --- mount: viewer + stream + click handler ------------------------------
  useEffect(() => {
    if (!containerRef.current) return;

    const viewer = new Viewer(containerRef.current, {
      animation: false,
      timeline: false,
      baseLayerPicker: false,
      geocoder: false,
      homeButton: false,
      sceneModePicker: false,
      navigationHelpButton: false,
      fullscreenButton: false,
      infoBox: false,
      selectionIndicator: false,
    });
    if (viewer.scene.skyBox) viewer.scene.skyBox.show = true;
    if (viewer.scene.sun) viewer.scene.sun.show = true;
    if (viewer.scene.moon) viewer.scene.moon.show = true;
    viewer.scene.globe.enableLighting = true;
    viewer.clock.shouldAnimate = true; // advance through the streamed samples
    viewerRef.current = viewer;

    const dataSource = new CzmlDataSource('catalog');
    viewer.dataSources.add(dataSource);
    dataSourceRef.current = dataSource;

    // Selection highlight: a yellow ring that live-tracks the selected
    // satellite's position (via a CallbackProperty, so it follows the moving
    // dot every frame with no per-tick allocation). Returns undefined when
    // nothing is selected, so the ring simply isn't drawn. Lives in
    // viewer.entities (not the CZML source), so chunk processing never wipes it.
    viewer.entities.add({
      id: '__selection__',
      position: new CallbackPositionProperty(() => {
        const sel = useStore.getState().selectedSatellite;
        if (!sel) return undefined;
        const e = dataSourceRef.current?.entities.getById(`sat-${sel.noradId}`);
        return e?.position?.getValue(viewer.clock.currentTime) ?? undefined;
      }, false),
      point: {
        pixelSize: 18,
        color: Color.TRANSPARENT,
        outlineColor: Color.YELLOW,
        outlineWidth: 2,
        // Grow the ring as the camera approaches so zoom has visible feedback
        // (the satellite itself is a fixed-size screen-space dot). Normal size
        // at the ~2000 km default focus view, up to 3x when zoomed to ~100 km.
        scaleByDistance: new NearFarScalar(100_000, 3.0, 2_000_000, 1.0),
        disableDepthTestDistance: Number.POSITIVE_INFINITY, // always visible
      },
    });

    // Serialize chunk processing so a slow process() can't overlap the next
    // chunk, and catch failures so one malformed frame degrades gracefully
    // instead of throwing an unhandled rejection (the stream self-heals: the
    // next good chunk merges by id).
    let processChain: Promise<void> = Promise.resolve();
    const stream = new CatalogStreamClient(CatalogStreamClient.defaultUrl(), STREAM_CONTRACT_VERSION, {
      onMessage: (msg) => {
        const ds = dataSourceRef.current;
        if (!ds) return;
        if (!indexBuiltRef.current && msg.epoch) {
          // Align the viewer clock with the first data epoch.
          viewer.clock.currentTime = JulianDate.fromIso8601(msg.epoch);
        }
        processChain = processChain
          .then(() => ds.process(msg.czml as unknown as object))
          .then(() => {
            applyFilters(ds, useStore.getState().filters.constellations);
          })
          .catch((err) => {
            // eslint-disable-next-line no-console
            console.error('Catalog CZML chunk failed to process; skipping it', err);
          });
        if (!indexBuiltRef.current) {
          useStore.getState().setCatalog(msg.satelliteCount, buildIndex(msg.czml));
          indexBuiltRef.current = true;
        }
      },
    });
    // Defer connect to the next macrotask so React StrictMode's dev
    // mount→unmount→remount doesn't open then immediately close a socket
    // (which logs "WebSocket is closed before the connection is established").
    // The cancelled first scheduling never connects; only the remount's does.
    let connectCancelled = false;
    const connectTimer = window.setTimeout(() => {
      if (!connectCancelled) stream.connect();
    }, 0);

    // Keep the selected satellite's lat/lon/alt live as the clock advances
    // (the dot moves; the info panel must show "current" position, not a frozen
    // click-time snapshot). Throttled so the panel updates a few times a second.
    let lastPosUpdate = 0;
    const removeTick = viewer.clock.onTick.addEventListener((clock) => {
      const selected = useStore.getState().selectedSatellite;
      if (!selected) return;
      const now = performance.now();
      if (now - lastPosUpdate < 250) return;
      lastPosUpdate = now;
      const ds = dataSourceRef.current;
      const entity = ds?.entities.getById(`sat-${selected.noradId}`);
      const pos = entity?.position?.getValue(clock.currentTime);
      if (!pos) return;
      const carto = Cartographic.fromCartesian(pos);
      if (!carto) return;
      useStore.getState().updateSelectedPosition(
        CesiumMath.toDegrees(carto.latitude),
        CesiumMath.toDegrees(carto.longitude),
        carto.height / 1000,
      );
    });

    const handler = new ScreenSpaceEventHandler(viewer.scene.canvas);
    // Single click = inspect only (select + info panel + highlight ring, no
    // camera move).
    handler.setInputAction((click: { position: Cartesian2 }) => {
      const entity = pickSatellite(viewer, click.position);
      if (!entity) return;
      useStore.getState().setSelectedSatellite(describeEntity(entity, viewer.clock.currentTime));
    }, ScreenSpaceEventType.LEFT_CLICK);
    // Double click = focus (track the satellite: 360° orbit, centered, zoom
    // toward it). Routed through requestFocus → the focus effect below.
    handler.setInputAction((click: { position: Cartesian2 }) => {
      const entity = pickSatellite(viewer, click.position);
      if (!entity) return;
      const norad = noradFromEntityId(entity.id);
      if (norad !== null) useStore.getState().requestFocus(norad);
    }, ScreenSpaceEventType.LEFT_DOUBLE_CLICK);

    return () => {
      connectCancelled = true;
      window.clearTimeout(connectTimer);
      stream.close();
      removeTick();
      handler.destroy();
      viewer.destroy();
      viewerRef.current = null;
      dataSourceRef.current = null;
      indexBuiltRef.current = false;
    };
  }, []);

  // --- filters change: re-apply show/hide ----------------------------------
  useEffect(() => {
    const ds = dataSourceRef.current;
    if (ds) applyFilters(ds, activeConstellations);
  }, [activeConstellations]);

  // --- focus request: smoothly zoom into a tracked satellite (dbl-click/search)
  // Done ENTIRELY via the tracking camera (EntityView) — never camera.flyTo,
  // which fought EntityView and caused twist-back / black-flash / super-zoom.
  // Engage tracking starting at the camera's CURRENT offset (so the position
  // doesn't jump), then animate viewFrom to a fixed 3/4 view; afterward clear
  // viewFrom so the user can orbit/zoom freely. The satellite stays centered
  // throughout.
  useEffect(() => {
    const viewer = viewerRef.current;
    const ds = dataSourceRef.current;
    if (!viewer || !ds || !focus) return;
    const entity = ds.entities.getById(`sat-${focus.noradId}`);
    if (!entity) return;
    const time = viewer.clock.currentTime;
    useStore.getState().setSelectedSatellite(describeEntity(entity, time));
    const satPos = entity.position?.getValue(time);
    if (!satPos) return;

    viewer.trackedEntity = undefined; // release any prior tracking cleanly

    // Camera's current offset in the satellite's ENU frame = the animation start
    // (engaging tracking from here means no position jump).
    const enu = Transforms.eastNorthUpToFixedFrame(satPos);
    const invEnu = Matrix4.inverseTransformation(enu, new Matrix4());
    const startOffset = Matrix4.multiplyByPoint(invEnu, viewer.camera.positionWC, new Cartesian3());
    const endOffset = new Cartesian3(0, -1_500_000, 1_500_000); // 3/4 view, ~2120 km
    const startMs = performance.now();
    const durationMs = 1200;

    entity.viewFrom = new CallbackProperty(() => {
      const t = Math.min(1, (performance.now() - startMs) / durationMs);
      const eased = t * t * (3 - 2 * t); // smoothstep
      return Cartesian3.lerp(startOffset, endOffset, eased, new Cartesian3());
    }, false);
    viewer.trackedEntity = entity;

    // Once the zoom-in finishes, drop viewFrom so EntityView preserves the
    // user's offset (lets them orbit/zoom) instead of forcing the camera.
    const clearTimer = window.setTimeout(() => {
      const v = viewerRef.current;
      if (v && !v.isDestroyed() && v.trackedEntity === entity) {
        entity.viewFrom = undefined;
      }
    }, durationMs + 200);
    return () => window.clearTimeout(clearTimer);
  }, [focus]);

  // --- camera reset: stop tracking + fly back to a global view -------------
  useEffect(() => {
    if (cameraResetNonce === 0) return; // skip initial mount
    const viewer = viewerRef.current;
    if (!viewer) return;
    viewer.trackedEntity = undefined; // release the orbit-camera lock
    viewer.camera.flyHome(1.0);
  }, [cameraResetNonce]);

  return <div ref={containerRef} style={{ width: '100%', height: '100%' }} />;
}
