import { create } from 'zustand';
import { CONSTELLATIONS, type Constellation } from '../lib/constellations';
import { api } from '../api/client';
import type { components } from '../api/schema';

/** A saved scenario as listed by GET /scenarios (generated from the backend contract). */
export type ScenarioSummary = components['schemas']['ScenarioSummary'];
type ScenarioRequest = components['schemas']['ScenarioRequest'];
type ScenarioBodyT = components['schemas']['ScenarioBody'];
/** Catalog conjunction screening result (Phase 8, US-EVT-02 / UC-7). */
export type ScreeningResult = components['schemas']['ScreeningResult'];
export type ConjunctionResult = components['schemas']['ConjunctionResult'];
/** Rendezvous arrival×rev ΔV search (Phase 9A, US-MAN-03). */
export type RendezvousSearchResult = components['schemas']['RendezvousSearchResult'];
export type DvCell = components['schemas']['DvCell'];
/** Monte Carlo dispersion result (Phase 9C, UC-6, US-MC-01/02). */
export type MonteCarloResult = components['schemas']['MonteCarloResult'];
export type EllipsoidSample = components['schemas']['EllipsoidSample'];
/** RF/optical link-budget inputs (Phase 9D, US-EVT-05). */
export interface LinkBudgetParams {
  kind: string;
  eirpDbw: number;
  gOverTdbK: number;
  frequencyGhz: number;
  bandwidthHz: number;
  thresholdDb: number;
}
/** Dispersion inputs (1-σ initial-state uncertainty + maneuver execution error). */
export interface MonteCarloParams {
  sampleCount: number;
  seed: number;
  posSigmaM: number;
  velSigmaMs: number;
  dvMagFrac: number;
  dvPointingDeg: number;
}

/** The scenario currently loaded for playback (Phase 4): its id, name, and body. */
export interface LoadedScenario {
  id: string;
  name: string;
  body: ScenarioBodyT;
}

/** The simulation clock's playback window (Decision 11). Null = catalog "live" regime. */
export interface ClockBounds {
  start: Date;
  end: Date;
}

export interface Filters {
  /** Constellations currently shown. A constellation NOT in this set is hidden. */
  constellations: string[];
  showDebris: boolean;
  showRocketBodies: boolean;
}

/** One entry in the in-memory catalog index, built from the CZML stream. */
export interface SatIndexEntry {
  noradId: number;
  name: string;
  constellation: Constellation | null;
}

/** Details of the currently inspected satellite (click-to-inspect, UC-1). */
export interface SelectedSatellite {
  noradId: number;
  name: string;
  inclinationDeg: number | null;
  periodMinutes: number | null;
  latitudeDeg: number;
  longitudeDeg: number;
  altitudeKm: number;
  /** Scenario sat whose orbit changes at a maneuver — inclination/period are the
   *  pre-burn seed-orbit values (the client marks them). Undefined for catalog sats. */
  maneuvered?: boolean;
}

/** A camera-focus request (search → fly-to). `nonce` retriggers the same id. */
export interface FocusRequest {
  noradId: number;
  nonce: number;
}

/**
 * The current state of the scenario being authored (Decision 14, US-SCN-02).
 * Role assignment is wired in Phase 3; the slice exists now so the catalog
 * click flow has somewhere to write.
 */
export interface Composer {
  chiefId: number | null;
  deputyIds: number[];
  scenarioId: string | null;
  isDirty: boolean;
  /** Scenario time window (ISO-8601 UTC). null until set/loaded → save defaults to now…+24h. */
  start: string | null;
  end: string | null;
  /** Propagator fidelity: 'sgp4' | 'numerical' | 'cw' (US-PROP-03, Phase 5C). */
  fidelity: string;
}

export interface State {
  // --- Simulation clock (Decision 11; US-VIEW-02/03). The clockEngine rAF loop
  // is the SOLE writer of currentTime during playback; views only read it.
  currentTime: Date;
  isPlaying: boolean;
  rate: number;                 // playback multiplier magnitude (≥0); 1 = realtime
  direction: 1 | -1;            // forward / reverse
  bounds: ClockBounds | null;   // active time window (scenario range, or the live ± travel window)
  // Catalog-mode only: true = following real time (live broadcast); false =
  // "frozen" at currentTime, showing an on-demand propagated snapshot (Decision 21).
  catalogLive: boolean;

  filters: Filters;
  composer: Composer;

