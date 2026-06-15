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
