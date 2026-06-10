import { create } from 'zustand';
import { CONSTELLATIONS, type Constellation } from '../lib/constellations';
import { api } from '../api/client';
import type { components } from '../api/schema';

/** A saved scenario as listed by GET /scenarios (generated from the backend contract). */
export type ScenarioSummary = components['schemas']['ScenarioSummary'];
type ScenarioRequest = components['schemas']['ScenarioRequest'];

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
}

export interface State {
  currentTime: Date;
  isPlaying: boolean;
  filters: Filters;
  composer: Composer;

  // Saved scenarios (from the backend; US-SCN-03/11/12)
  scenarios: ScenarioSummary[];

  // Catalog (from the backend stream)
  catalogTotal: number;
  catalogIndex: SatIndexEntry[];

  // Inspection + camera focus
  selectedSatellite: SelectedSatellite | null;
  focus: FocusRequest | null;
  cameraResetNonce: number;

  setCurrentTime: (t: Date) => void;
  togglePlay: () => void;
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
  clearComposer: () => void;

  // Scenario CRUD (calls the generated client)
  loadScenarios: () => Promise<void>;
  /** Create (no scenarioId yet) or save-a-new-version (existing id), optionally renaming. */
  saveScenario: (name: string) => Promise<void>;
  loadScenario: (id: string) => Promise<void>;
  deleteScenario: (id: string) => Promise<void>;
}

/** Build a create/update request from the composer. Defaults to a 24-hour window
 *  (the timeline editor lands in Phase 4); fidelity is sgp4 in Phase 3A. */
function composerToRequest(name: string, composer: Composer): ScenarioRequest {
  const start = new Date();
  const end = new Date(start.getTime() + 24 * 60 * 60 * 1000);
  return {
    name,
    fidelity: 'sgp4',
    timeRange: { start: start.toISOString(), end: end.toISOString() },
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
};

export const useStore = create<State>((set, get) => ({
  currentTime: new Date(),
  isPlaying: true,
  filters: {
    constellations: loadConstellations(),
    showDebris: false,
    showRocketBodies: false,
  },
  composer: emptyComposer,

  scenarios: [],

  catalogTotal: 0,
  catalogIndex: [],

  selectedSatellite: null,
  focus: null,
  cameraResetNonce: 0,

  setCurrentTime: (t) => set({ currentTime: t }),
  togglePlay: () => set((s) => ({ isPlaying: !s.isPlaying })),

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
  },

  loadScenario: async (id) => {
    const { data, error } = await api.GET('/scenarios/{id}', { params: { path: { id } } });
    if (error || !data) {
      console.error('Failed to load scenario', error);
      return;
    }
    const chiefId = data.body?.chief?.noradId ?? null;
    const deputyIds = (data.body?.deputies ?? [])
      .map((d) => d.noradId)
      .filter((n): n is number => typeof n === 'number');
    set({
      composer: { chiefId, deputyIds, scenarioId: data.id ?? id, isDirty: false },
    });
  },

  deleteScenario: async (id) => {
    const { error } = await api.DELETE('/scenarios/{id}', { params: { path: { id } } });
    if (error) {
      console.error('Failed to delete scenario', error);
      return;
    }
    // If the deleted scenario is the one loaded in the composer, clear it.
    set((s) => (s.composer.scenarioId === id ? { composer: emptyComposer } : {}));
    await get().loadScenarios();
  },
}));
