import { useEffect, useRef } from 'react';
import {
  Viewer,
  Ion,
  ArcType,
  CzmlDataSource,
  ScreenSpaceEventHandler,
  ScreenSpaceEventType,
  CallbackPositionProperty,
  CallbackProperty,
  Cartesian2,
  Cartesian3,
  Cartographic,
  Color,
  ConstantProperty,
  Entity,
  JulianDate,
  Math as CesiumMath,
  Matrix4,
  NearFarScalar,
  PolylineDashMaterialProperty,
  TrackingReferenceFrame,
  Transforms,
  defined,
} from 'cesium';
import 'cesium/Build/Cesium/Widgets/widgets.css';
import { useStore, type SatIndexEntry, type SelectedSatellite } from '../store/useStore';
import { constellationOf } from '../lib/constellations';
import { CatalogStreamClient } from '../stream/CatalogStreamClient';
import { ScenarioStreamClient } from '../stream/ScenarioStreamClient';
import { setRelativeData, clearRelativeData, parseRelativeMessage } from '../stream/relativeBuffer';
import { STREAM_CONTRACT_VERSION } from '../api/contract';

const cesiumToken = import.meta.env.VITE_CESIUM_ION_TOKEN;
if (cesiumToken) {
  Ion.defaultAccessToken = cesiumToken;
}

const HIT_PAD_PX = 5;

// Live time-travel tuning (Decision 21). A paused/stepped catalog snapshot uses
// a short window; playing forward from a traveled time uses a window that scales
// with the rate so prefetch has headroom, and prefetches when this many seconds
// of real time remain before the window edge.
const LIVE_FROZEN_WINDOW_S = 180;
const PREFETCH_LEAD_S = 2.5;
function travelWindowSeconds(rate: number): number {
  // Cover ≥ ~8 s of real time at the current rate; min the broadcast window, capped.
  return Math.min(2400, Math.max(180, Math.ceil(rate * 8)));
}

// Orbit-path toggle (single click). The toggle is debounced so a double-click
// (= two clicks, used for focus) never draws a path.
const CLICK_TOGGLE_DELAY_MS = 250;
// Re-request a shown orbit when the sim clock has advanced this far from its
// last fetch, so the path stays current as time runs (live update).
const ORBIT_REFRESH_SIM_MS = 30_000;
// Pulse cadence (ms) for the marker that flags a satellite whose orbit path is
// shown — a sonar-style ping so a previously-clicked satellite stays obvious
// after the yellow selection ring moves on to the next click.
const ORBIT_PULSE_PERIOD_MS = 1600;
// Round-robin palette so consecutive orbit paths are maximally distinct.
const ORBIT_PALETTE = [
  Color.CYAN,
  Color.ORANGE,
  Color.LIME,
  Color.MAGENTA,
  Color.YELLOW,
  Color.DEEPSKYBLUE,
  Color.HOTPINK,
  Color.SPRINGGREEN,
];

/** Parse "sat-25544" or "scn-25544" → 25544, or null. */
function noradFromEntityId(id: unknown): number | null {
  if (typeof id !== 'string') return null;
  if (!id.startsWith('sat-') && !id.startsWith('scn-')) return null;
  const n = Number.parseInt(id.slice(4), 10);
  return Number.isFinite(n) ? n : null;
}

/**
 * Like {@link noradFromEntityId}, but also resolves the orbit-path overlay
 * markers ("orbit-dot-25544" / "orbit-pulse-25544"). Those sit on top of the
 * satellite dot with depth-test disabled, so a click lands on them — without
 * this they'd swallow the pick and break inspect/focus on any satellite whose
 * path is shown.
 */
function noradFromPickableId(id: unknown): number | null {
  const direct = noradFromEntityId(id);
  if (direct !== null) return direct;
  if (typeof id !== 'string') return null;
  const m = /^orbit-(?:dot|pulse)-(\d+)$/.exec(id);
  return m ? Number.parseInt(m[1], 10) : null;
}

/**
 * The entity to track / inspect / focus for a NORAD id. Prefers the scenario
 * layer ("scn-<id>", visible during playback) over the catalog ("sat-<id>",
 * hidden while a scenario is active) so the ring, info panel, and camera follow
 * the on-screen dot — not a hidden catalog entity frozen at its live position.
 */
