import { create } from 'zustand';

export interface Filters {
  constellations: string[];
  showDebris: boolean;
  showRocketBodies: boolean;
}

export interface State {
  currentTime: Date;
  isPlaying: boolean;
  selectedId: number | null;
  filters: Filters;

  setCurrentTime: (t: Date) => void;
  togglePlay: () => void;
  setSelectedId: (id: number | null) => void;
  toggleConstellation: (name: string) => void;
}

export const useStore = create<State>((set) => ({
  currentTime: new Date(),
  isPlaying: true,
  selectedId: null,
  filters: {
    constellations: ['Starlink', 'OneWeb', 'GPS'],
    showDebris: false,
    showRocketBodies: false,
  },

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
}));