  // The scenario currently loaded for playback (null = catalog-only / live).
  loadedScenario: LoadedScenario | null;
  // Bumped on every (re)load so the Globe reopens the scenario stream even when
  // the id is unchanged — e.g. after editing the time range of a loaded scenario.
  scenarioReloadNonce: number;
  // Non-null when the per-scenario stream was refused (e.g. a spacecraft decays /
  // maneuvers below the surface → 4422). Surfaced as a banner so a blank proximity
  // view isn't silent. Cleared when a stream (re)connects.
  scenarioStreamError: string | null;
  setScenarioStreamError: (message: string | null) => void;

  // Saved scenarios (from the backend; US-SCN-03/11/12)
  scenarios: ScenarioSummary[];

  // Catalog (from the backend stream)
  catalogTotal: number;
  catalogIndex: SatIndexEntry[];

  // Inspection + camera focus
  selectedSatellite: SelectedSatellite | null;
  focus: FocusRequest | null;
  // Proximity-view camera focus request (composer click → three.js camera rides
  // that craft). Separate from `focus` (the Cesium globe focus) so a globe
  // double-click doesn't also move the proximity camera. `nonce` retriggers.
  proximityFocus: FocusRequest | null;
  cameraResetNonce: number;
  // While a scenario is loaded the catalog layer is hidden (its live ~180 s
  // window can't represent the scenario epoch). This toggle re-shows it so the
  // user can pick a real satellite to add as a deputy mid-edit. Positions are
  // approximate when the scenario time is far from "now" (dots hold at the
  // live-window edge) — fine for composition.
  showCatalogInScenario: boolean;

  // Monte Carlo dispersion (Phase 9C, UC-6). Static once computed → lives in Zustand
  // (unlike the per-frame stream); the proximity view reads it to draw the cloud +
  // ellipsoids. `visible` toggles the overlay.
  monteCarlo: MonteCarloResult | null;
  monteCarloVisible: boolean;

  // Clock control (frontend owns playback — Decision 11)
  setCurrentTime: (t: Date) => void;
  togglePlay: () => void;
  setRate: (rate: number) => void;
  toggleDirection: () => void;
  seek: (t: Date) => void;
  step: (deltaSec: number) => void;
  resetClock: () => void;
  setBounds: (start: Date, end: Date) => void;
  /** Catalog mode: snap back to real time + the live broadcast (re-centers the travel window). */
  goLive: (at?: Date) => void;
  /** Catalog mode play/pause: pause also freezes the catalog; play runs from the current instant. */
  toggleCatalogPlayback: () => void;

  toggleConstellation: (name: string) => void;
  setCatalog: (total: number, index: SatIndexEntry[]) => void;
  setSelectedSatellite: (sat: SelectedSatellite | null) => void;
  updateSelectedPosition: (latitudeDeg: number, longitudeDeg: number, altitudeKm: number) => void;
  requestFocus: (noradId: number) => void;
  requestProximityFocus: (noradId: number) => void;
  resetCamera: () => void;
  setShowCatalogInScenario: (show: boolean) => void;

  // Composer actions
  setChief: (id: number) => void;
  addDeputy: (id: number) => void;
  /** Promote an existing deputy to chief, demoting the current chief to a deputy (swap). */
  promoteToChief: (id: number) => void;
  removeFromScenario: (id: number) => void;
  setComposerTimeRange: (start: string, end: string) => void;
  setComposerFidelity: (fidelity: string) => void;
  clearComposer: () => void;

  // Scenario CRUD (calls the generated client)
  loadScenarios: () => Promise<void>;
  /** Create (no scenarioId yet) or save-a-new-version (existing id), optionally renaming. */
  saveScenario: (name: string) => Promise<void>;
  loadScenario: (id: string) => Promise<void>;
  deleteScenario: (id: string) => Promise<void>;
  /** Import a measured ephemeris (server-side WOD CSV path) → new scenario, then load it. */
  importMeasuredScenario: (path: string, noradId?: number) => Promise<void>;
  /** Stop streaming the loaded scenario and return to the live catalog regime. */
  closeScenario: () => void;

