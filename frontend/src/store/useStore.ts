import { create } from 'zustand';

export interface Filters {
  constellations: string[];
  showDebris: boolean;
  showRocketBodies: boolean;
}

/**
 * The current state of the scenario being authored.
 *
 * - `chiefId` is the NORAD ID of the designated chief, or null before one is set.
 * - `deputyIds` are NORAD IDs of deputies, in click-order.
 * - `scenarioId` is the persisted backend id once the scenario has been saved
 *   at least once; null while composing a not-yet-saved scenario.
 * - `isDirty` flips true on any modification and back to false after a
 *   successful save.
 *
 * See US-SCN-02 in docs/user-stories.md and decisions.md §14.
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
  selectedId: number | null;
  filters: Filters;
  composer: Composer;

  setCurrentTime: (t: Date) => void;
  togglePlay: () => void;
  setSelectedId: (id: number | null) => void;
  toggleConstellation: (name: string) => void;

  // Composer actions. Each maps to a UX action in use-cases.md UC-1.
  setChief: (id: number) => void;
  addDeputy: (id: number) => void;
  removeFromScenario: (id: number) => void;
  clearComposer: () => void;
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
  selectedId: null,
  filters: {
    constellations: ['Starlink', 'OneWeb', 'GPS'],
    showDebris: false,
    showRocketBodies: false,
  },
  composer: emptyComposer,

  setCurrentTime: (t) => set({ currentTime: t }),
  togglePlay: () => set((s) => ({ isPlaying: !s.isPlaying })),
  setSelectedId: (id) => set({ selectedId: id }),
  toggleConstellation: (name) =>
    set((s) => ({
      filters: {
        ...s.filters,
        constellations: s.filters.constellations.includes(name)
          ? s.filters.constellations.filter((c) => c !== name)
          : [...s.filters.constellations, name],
      },
    })),

  setChief: (id) =>
    set((s) => ({
      composer: {
        ...s.composer,
        chiefId: id,
        // If the new chief was previously a deputy, drop it from deputies.
        deputyIds: s.composer.deputyIds.filter((d) => d !== id),
        isDirty: true,
      },
    })),

  addDeputy: (id) =>
    set((s) => {
      if (s.composer.chiefId === id) return s; // can't be both
      if (s.composer.deputyIds.includes(id)) return s; // already a deputy
      return {
        composer: {
          ...s.composer,
          deputyIds: [...s.composer.deputyIds, id],
          isDirty: true,
        },
      };
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
