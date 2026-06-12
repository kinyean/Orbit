import { create } from 'zustand';
import { CONSTELLATIONS, type Constellation } from '../lib/constellations';
import { api } from '../api/client';
import type { components } from '../api/schema';

/** A saved scenario as listed by GET /scenarios (generated from the backend contract). */
export type ScenarioSummary = components['schemas']['ScenarioSummary'];
type ScenarioRequest = components['schemas']['ScenarioRequest'];
type ScenarioBodyT = components['schemas']['ScenarioBody'];

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

  // Saved scenarios (from the backend; US-SCN-03/11/12)
  scenarios: ScenarioSummary[];

  // Catalog (from the backend stream)
  catalogTotal: number;
  catalogIndex: SatIndexEntry[];

  // Inspection + camera focus
  selectedSatellite: SelectedSatellite | null;
  focus: FocusRequest | null;
  cameraResetNonce: number;

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
  resetCamera: () => void;

  // Composer actions
  setChief: (id: number) => void;
  addDeputy: (id: number) => void;
  removeFromScenario: (id: number) => void;
  setComposerTimeRange: (start: string, end: string) => void;
  clearComposer: () => void;

  // Scenario CRUD (calls the generated client)
  loadScenarios: () => Promise<void>;
  /** Create (no scenarioId yet) or save-a-new-version (existing id), optionally renaming. */
  saveScenario: (name: string) => Promise<void>;
  loadScenario: (id: string) => Promise<void>;
  deleteScenario: (id: string) => Promise<void>;
  /** Stop streaming the loaded scenario and return to the live catalog regime. */
  closeScenario: () => void;
}

/** Build a create/update request from the composer. Defaults to a 24-hour window
 *  (the timeline editor lands in Phase 4); fidelity is sgp4 in Phase 3A. */
function composerToRequest(name: string, composer: Composer): ScenarioRequest {
  const start = composer.start ?? new Date().toISOString();
  const end = composer.end ?? new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
  return {
    name,
    fidelity: 'sgp4',
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

  scenarios: [],

  catalogTotal: 0,
  catalogIndex: [],

  selectedSatellite: null,
  focus: null,
  cameraResetNonce: 0,

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
  resetCamera: () => set((s) => ({ cameraResetNonce: s.cameraResetNonce + 1 })),

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
      },
      loadedScenario: body ? { id: scenarioId, name: data.name ?? '', body } : null,
      scenarioReloadNonce: get().scenarioReloadNonce + 1,
      // Drop any catalog selection — its dot is hidden during playback, so a
      // stale ring would track an invisible entity ("circles empty").
      selectedSatellite: null,
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
      bounds: liveBounds(now),
      composer: emptyComposer,
      selectedSatellite: null,
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
}));