  // Maneuvers (Phase 5B, US-MAN-01). Edit → new version + audit (backend) → reload
  // the loaded scenario so the stream re-propagates with the maneuver applied.
  addManeuver: (deputyNoradId: number, epoch: string, dv: { r: number; i: number; c: number }) => Promise<void>;
  removeManeuver: (maneuverId: string) => Promise<void>;
  // Maneuver templates (Phase 5C, US-MAN-02/03). Compute ΔV server-side, insert,
  // and reload (re-propagate). Return an error message on failure (else null).
  applyHohmann: (deputyNoradId: number, targetAltitudeKm: number) => Promise<string | null>;
  // Flight-ready rendezvous (Phase 9A, US-MAN-03): `corrected` (default true) closes the
  // loop against the real propagators (R16); `nRev` (from the search) fixes the rev count.
  applyRendezvous: (
    deputyNoradId: number, arrivalEpoch: string, corrected?: boolean, nRev?: number,
  ) => Promise<string | null>;
  // Arrival×rev ΔV search (Phase 9A): one-shot REST analysis → a sorted ΔV map, or an error.
  searchRendezvous: (deputyNoradId: number) => Promise<RendezvousSearchResult | string>;
  // Phasing-orbit rendezvous (Phase 9A, US-MAN-06): close the phase gap over N revs.
  applyPhasing: (deputyNoradId: number, phasingRevs: number) => Promise<string | null>;
  // Close-range CW templates (Phase 9B, US-MAN-07/08/09/10). Compute ΔV server-side, insert,
  // reload. NMC = a bounded relative orbit; hold = park at a V-bar/R-bar point.
  applyNmc: (deputyNoradId: number) => Promise<string | null>;
  applyHold: (
    deputyNoradId: number, axis: 'vbar' | 'rbar', distanceM: number, arrivalEpoch: string,
  ) => Promise<string | null>;

  // Sensors & attitude (Phase 7, US-SENSE-01 / US-PROX-01). Edit → new version +
  // audit (backend) → reload so the stream re-emits FOV/attitude/events. Return an
  // error message on failure (else null).
  addSensor: (req: SensorRequest) => Promise<string | null>;
  removeSensor: (sensorId: string) => Promise<string | null>;
  setAttitude: (noradId: number, mode: 'lvlh' | 'fixed', quaternion?: number[]) => Promise<string | null>;
  // Sensor link budget (Phase 9D, US-EVT-05). Set → SNR series stream; reload re-propagates.
  setLinkBudget: (sensorId: string, params: LinkBudgetParams) => Promise<string | null>;

  // Constraints & conjunctions (Phase 8, US-EVT-02 / US-EVT-03). Same audited-edit
  // + reload pattern as sensors. Return an error message on failure (else null).
  addConstraint: (req: ConstraintRequest) => Promise<string | null>;
  removeConstraint: (constraintId: string) => Promise<string | null>;
  setMissDistance: (thresholdM: number | null) => Promise<string | null>;
  // Catalog conjunction screening (Phase 8, US-EVT-02 / UC-7). One-shot REST
  // analysis → a sorted result, or an error message string.
  screenCatalog: (thresholdKm: number) => Promise<ScreeningResult | string>;
  // Monte Carlo dispersion (Phase 9C, UC-6, US-MC-01/02). One-shot REST analysis held in
  // Zustand; returns an error message string on failure (else null).
  runMonteCarlo: (deputyNoradId: number, params: MonteCarloParams) => Promise<string | null>;
  setMonteCarloVisible: (visible: boolean) => void;
  clearMonteCarlo: () => void;
}

/** Add-constraint request shape (mirrors the backend ConstraintRequest DTO). */
export interface ConstraintRequest {
  hostNoradId: number;
  kind: 'sun-keep-out' | 'approach-corridor';
  sensorId?: string;
  targetNoradId?: number;
  limitDeg: number;
  rangeM?: number;
}

/** Add-sensor request shape (mirrors the backend SensorRequest DTO). */
export interface SensorRequest {
  noradId: number;
  kind: string;
  name: string;
  fovType: 'cone' | 'rect';
  halfAngleDeg: number;
  hDeg: number;
  vDeg: number;
  minRangeM: number;
  maxRangeM: number;
  boresightX: number;
  boresightY: number;
  boresightZ: number;
  clockDeg: number;
}

/** Build a create/update request from the composer. Defaults to a 24-hour window
 *  (the timeline editor lands in Phase 4); fidelity is sgp4 in Phase 3A. */
