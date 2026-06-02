import { create } from 'zustand';
import { CONSTELLATIONS, type Constellation } from '../lib/constellations';

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

  // Catalog (from the backend stream)
  catalogTotal: number;
  catalogIndex: SatIndexEntry[];

  // Inspection + camera focus
  selectedSatellite: SelectedSatellite | null;
  focus: FocusRequest | null;

  setCurrentTime: (t: Date) => void;
  togglePlay: () => void;
  toggleConstellation: (name: string) => void;
  setCatalog: (total: number, index: SatIndexEntry[]) => void;
  setSelectedSatellite: (sat: SelectedSatellite | null) => void;
  requestFocus: (noradId: number) => void;

  // Composer actions
  setChief: (id: number) => void;
  addDeputy: (id: number) => void;
  removeFromScenario: (id: number) => void;
  clearComposer: () => void;
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

export const useStore = create<State>((set) => ({
  currentTime: new Date(),
  isPlaying: true,
  filters: {
    constellations: loadConstellations(),
    showDebris: false,
    showRocketBodies: false,
  },
  composer: emptyComposer,

  catalogTotal: 0,
  catalogIndex: [],

  selectedSatellite: null,
  focus: null,

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
  requestFocus: (noradId) =>
    set((s) => ({ focus: { noradId, nonce: (s.focus?.nonce ?? 0) + 1 } })),

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
}));