function entityForNorad(
  norad: number,
  scenarioSrc: CzmlDataSource | null,
  catalogSrc: CzmlDataSource | null,
): Entity | undefined {
  return (
    scenarioSrc?.entities.getById(`scn-${norad}`) ??
    catalogSrc?.entities.getById(`sat-${norad}`) ??
    undefined
  );
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
    // Scenario stream flags maneuvered roles so the panel marks the elements
    // pre-burn; catalog packets omit it (→ null → undefined).
    maneuvered: readNumberProp(entity, 'maneuvered', time) === 1,
  };
}

/** Pick a satellite entity at the click point, padding the search radius. */
function pickSatellite(
  viewer: Viewer,
  position: Cartesian2,
  scenarioSrc: CzmlDataSource | null,
  catalogSrc: CzmlDataSource | null,
): Entity | null {
  const tryPick = (x: number, y: number): Entity | null => {
    const picked = viewer.scene.pick(new Cartesian2(x, y));
    if (!(defined(picked) && picked.id instanceof Entity)) return null;
    const norad = noradFromPickableId(picked.id.id);
    if (norad === null) return null;
    // A click can land on the orbit-path overlay marker; map it back to the
    // canonical satellite entity so describeEntity/focus operate on the dot.
    return entityForNorad(norad, scenarioSrc, catalogSrc) ?? picked.id;
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
  const scenarioSourceRef = useRef<CzmlDataSource | null>(null);
  const indexBuiltRef = useRef(false);
  const clockSeededRef = useRef(false);
  // Orbit paths shown on the globe (click-to-toggle, multiple at once): which
  // NORAD ids, their round-robin color, the sim-time each was last fetched at
  // (for live refresh), and the next palette index to hand out.
  const orbitsRef = useRef<{
    shown: Set<number>;
    colors: Map<number, Color>;
    lastFetchMs: Map<number, number>;
    nextColor: number;
  }>({ shown: new Set(), colors: new Map(), lastFetchMs: new Map(), nextColor: 0 });

  // Reactive store slices that drive imperative Cesium updates.
  const activeConstellations = useStore((s) => s.filters.constellations);
  const focus = useStore((s) => s.focus);
  const cameraResetNonce = useStore((s) => s.cameraResetNonce);
  const loadedScenarioId = useStore((s) => s.loadedScenario?.id ?? null);
  const scenarioReloadNonce = useStore((s) => s.scenarioReloadNonce);
  const showCatalogInScenario = useStore((s) => s.showCatalogInScenario);

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
    // Sever Cesium's autonomous clock (Phase 4, Decision 11): the store's
    // clockEngine is the sole time authority. We copy store.currentTime into
    // viewer.clock every frame (preRender below); with shouldAnimate=false +
    // multiplier=0, clock.tick() leaves that value untouched, so the scene
    // renders at the shared simulation time. Cap Cesium at 30 fps (SRS §5.1.2).
    viewer.clock.shouldAnimate = false;
    viewer.clock.multiplier = 0;
    viewer.targetFrameRate = 30;
    // Decision 18: we own the camera. Remove Cesium's default double-click
    // handler (pickAndTrackObject), which snaps + auto-zooms to a satellite's
    // bounding sphere — disastrous for a scenario entity whose orbit-path
    // bounding sphere spans the whole orbit ("hard to zoom out"). Our custom
    // double-click handler drives the smooth, zoom-preserving focus instead.
    viewer.screenSpaceEventHandler.removeInputAction(ScreenSpaceEventType.LEFT_DOUBLE_CLICK);
    viewerRef.current = viewer;

    const dataSource = new CzmlDataSource('catalog');
    viewer.dataSources.add(dataSource);
    dataSourceRef.current = dataSource;

    // Scenario layer (Phase 4): chief + deputies of the loaded scenario, fed by
    // the per-scenario stream. Separate source so chunk processing/clearing
    // never touches the catalog. Packet ids are "scn-<norad>" (no collision).
    const scenarioDataSource = new CzmlDataSource('scenario');
    viewer.dataSources.add(scenarioDataSource);
    scenarioSourceRef.current = scenarioDataSource;

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
        const e = entityForNorad(sel.noradId, scenarioSourceRef.current, dataSourceRef.current);
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
    // Live time-travel state (Decision 21): the coverage window of the latest
    // requested snapshot, and whether one is in flight (so playback prefetches
    // the next window without spamming requests).
    let coverStartMs = 0;
    let coverEndMs = 0;
    let snapshotPending = false;
    let snapshotSafetyTimer = 0;
    const stream = new CatalogStreamClient(CatalogStreamClient.defaultUrl(), STREAM_CONTRACT_VERSION, {
      onMessage: (msg) => {
        const ds = dataSourceRef.current;
        if (!ds) return;
        const st = useStore.getState();
        // A "catalog-snapshot" is an on-demand time-travel reply (Decision 21):
        // apply it always. The live "catalog-czml" broadcast is applied only
        // while actually live (no scenario + catalogLive) — otherwise it would
        // yank the frozen/traveled view back to "now".
        const isSnapshot = msg.type === 'catalog-snapshot';
        if (isSnapshot) {
          snapshotPending = false;
          window.clearTimeout(snapshotSafetyTimer);
        }

        // Build the searchable index + seed the live clock once, from the first
        // live broadcast (≈ server "now"); never reseed over a loaded scenario.
        if (!isSnapshot && !indexBuiltRef.current) {
          st.setCatalog(msg.satelliteCount, buildIndex(msg.czml));
          indexBuiltRef.current = true;
        }
        if (!isSnapshot && !clockSeededRef.current && msg.epoch && !st.loadedScenario) {
          st.goLive(new Date(msg.epoch));
          clockSeededRef.current = true;
        }

        const live = !st.loadedScenario && st.catalogLive;
        if (!isSnapshot && !live) return;

        processChain = processChain
          .then(() => ds.process(msg.czml as unknown as object))
          .then(() => {
            applyFilters(ds, useStore.getState().filters.constellations);
          })
          .catch((err) => {
            // eslint-disable-next-line no-console
            console.error('Catalog CZML chunk failed to process; skipping it', err);
          });
      },
      onOrbit: (norad, cartesian) => addOrbitPath(norad, cartesian),
    });

    // --- orbit paths (single-click toggle; multiple at once) ----------------
    // A dashed polyline of one orbital period (ECEF positions from the backend),
    // drawn in viewer.entities under id "orbit-<norad>". The toggle just tracks
    // membership + asks the backend; the polyline is materialised when the
    // response arrives (and only if still toggled on — so a cancelled toggle,
    // e.g. from a double-click, never leaves a stray path).
    // Round-robin: hand out the next palette color, preferring one not currently
    // in use so simultaneous paths stay maximally distinct.
    function assignOrbitColor(norad: number): Color {
      const orbits = orbitsRef.current;
      const existing = orbits.colors.get(norad);
      if (existing) return existing;
      const inUse = new Set(orbits.colors.values());
      let chosen = ORBIT_PALETTE[orbits.nextColor % ORBIT_PALETTE.length];
      for (let i = 0; i < ORBIT_PALETTE.length; i++) {
        const c = ORBIT_PALETTE[(orbits.nextColor + i) % ORBIT_PALETTE.length];
        if (!inUse.has(c)) {
          chosen = c;
          orbits.nextColor = orbits.nextColor + i + 1;
          break;
        }
        if (i === ORBIT_PALETTE.length - 1) orbits.nextColor += 1; // all in use → just advance
      }
      orbits.colors.set(norad, chosen);
      return chosen;
    }
    function addOrbitPath(norad: number, cartesian: number[]): void {
      if (!orbitsRef.current.shown.has(norad)) return; // toggled off before arrival
      const positions: Cartesian3[] = [];
      for (let i = 0; i + 2 < cartesian.length; i += 3) {
        positions.push(new Cartesian3(cartesian[i], cartesian[i + 1], cartesian[i + 2]));
      }
      if (positions.length < 2) return;
      const color = orbitsRef.current.colors.get(norad) ?? ORBIT_PALETTE[0];
      viewer.entities.removeById(`orbit-${norad}`); // replace (live refresh) or first draw
      viewer.entities.add({
        id: `orbit-${norad}`,
        polyline: {
          positions,
          width: 2,
          arcType: ArcType.NONE, // straight segments at altitude, not clamped to the surface
          // Fine dotted line matching the scenario trails (dashLength 6, was 16):
          // reads as a crisp, near-solid curve rather than a coarse dashed one.
          material: new PolylineDashMaterialProperty({ color: color.withAlpha(0.95), dashLength: 6 }),
        },
      });
    }
    // A persistent, color-matched marker on every satellite whose orbit path is
    // shown, so it stays findable after the (single) selection ring moves to the
    // next click: a solid core dot in the path's color + a sonar-ping ring that
    // expands and fades on a loop. Both live-track the satellite via a position
    // callback (like the selection ring) and sit in viewer.entities, so catalog
    // chunk processing never wipes them.
    function orbitMarkerPosition(norad: number): CallbackPositionProperty {
      return new CallbackPositionProperty(
        () =>
          entityForNorad(norad, scenarioSourceRef.current, dataSourceRef.current)
            ?.position?.getValue(viewer.clock.currentTime) ?? undefined,
        false,
      );
    }
    function addOrbitMarker(norad: number, color: Color): void {
      viewer.entities.add({
        id: `orbit-dot-${norad}`,
        position: orbitMarkerPosition(norad),
        point: {
          pixelSize: 7,
          color: color.withAlpha(1.0),
          outlineColor: Color.WHITE.withAlpha(0.85),
          outlineWidth: 1,
          // Grow as the camera approaches (mirrors the selection ring) so zoom
          // has feedback; the underlying catalog dot is fixed screen-space size.
          scaleByDistance: new NearFarScalar(100_000, 2.0, 2_000_000, 1.0),
          disableDepthTestDistance: Number.POSITIVE_INFINITY, // always visible
        },
      });
      viewer.entities.add({
        id: `orbit-pulse-${norad}`,
        position: orbitMarkerPosition(norad),
        point: {
          // Expanding ring (transparent fill, colored outline that fades out).
          pixelSize: new CallbackProperty(() => {
            const p = (performance.now() % ORBIT_PULSE_PERIOD_MS) / ORBIT_PULSE_PERIOD_MS;
            return 10 + 22 * p;
          }, false),
          color: Color.TRANSPARENT,
          outlineColor: new CallbackProperty(() => {
            const p = (performance.now() % ORBIT_PULSE_PERIOD_MS) / ORBIT_PULSE_PERIOD_MS;
            return color.withAlpha(0.8 * (1 - p));
          }, false),
          outlineWidth: 2,
          disableDepthTestDistance: Number.POSITIVE_INFINITY,
        },
      });
    }
    // Remove an orbit's polyline AND its pulsing marker together.
    function removeOrbitVisuals(norad: number): void {
      viewer.entities.removeById(`orbit-${norad}`);
      viewer.entities.removeById(`orbit-dot-${norad}`);
      viewer.entities.removeById(`orbit-pulse-${norad}`);
    }
    // Request a shown orbit at the current sim clock and record when (for refresh).
    function fetchOrbit(norad: number): void {
      orbitsRef.current.lastFetchMs.set(norad, useStore.getState().currentTime.getTime());
      stream.requestOrbit(norad, useStore.getState().currentTime.toISOString());
    }
    function toggleOrbitPath(norad: number): void {
      const orbits = orbitsRef.current;
      if (orbits.shown.has(norad)) {
        orbits.shown.delete(norad);
        orbits.colors.delete(norad);
        orbits.lastFetchMs.delete(norad);
        removeOrbitVisuals(norad);
      } else {
        orbits.shown.add(norad);
        const color = assignOrbitColor(norad);
        addOrbitMarker(norad, color); // pulsing marker appears immediately
        fetchOrbit(norad); // path polyline appears when the response lands
      }
    }
    // In scenario mode the chief/deputy trails are part of the CZML and on by
    // default; clicking a satellite toggles its own trail's visibility.
    function toggleScenarioPath(norad: number): void {
      const ent = scenarioSourceRef.current?.entities.getById(`scn-${norad}`);
      if (!ent?.path) return;
      const visible = ent.path.show?.getValue(viewer.clock.currentTime) ?? true;
      ent.path.show = new ConstantProperty(!visible);
    }
    // Defer connect to the next macrotask so React StrictMode's dev
    // mount→unmount→remount doesn't open then immediately close a socket
    // (which logs "WebSocket is closed before the connection is established").
    // The cancelled first scheduling never connects; only the remount's does.
    let connectCancelled = false;
    const connectTimer = window.setTimeout(() => {
      if (!connectCancelled) stream.connect();
    }, 0);

    // Live time-travel (Decision 21). A non-React store subscription (so the
    // clock ticking can't re-render this component) drives on-demand catalog
    // snapshots:
    //   - frozen (paused/stepped/scrubbed): one snapshot at the chosen instant;
    //   - playing-from-a-traveled-time: ROLLING prefetched snapshots — a window
    //     scaled to the rate, re-requested before the clock reaches its edge so
    //     motion stays continuous (up to the 100× live cap);
    //   - returning to live (or closing a scenario): an immediate "now" snapshot,
    //     then the live broadcast resumes.
    let snapshotTimer = 0;
    const requestCatalogSnapshot = (centerMs: number, windowSec: number, direction: 1 | -1) => {
      // Cover the travel direction: forward [t, t+w], reverse [t-w, t].
      const epochMs = direction < 0 ? centerMs - windowSec * 1000 : centerMs;
      coverStartMs = epochMs;
      coverEndMs = epochMs + windowSec * 1000;
      snapshotPending = true;
      window.clearTimeout(snapshotSafetyTimer);
      snapshotSafetyTimer = window.setTimeout(() => {
        snapshotPending = false; // unstick if a reply never arrives
      }, 5000);
      stream.seek(new Date(epochMs).toISOString(), windowSec);
    };
    // Re-fetch each explicitly-shown orbit path once the sim clock drifts past
    // the threshold (lastFetchMs updated at request time → no duplicate requests).
    const refreshShownOrbits = (nowMs: number) => {
      const orbits = orbitsRef.current;
      if (orbits.shown.size === 0) return;
      orbits.shown.forEach((norad) => {
        if (Math.abs(nowMs - (orbits.lastFetchMs.get(norad) ?? 0)) > ORBIT_REFRESH_SIM_MS) {
          fetchOrbit(norad);
        }
      });
    };

    const unsubscribeTravel = useStore.subscribe((state, prev) => {
      // Tier A — catalog auto-refresh while composing over a scenario (extends
      // Decision 21). A scenario drives the clock, so the live broadcast can't
      // represent its epoch; when the user reveals the catalog we refresh it with
      // on-demand snapshots, but only when the clock SETTLES (reveal / pause /
      // step / scrub) — never per playback tick, since a full-catalog snapshot is
      // the bounded per-user compute path and scenarios play up to 10000×.
      if (state.loadedScenario) {
        if (!state.showCatalogInScenario) return; // catalog hidden → nothing to do
        const clockMs = state.currentTime.getTime();
        const dir = state.direction;
        const justRevealed = !prev.showCatalogInScenario;
        const settled = !state.isPlaying && (prev.isPlaying || state.currentTime !== prev.currentTime);
        if (justRevealed) {
          // Reveal: snapshot immediately so the dots jump to the scenario time.
          window.clearTimeout(snapshotTimer);
          requestCatalogSnapshot(clockMs, LIVE_FROZEN_WINDOW_S, dir);
          refreshShownOrbits(clockMs);
        } else if (settled) {
          // Pause / step / scrub: debounce a snapshot at the settled instant.
          window.clearTimeout(snapshotTimer);
          snapshotTimer = window.setTimeout(() => {
            requestCatalogSnapshot(clockMs, LIVE_FROZEN_WINDOW_S, dir);
            refreshShownOrbits(clockMs);
          }, 120);
        }
        return;
      }

      // Live-refresh shown orbit paths (catalog mode).
      refreshShownOrbits(state.currentTime.getTime());

      const justWentLive = state.catalogLive && !prev.catalogLive;
      const justClosedScenario = !state.loadedScenario && !!prev.loadedScenario;
      if (justWentLive || justClosedScenario) {
        requestCatalogSnapshot(Date.now(), LIVE_FROZEN_WINDOW_S, 1); // instant "now"
        return;
      }
      if (!state.catalogLive && prev.catalogLive) {
        // Just left live (pause / step / scrub) → freeze at the current instant.
        requestCatalogSnapshot(state.currentTime.getTime(), LIVE_FROZEN_WINDOW_S, state.direction);
        return;
      }
      if (state.catalogLive) return; // live: the shared broadcast drives the globe

      const clockMs = state.currentTime.getTime();
      if (!state.isPlaying) {
        // Frozen: debounce a snapshot at the scrubbed/stepped instant.
        if (state.currentTime !== prev.currentTime) {
          window.clearTimeout(snapshotTimer);
          const dir = state.direction;
          snapshotTimer = window.setTimeout(
            () => requestCatalogSnapshot(clockMs, LIVE_FROZEN_WINDOW_S, dir),
            120,
          );
        }
        return;
      }
      // Playing forward/backward from a traveled time → rolling prefetch.
      const rate = state.rate;
      const dir = state.direction;
      const leadMs = PREFETCH_LEAD_S * 1000 * rate;
      const remainingMs = dir < 0 ? clockMs - coverStartMs : coverEndMs - clockMs;
      const outside = clockMs < coverStartMs || clockMs > coverEndMs;
      if (!snapshotPending && (outside || remainingMs < leadMs)) {
        requestCatalogSnapshot(clockMs, travelWindowSeconds(rate), dir);
      }
    });

    // Drive Cesium from the shared simulation clock every frame, and keep the
    // selected satellite's lat/lon/alt live. This runs in preRender (not
    // clock.onTick, which stops firing once shouldAnimate=false) — it's the seam
    // that makes both views read one clock (Decision 11). The selection-ring
    // CallbackPositionProperty and the Decision-18 focus/track code read
    // viewer.clock.currentTime, kept fresh here.
    const clockScratch = new JulianDate();
    let lastPosUpdate = 0;
    const removeClockSync = viewer.scene.preRender.addEventListener(() => {
      JulianDate.fromDate(useStore.getState().currentTime, clockScratch);
      viewer.clock.currentTime = clockScratch;

      const selected = useStore.getState().selectedSatellite;
      if (!selected) return;
      const now = performance.now();
      if (now - lastPosUpdate < 250) return;
      lastPosUpdate = now;
      const entity = entityForNorad(selected.noradId, scenarioSourceRef.current, dataSourceRef.current);
      const pos = entity?.position?.getValue(viewer.clock.currentTime);
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
    // Single click = inspect (select + info panel + ring, no camera move) AND
    // toggle the satellite's orbit path on/off. The path toggle is debounced and
    // cancelled by a double-click, so double-click stays pure focus (never a path).
    let toggleTimer = 0;
    handler.setInputAction((click: { position: Cartesian2 }) => {
      const entity = pickSatellite(viewer, click.position, scenarioSourceRef.current, dataSourceRef.current);
      if (!entity) return;
      useStore.getState().setSelectedSatellite(describeEntity(entity, viewer.clock.currentTime));
      const norad = noradFromEntityId(entity.id);
      if (norad === null) return;
      // Single-click toggles the orbit path based on WHAT was clicked, not just
      // whether a scenario is loaded: a scenario member (scn-<id>) toggles its
      // built-in CZML trail; a catalog satellite (sat-<id>) — e.g. one revealed
      // via "Show catalog" mid-scenario — uses the on-demand catalog path +
      // pulsing marker. (pickSatellite/entityForNorad prefer the scn- entity, so
      // members resolve correctly even with the catalog shown over them.)
      const isScenarioEntity = typeof entity.id === 'string' && entity.id.startsWith('scn-');
      window.clearTimeout(toggleTimer);
      toggleTimer = window.setTimeout(() => {
        if (isScenarioEntity) toggleScenarioPath(norad);
        else toggleOrbitPath(norad);
      }, CLICK_TOGGLE_DELAY_MS);
    }, ScreenSpaceEventType.LEFT_CLICK);
    // Double click = focus (track the satellite: centered, zoom-preserving).
    // Cancel any pending single-click path toggle so it doesn't fire too.
    handler.setInputAction((click: { position: Cartesian2 }) => {
      window.clearTimeout(toggleTimer);
      const entity = pickSatellite(viewer, click.position, scenarioSourceRef.current, dataSourceRef.current);
      if (!entity) return;
      const norad = noradFromEntityId(entity.id);
      if (norad !== null) useStore.getState().requestFocus(norad);
    }, ScreenSpaceEventType.LEFT_DOUBLE_CLICK);

    return () => {
      connectCancelled = true;
      window.clearTimeout(connectTimer);
      window.clearTimeout(snapshotTimer);
      window.clearTimeout(snapshotSafetyTimer);
      window.clearTimeout(toggleTimer);
      unsubscribeTravel();
      stream.close();
      removeClockSync();
      handler.destroy();
      viewer.destroy();
      viewerRef.current = null;
      dataSourceRef.current = null;
      scenarioSourceRef.current = null;
      indexBuiltRef.current = false;
      clockSeededRef.current = false;
    };
  }, []);

  // --- filters change: re-apply show/hide ----------------------------------
  useEffect(() => {
    const ds = dataSourceRef.current;
    if (ds) applyFilters(ds, activeConstellations);
  }, [activeConstellations]);

  // --- loaded scenario: open its stream + dim the catalog -------------------
  // The catalog feed is a live ~180 s window around "now"; it can't represent a
  // scenario's (arbitrary/historical) epoch — its dots would hold at the window
  // edge. So while a scenario plays we hide the catalog layer and show only the
  // scenario's chief + deputies, restoring the catalog when the scenario closes.
  useEffect(() => {
    const viewer = viewerRef.current;
    const scenarioDs = scenarioSourceRef.current;
    if (!scenarioDs) return;

    // Don't leave the camera tracking a scenario entity we're about to remove.
    const releaseScenarioTracking = () => {
      const tracked = viewer?.trackedEntity;
      if (viewer && tracked && typeof tracked.id === 'string' && tracked.id.startsWith('scn-')) {
        viewer.trackedEntity = undefined;
      }
    };

    if (!loadedScenarioId) {
      releaseScenarioTracking();
      scenarioDs.entities.removeAll();
      clearRelativeData(); // no scenario → proximity view has nothing to show
      return;
    }

    // Catalog visibility is owned by a dedicated effect (showCatalogInScenario).
    // Catalog orbit paths (+ their pulsing markers) belong to catalog dots —
    // clear them on scenario load.
    const orbits = orbitsRef.current;
    if (viewer) {
      orbits.shown.forEach((n) => {
        viewer.entities.removeById(`orbit-${n}`);
        viewer.entities.removeById(`orbit-dot-${n}`);
        viewer.entities.removeById(`orbit-pulse-${n}`);
      });
    }
    orbits.shown.clear();
    orbits.colors.clear();
    orbits.lastFetchMs.clear();
    scenarioDs.entities.removeAll();

    let processChain: Promise<void> = Promise.resolve();
    const client = new ScenarioStreamClient(
      ScenarioStreamClient.urlForScenario(loadedScenarioId),
      STREAM_CONTRACT_VERSION,
      {
        onCzml: (czml) => {
          processChain = processChain
            .then(() => scenarioDs.process(czml as unknown as object))
            .then(() => undefined)
            .catch((err) => {
              // eslint-disable-next-line no-console
              console.error('Scenario CZML chunk failed to process; skipping it', err);
            });
        },
        // Feed the proximity view's relative-state buffer (Phase 4B). One socket
        // serves both viewports; ProximityView reads this buffer each frame.
        onRelative: (msg) => {
          const data = parseRelativeMessage(msg);
          if (data) setRelativeData(data);
        },
        // Surface a refusal so a blank proximity view isn't silent. 4422 →
        // "rejected" (e.g. a spacecraft decays/maneuvers below the surface over
        // the time range); cleared once a stream (re)connects.
        onStatus: (status) => {
          const { setScenarioStreamError } = useStore.getState();
          if (status === 'rejected') {
            setScenarioStreamError(
              "This scenario can't be streamed — a spacecraft leaves the propagation model's" +
                ' valid domain over the time range (orbital decay, or a maneuver puts it below the' +
                ' surface). Shorten the time range or revise the maneuver.',
            );
          } else if (status === 'version-mismatch') {
            setScenarioStreamError('Stream version mismatch — reload the page to update the client.');
          } else if (status === 'open' || status === 'connecting') {
            setScenarioStreamError(null);
          }
        },
      },
    );
    // Defer connect one macrotask (matches the catalog client) so StrictMode's
    // dev mount→unmount→remount doesn't open-then-close a socket.
    let connectCancelled = false;
    const connectTimer = window.setTimeout(() => {
      if (!connectCancelled) client.connect();
    }, 0);

    return () => {
      connectCancelled = true;
      window.clearTimeout(connectTimer);
      client.close();
      releaseScenarioTracking();
      scenarioDs.entities.removeAll();
      clearRelativeData(); // drop relative samples on (re)connect/close
    };
    // scenarioReloadNonce forces a reconnect when the same scenario is re-loaded
    // (e.g. after a time-range edit) so the stream recomputes for the new window.
  }, [loadedScenarioId, scenarioReloadNonce]);

  // --- catalog layer visibility --------------------------------------------
  // Shown by default; hidden while a scenario plays (its dots can't represent the
  // scenario epoch — they'd hold at the live-window edge), UNLESS the user toggles
  // it back on to compose by picking a real satellite (US-SCN-05). Centralized
  // here so the scenario-stream effect doesn't fight it.
  useEffect(() => {
    const catalogDs = dataSourceRef.current;
    if (!catalogDs) return;
    catalogDs.show = !loadedScenarioId || showCatalogInScenario;
  }, [loadedScenarioId, showCatalogInScenario]);

  // --- focus request: blend into the LIVE tracked pose (double-click/search)
  // Two parts make this twist-free:
  //  (1) Frame match. Cesium's trackedEntity AUTO-SELECTS a velocity-aligned
  //      (VVLH) frame for fast objects like satellites (EntityView.js lines
  //      165-214), but our blend computes the target with eastNorthUpToFixedFrame
  //      (ENU). Converging to an ENU pose then engaging in a VVLH frame is what
  //      produced the end-twist. We force the entity's tracking frame to ENU
  //      (TrackingReferenceFrame.ENU → EntityView uses eastNorthUpToFixedFrame),
  //      so tracking engages in the exact frame the blend targets.
  //  (2) Live convergence. Each frame we compute the exact pose tracking would
  //      have RIGHT NOW (the same camera.lookAtTransform(enu, offset) call
  //      EntityView makes) and blend the camera from its start pose toward it
  //      over ~0.8s. At t=1 the camera already IS the tracked pose, so engaging
  //      trackedEntity changes nothing. Distance/zoom preserved (offset = the
  //      current ENU offset).
  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || !focus) return;
    const entity = entityForNorad(focus.noradId, scenarioSourceRef.current, dataSourceRef.current);
    if (!entity) return;
    const camera = viewer.camera;
    const scene = viewer.scene;
    const now0 = viewer.clock.currentTime;
    useStore.getState().setSelectedSatellite(describeEntity(entity, now0));
    const satNow = entity.position?.getValue(now0);
    if (!satNow) return;
    const targetNorad = focus.noradId;

    viewer.trackedEntity = undefined; // release any prior tracking
    // Track in ENU (not the auto-selected VVLH) so the engage pose matches the
    // ENU pose our blend converges to — the key to no twist on hand-off.
    entity.trackingReferenceFrame = TrackingReferenceFrame.ENU;

    // Fixed ENU-local offset = current distance/zoom, and the viewFrom we track with.
    const enu0 = Transforms.eastNorthUpToFixedFrame(satNow);
    const inv0 = Matrix4.inverseTransformation(enu0, new Matrix4());
    const offset = Matrix4.multiplyByPoint(inv0, camera.positionWC, new Cartesian3());

    // Pose to blend FROM (captured once).
    const startPos = Cartesian3.clone(camera.positionWC, new Cartesian3());
    const startDir = Cartesian3.clone(camera.directionWC, new Cartesian3());
    const startUp = Cartesian3.clone(camera.upWC, new Cartesian3());

    const durationMs = 800;
    let startMs = -1;
    let removeListener: (() => void) | null = null;

    const onPreRender = (_scene: unknown, time: JulianDate) => {
      const tNow = performance.now();
      if (startMs < 0) startMs = tNow;
      const t = Math.min(1, (tNow - startMs) / durationMs);
      const eased = t * t * (3 - 2 * t); // smoothstep

      const ent = entityForNorad(targetNorad, scenarioSourceRef.current, dataSourceRef.current);
      const sp = ent?.position?.getValue(time);
      if (!ent || !sp) return;

      // Live tracked pose for the satellite's CURRENT position (Cesium's math).
      const enu = Transforms.eastNorthUpToFixedFrame(sp);
      camera.lookAtTransform(enu, offset);
      const trackPos = Cartesian3.clone(camera.positionWC, new Cartesian3());
      const trackDir = Cartesian3.clone(camera.directionWC, new Cartesian3());
      const trackUp = Cartesian3.clone(camera.upWC, new Cartesian3());
      camera.lookAtTransform(Matrix4.IDENTITY); // release before we set our blend

      // Blend start → live tracked pose; orthonormalize the orientation.
      const bPos = Cartesian3.lerp(startPos, trackPos, eased, new Cartesian3());
      const bDir = Cartesian3.normalize(
        Cartesian3.lerp(startDir, trackDir, eased, new Cartesian3()),
        new Cartesian3(),
      );
      let bUp = Cartesian3.normalize(
        Cartesian3.lerp(startUp, trackUp, eased, new Cartesian3()),
        new Cartesian3(),
      );
      const right = Cartesian3.cross(bDir, bUp, new Cartesian3());
      bUp = Cartesian3.normalize(Cartesian3.cross(right, bDir, new Cartesian3()), new Cartesian3());
      camera.setView({ destination: bPos, orientation: { direction: bDir, up: bUp } });

      if (t >= 1) {
        if (removeListener) {
          removeListener();
          removeListener = null;
        }
        if (useStore.getState().selectedSatellite?.noradId === targetNorad) {
          ent.viewFrom = new ConstantProperty(offset);
          viewer.trackedEntity = ent; // engages at the exact pose we just set
          window.setTimeout(() => {
            const vv = viewerRef.current;
            if (vv && !vv.isDestroyed() && vv.trackedEntity === ent) ent.viewFrom = undefined;
          }, 200);
        }
      }
    };
    removeListener = scene.preRender.addEventListener(onPreRender);
    return () => {
      if (removeListener) removeListener();
    };
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