function composerToRequest(name: string, composer: Composer): ScenarioRequest {
  const start = composer.start ?? new Date().toISOString();
  const end = composer.end ?? new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
  return {
    name,
    fidelity: composer.fidelity || 'sgp4',
    timeRange: { start, end },
    chief: { noradId: composer.chiefId ?? 0 },
    deputies: composer.deputyIds.map((id) => ({ noradId: id })),
  };
}

const FILTERS_STORAGE_KEY = 'orbit.filters.constellations';

function loadConstellations(): string[] {
  try {
    const saved = localStorage.getItem(FILTERS_STORAGE_KEY);
    if (saved) {
      const parsed = JSON.parse(saved);
      if (Array.isArray(parsed)) return parsed;
    }
  } catch {
    // ignore malformed / unavailable storage
  }
  return [...CONSTELLATIONS]; // default: all constellations visible
}

function persistConstellations(value: string[]) {
  try {
    localStorage.setItem(FILTERS_STORAGE_KEY, JSON.stringify(value));
  } catch {
    // ignore storage failures (private mode, quota)
  }
}

const emptyComposer: Composer = {
  chiefId: null,
  deputyIds: [],
  scenarioId: null,
  isDirty: false,
  start: null,
  end: null,
  fidelity: 'sgp4',
};

/** Live-mode time-travel half-window: the scrub bar spans ±this around "now". */
const LIVE_WINDOW_MS = 12 * 60 * 60 * 1000;
function liveBounds(center: Date): ClockBounds {
  return {
    start: new Date(center.getTime() - LIVE_WINDOW_MS),
    end: new Date(center.getTime() + LIVE_WINDOW_MS),
  };
}

