import { useEffect, useRef } from 'react';
import {
  Viewer,
  Ion,
  CzmlDataSource,
  ScreenSpaceEventHandler,
  ScreenSpaceEventType,
  Cartesian2,
  Cartographic,
  Entity,
  JulianDate,
  Math as CesiumMath,
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

    const stream = new CatalogStreamClient(CatalogStreamClient.defaultUrl(), STREAM_CONTRACT_VERSION, {
      onMessage: (msg) => {
        const ds = dataSourceRef.current;
        if (!ds) return;
        if (!indexBuiltRef.current && msg.epoch) {
          // Align the viewer clock with the first data epoch.
          viewer.clock.currentTime = JulianDate.fromIso8601(msg.epoch);
        }
        ds.process(msg.czml as unknown as object).then(() => {
          applyFilters(ds, useStore.getState().filters.constellations);
        });
        if (!indexBuiltRef.current) {
          useStore.getState().setCatalog(msg.satelliteCount, buildIndex(msg.czml));
          indexBuiltRef.current = true;
        }
      },
    });
    stream.connect();

    const handler = new ScreenSpaceEventHandler(viewer.scene.canvas);
    handler.setInputAction((click: { position: Cartesian2 }) => {
      const entity = pickSatellite(viewer, click.position);
      if (entity) {
        useStore.getState().setSelectedSatellite(describeEntity(entity, viewer.clock.currentTime));
      }
    }, ScreenSpaceEventType.LEFT_CLICK);

    return () => {
      stream.close();
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

  // --- focus request: fly the camera to a satellite ------------------------
  useEffect(() => {
    const viewer = viewerRef.current;
    const ds = dataSourceRef.current;
    if (!viewer || !ds || !focus) return;
    const entity = ds.entities.getById(`sat-${focus.noradId}`);
    if (entity) {
      viewer.flyTo(entity, { duration: 1.2 }).catch(() => {
        /* flyTo rejects if interrupted by another camera move — ignore */
      });
    }
  }, [focus]);

  return <div ref={containerRef} style={{ width: '100%', height: '100%' }} />;
}
