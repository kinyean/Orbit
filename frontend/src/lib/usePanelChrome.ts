import { useCallback, useState } from 'react';

/**
 * Per-panel collapse (minimize) state, persisted in localStorage so a user's
 * decluttering choices stick across reloads. Keyed by a stable panel id.
 */
export function useCollapsed(id: string, defaultCollapsed = false): {
  collapsed: boolean;
  toggle: () => void;
} {
  const key = `orbit.panel.${id}.collapsed`;
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    try {
      const v = localStorage.getItem(key);
      return v === null ? defaultCollapsed : v === '1';
    } catch {
      return defaultCollapsed;
    }
  });
  const toggle = useCallback(() => {
    setCollapsed((c) => {
      const next = !c;
      try {
        localStorage.setItem(key, next ? '1' : '0');
      } catch {
        /* ignore storage failures (private mode, quota) */
      }
      return next;
    });
  }, [key]);
  return { collapsed, toggle };
}

/**
 * Per-panel selected tab, persisted in localStorage (same posture as
 * {@link useCollapsed}). `tabs` is the allowed set; an unknown stored value
 * falls back to the first tab.
 */
export function usePanelTab<T extends string>(id: string, tabs: readonly T[]): {
  tab: T;
  setTab: (t: T) => void;
} {
  const key = `orbit.panel.${id}.tab`;
  const [tab, setTabState] = useState<T>(() => {
    try {
      const v = localStorage.getItem(key) as T | null;
      return v !== null && tabs.includes(v) ? v : tabs[0];
    } catch {
      return tabs[0];
    }
  });
  const setTab = useCallback((t: T) => {
    setTabState(t);
    try {
      localStorage.setItem(key, t);
    } catch {
      /* ignore storage failures (private mode, quota) */
    }
  }, [key]);
  return { tab, setTab };
}