export const useStore = create<State>((set, get) => ({
  currentTime: new Date(),
  isPlaying: true,
  rate: 1,
  direction: 1,
  bounds: null,
  catalogLive: true,
  filters: {
    constellations: loadConstellations(),
    showDebris: false,
    showRocketBodies: false,
  },
  composer: emptyComposer,

  loadedScenario: null,
  scenarioReloadNonce: 0,
  scenarioStreamError: null,
  setScenarioStreamError: (message) => set({ scenarioStreamError: message }),

  scenarios: [],

  catalogTotal: 0,
  catalogIndex: [],

  selectedSatellite: null,
  focus: null,
  proximityFocus: null,
  cameraResetNonce: 0,
  showCatalogInScenario: false,
  monteCarlo: null,
  monteCarloVisible: true,

  setCurrentTime: (t) => set({ currentTime: t }),
  togglePlay: () => set((s) => ({ isPlaying: !s.isPlaying })),
  setRate: (rate) => set({ rate: Math.max(0, rate) }),
  toggleDirection: () => set((s) => ({ direction: s.direction === 1 ? -1 : 1 })),
  // Scrubbing/seeking leaves the catalog's live regime (catalogLive=false) so
  // the globe shows an on-demand snapshot at the chosen instant rather than the
  // rolling live window. Harmless/ignored in scenario mode.
  seek: (t) => {
    const { bounds } = get();
    const ms = bounds
      ? Math.min(bounds.end.getTime(), Math.max(bounds.start.getTime(), t.getTime()))
      : t.getTime();
    // Scrubbing locates-and-pauses (so the engine doesn't fight the drag) and
    // leaves the catalog's live regime (a frozen snapshot is shown there).
    set({ currentTime: new Date(ms), catalogLive: false, isPlaying: false });
  },
  step: (deltaSec) =>
    set((s) => {
      let ms = s.currentTime.getTime() + deltaSec * 1000;
      if (s.bounds) {
        ms = Math.min(s.bounds.end.getTime(), Math.max(s.bounds.start.getTime(), ms));
      }
      // Stepping is frame-by-frame → pause; and it freezes the catalog at the new instant.
      return { currentTime: new Date(ms), isPlaying: false, catalogLive: false };
    }),
  resetClock: () => {
    // Live mode: reset means "go live now". Scenario mode: jump to the window start, paused.
    if (!get().loadedScenario) {
      get().goLive();
      return;
    }
    set((s) => ({ currentTime: s.bounds ? new Date(s.bounds.start) : new Date(), isPlaying: false }));
  },
  setBounds: (start, end) => set({ bounds: { start, end } }),
  goLive: (at) => {
    const t = at ?? new Date();
    set({ currentTime: t, catalogLive: true, isPlaying: true, rate: 1, direction: 1, bounds: liveBounds(t) });
  },
  toggleCatalogPlayback: () =>
    // Pause → also freeze the catalog (drop the live broadcast). Play → run from
    // the current instant via rolling snapshots (catalogLive stays false).
    set((s) => (s.isPlaying ? { isPlaying: false, catalogLive: false } : { isPlaying: true })),

  toggleConstellation: (name) =>
    set((s) => {
      const next = s.filters.constellations.includes(name)
        ? s.filters.constellations.filter((c) => c !== name)
        : [...s.filters.constellations, name];
      persistConstellations(next);
      return { filters: { ...s.filters, constellations: next } };
    }),

  setCatalog: (total, index) => set({ catalogTotal: total, catalogIndex: index }),
  setSelectedSatellite: (sat) => set({ selectedSatellite: sat }),
  updateSelectedPosition: (latitudeDeg, longitudeDeg, altitudeKm) =>
    set((s) =>
      s.selectedSatellite
        ? { selectedSatellite: { ...s.selectedSatellite, latitudeDeg, longitudeDeg, altitudeKm } }
        : s,
    ),
  requestFocus: (noradId) =>
    set((s) => ({ focus: { noradId, nonce: (s.focus?.nonce ?? 0) + 1 } })),
  requestProximityFocus: (noradId) =>
    set((s) => ({ proximityFocus: { noradId, nonce: (s.proximityFocus?.nonce ?? 0) + 1 } })),
  resetCamera: () => set((s) => ({ cameraResetNonce: s.cameraResetNonce + 1 })),
  setShowCatalogInScenario: (show) => set({ showCatalogInScenario: show }),

  setChief: (id) =>
    set((s) => ({
      composer: {
        ...s.composer,
        chiefId: id,
        deputyIds: s.composer.deputyIds.filter((d) => d !== id),
        isDirty: true,
      },
    })),
  addDeputy: (id) =>
    set((s) => {
      if (s.composer.chiefId === id || s.composer.deputyIds.includes(id)) return s;
      return { composer: { ...s.composer, deputyIds: [...s.composer.deputyIds, id], isDirty: true } };
    }),
  promoteToChief: (id) =>
    set((s) => {
      const { chiefId, deputyIds } = s.composer;
      if (chiefId === id) return s; // already chief
      // Swap: the new chief leaves the deputy list; the old chief joins it.
      const nextDeputies = deputyIds.filter((d) => d !== id);
      if (chiefId !== null) nextDeputies.push(chiefId);
      return { composer: { ...s.composer, chiefId: id, deputyIds: nextDeputies, isDirty: true } };
    }),
  removeFromScenario: (id) =>
    set((s) => {
      const wasChief = s.composer.chiefId === id;
      const wasDeputy = s.composer.deputyIds.includes(id);
      if (!wasChief && !wasDeputy) return s;
      return {
        composer: {
          ...s.composer,
          chiefId: wasChief ? null : s.composer.chiefId,
          deputyIds: s.composer.deputyIds.filter((d) => d !== id),
          isDirty: true,
        },
      };
    }),
  setComposerTimeRange: (start, end) =>
    set((s) => ({ composer: { ...s.composer, start, end, isDirty: true } })),
  setComposerFidelity: (fidelity) =>
    set((s) => ({ composer: { ...s.composer, fidelity, isDirty: true } })),
  clearComposer: () => set({ composer: emptyComposer }),

  loadScenarios: async () => {
    const { data, error } = await api.GET('/scenarios');
    if (error) {
      console.error('Failed to load scenarios', error);
      return;
    }
    set({ scenarios: data ?? [] });
  },

  saveScenario: async (name) => {
    const { composer } = get();
    if (composer.chiefId === null) return; // guarded in the UI too
    const body = composerToRequest(name, composer);

    if (composer.scenarioId) {
      const { error } = await api.PUT('/scenarios/{id}', {
        params: { path: { id: composer.scenarioId } },
        body,
      });
      if (error) throw error;
    } else {
      const { data, error } = await api.POST('/scenarios', { body });
      if (error || !data) throw error ?? new Error('Create failed');
      set((s) => ({ composer: { ...s.composer, scenarioId: data.id ?? null } }));
    }
    set((s) => ({ composer: { ...s.composer, isDirty: false } }));
    await get().loadScenarios();

    // If we just saved the scenario that's currently loaded for playback (e.g.
    // a time-range edit), reload it so the clock window + stream pick up the new
    // version. A fresh create (nothing loaded) just lands in the list.
    const savedId = get().composer.scenarioId;
    if (savedId && get().loadedScenario?.id === savedId) {
      await get().loadScenario(savedId);
    }
  },

  loadScenario: async (id) => {
    const { data, error } = await api.GET('/scenarios/{id}', { params: { path: { id } } });
    if (error || !data) {
      console.error('Failed to load scenario', error);
      return;
    }
    const body = data.body;
    const chiefId = body?.chief?.noradId ?? null;
    const deputyIds = (body?.deputies ?? [])
      .map((d) => d.noradId)
      .filter((n): n is number => typeof n === 'number');
    const scenarioId = data.id ?? id;
    const startStr = body?.timeRange?.start;
    const endStr = body?.timeRange?.end;

    set({
      composer: {
        chiefId,
        deputyIds,
        scenarioId,
        isDirty: false,
        start: startStr ?? null,
        end: endStr ?? null,
        fidelity: body?.fidelity ?? 'sgp4',
      },
      loadedScenario: body ? { id: scenarioId, name: data.name ?? '', body } : null,
      scenarioReloadNonce: get().scenarioReloadNonce + 1,
      scenarioStreamError: null, // clear any prior rejection; the new stream re-reports
      // Drop any catalog selection — its dot is hidden during playback, so a
      // stale ring would track an invisible entity ("circles empty").
      selectedSatellite: null,
      showCatalogInScenario: false, // each scenario starts with the catalog hidden
    });

    // Drive the shared clock from the scenario's time range and start playing
    // from the beginning (Globe opens the per-scenario stream off loadedScenario;
    // Decision 11 — frontend owns playback control).
    if (startStr && endStr) {
      const start = new Date(startStr);
      const end = new Date(endStr);
      if (Number.isFinite(start.getTime()) && Number.isFinite(end.getTime())) {
        set({ bounds: { start, end }, currentTime: start, rate: 1, direction: 1, isPlaying: true });
      }
    }
  },

  closeScenario: () => {
    // Back to the live catalog: drop the scenario, re-center the travel window on now.
    const now = new Date();
    set({
      loadedScenario: null,
      scenarioStreamError: null,
      bounds: liveBounds(now),
      composer: emptyComposer,
      selectedSatellite: null,
      showCatalogInScenario: false,
      currentTime: now,
      catalogLive: true,
      rate: 1,
      direction: 1,
      isPlaying: true,
    });
  },

  deleteScenario: async (id) => {
    const { error } = await api.DELETE('/scenarios/{id}', { params: { path: { id } } });
    if (error) {
      console.error('Failed to delete scenario', error);
      return;
    }
    // If the deleted scenario is the one loaded, return to the live catalog.
    if (get().loadedScenario?.id === id || get().composer.scenarioId === id) {
      get().closeScenario();
    }
    await get().loadScenarios();
  },

  importMeasuredScenario: async (path, noradId) => {
    const body: { path: string; noradId?: number } = { path };
    if (typeof noradId === 'number' && Number.isFinite(noradId)) body.noradId = noradId;
    const { data, error } = await api.POST('/scenarios/import/measured', { body });
    if (error || !data) {
      const msg = (error as { message?: string } | undefined)?.message ?? 'Measured-data import failed';
      set({ scenarioStreamError: msg });
      throw error ?? new Error('Import failed');
    }
    await get().loadScenarios();
    if (data.id) await get().loadScenario(data.id); // lands in the composer + plays the real track
  },

  addManeuver: async (deputyNoradId, epoch, dv) => {
    const id = get().loadedScenario?.id;
    if (!id) return;
    const { error } = await api.POST('/scenarios/{id}/maneuvers', {
      params: { path: { id } },
      body: { deputyNoradId, epoch, frame: 'ric', r: dv.r, i: dv.i, c: dv.c },
    });
    if (error) {
      console.error('Failed to add maneuver', error);
      return;
    }
    await get().loadScenario(id); // re-propagate: bumps scenarioReloadNonce → stream reopens
  },

  removeManeuver: async (maneuverId) => {
    const id = get().loadedScenario?.id;
    if (!id) return;
    const { error } = await api.DELETE('/scenarios/{id}/maneuvers/{maneuverId}', {
      params: { path: { id, maneuverId } },
    });
    if (error) {
      console.error('Failed to remove maneuver', error);
      return;
    }
    await get().loadScenario(id);
  },

  applyHohmann: async (deputyNoradId, targetAltitudeKm) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.POST('/scenarios/{id}/maneuvers/hohmann', {
      params: { path: { id } },
      body: { deputyNoradId, targetAltitudeKm },
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id);
    return null;
  },

  applyRendezvous: async (deputyNoradId, arrivalEpoch, corrected = true, nRev) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.POST('/scenarios/{id}/maneuvers/rendezvous', {
      params: { path: { id } },
      body: { deputyNoradId, arrivalEpoch, corrected, nRev },
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id);
    return null;
  },

  searchRendezvous: async (deputyNoradId) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { data, error } = await api.POST('/scenarios/{id}/maneuvers/rendezvous/search', {
      params: { path: { id } },
      body: { deputyNoradId },
    });
    if (error || !data) return errorMessage(error);
    return data; // does NOT reload — a one-shot read-only ΔV map
  },

  applyPhasing: async (deputyNoradId, phasingRevs) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.POST('/scenarios/{id}/maneuvers/phasing', {
      params: { path: { id } },
      body: { deputyNoradId, phasingRevs },
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id);
    return null;
  },

  applyNmc: async (deputyNoradId) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.POST('/scenarios/{id}/maneuvers/nmc', {
      params: { path: { id } },
      body: { deputyNoradId },
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id);
    return null;
  },

  applyHold: async (deputyNoradId, axis, distanceM, arrivalEpoch) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.POST('/scenarios/{id}/maneuvers/hold', {
      params: { path: { id } },
      body: { deputyNoradId, axis, distanceM, arrivalEpoch },
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id);
    return null;
  },

  addSensor: async (req) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.POST('/scenarios/{id}/sensors', {
      params: { path: { id } },
      body: req,
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id); // re-propagate: FOV/attitude/events re-emit
    return null;
  },

  removeSensor: async (sensorId) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.DELETE('/scenarios/{id}/sensors/{sensorId}', {
      params: { path: { id, sensorId } },
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id);
    return null;
  },

  setAttitude: async (noradId, mode, quaternion) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.PUT('/scenarios/{id}/attitude', {
      params: { path: { id } },
      body: { noradId, mode, quaternion },
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id);
    return null;
  },

  addConstraint: async (req) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.POST('/scenarios/{id}/constraints', {
      params: { path: { id } },
      body: req,
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id); // re-propagate: violation events re-emit
    return null;
  },

  removeConstraint: async (constraintId) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.DELETE('/scenarios/{id}/constraints/{constraintId}', {
      params: { path: { id, constraintId } },
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id);
    return null;
  },

  setLinkBudget: async (sensorId, params) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.PUT('/scenarios/{id}/sensors/{sensorId}/link-budget', {
      params: { path: { id, sensorId } },
      body: params,
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id); // re-propagate: the SNR series re-emits
    return null;
  },

  setMissDistance: async (thresholdM) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { error } = await api.PUT('/scenarios/{id}/miss-distance', {
      params: { path: { id } },
      body: { missDistanceThresholdM: thresholdM ?? undefined },
    });
    if (error) return errorMessage(error);
    await get().loadScenario(id);
    return null;
  },

  screenCatalog: async (thresholdKm) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { data, error } = await api.POST('/scenarios/{id}/screening', {
      params: { path: { id }, query: { thresholdKm } },
    });
    if (error || !data) return errorMessage(error);
    return data; // does NOT reload the scenario — a one-shot read-only analysis
  },

  runMonteCarlo: async (deputyNoradId, params) => {
    const id = get().loadedScenario?.id;
    if (!id) return 'No scenario loaded';
    const { data, error } = await api.POST('/scenarios/{id}/monte-carlo', {
      params: { path: { id } },
      body: { deputyNoradId, ...params },
    });
    if (error || !data) return errorMessage(error);
    set({ monteCarlo: data, monteCarloVisible: true }); // static result → lives in Zustand
    return null;
  },
  setMonteCarloVisible: (visible) => set({ monteCarloVisible: visible }),
  clearMonteCarlo: () => set({ monteCarlo: null }),
}));

/** Pull a human message out of the generated client's error payload (422 etc.). */
function errorMessage(error: unknown): string {
  if (error && typeof error === 'object' && 'message' in error) {
    const m = (error as { message?: unknown }).message;
    if (typeof m === 'string') return m;
  }
  return 'Request failed';
}
