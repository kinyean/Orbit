import { useCallback, useLayoutEffect, useRef, useState } from 'react';

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

export interface PanelPos {
  x: number;
  y: number;
}

/** Clamp a position so the panel's header stays within the viewport. */
function clampPos(p: PanelPos): PanelPos {
  if (typeof window === 'undefined') return p;
  const maxX = Math.max(0, window.innerWidth - 160);
  const maxY = Math.max(0, window.innerHeight - 120); // keep header + a strip of body visible
  return {
    x: Math.min(Math.max(0, p.x), maxX),
    y: Math.min(Math.max(0, p.y), maxY),
  };
}

/**
 * Draggable-panel position, persisted in localStorage and clamped into the viewport
 * on load — so a panel (or a stale saved position) is never left off the bottom/side
 * of the screen, and a user's chosen spot survives a refresh. {@link setPos} updates
 * without persisting (use during a drag); {@link commitPos} updates AND persists +
 * re-clamps (call on drag end).
 */
export function usePanelPosition(id: string, def: PanelPos): {
  pos: PanelPos;
  setPos: (p: PanelPos) => void;
  commitPos: (p: PanelPos) => void;
} {
  const key = `orbit.panel.${id}.pos`;
  const [pos, setPos] = useState<PanelPos>(() => {
    try {
      const v = localStorage.getItem(key);
      if (v) {
        const parsed = JSON.parse(v) as Partial<PanelPos>;
        if (typeof parsed?.x === 'number' && typeof parsed?.y === 'number') {
          return clampPos({ x: parsed.x, y: parsed.y });
        }
      }
    } catch {
      /* ignore malformed / unavailable storage */
    }
    return clampPos(def);
  });
  const commitPos = useCallback((p: PanelPos) => {
    const clamped = clampPos(p);
    setPos(clamped);
    try {
      localStorage.setItem(key, JSON.stringify(clamped));
    } catch {
      /* ignore storage failures (private mode, quota) */
    }
  }, [key]);
  return { pos, setPos, commitPos };
}

interface PanelSize {
  w?: number;
  h?: number;
}

function loadSize(key: string): PanelSize {
  try {
    const v = localStorage.getItem(key);
    if (v) {
      const p = JSON.parse(v) as Partial<PanelSize>;
      return {
        w: typeof p.w === 'number' && p.w > 0 ? p.w : undefined,
        h: typeof p.h === 'number' && p.h > 0 ? p.h : undefined,
      };
    }
  } catch {
    /* ignore malformed / unavailable storage */
  }
  return {};
}

/**
 * Persist a resizable panel's SIZE across refreshes and keep collapse tidy, without
 * disabling resize. CSS `resize` writes inline width/height when the user drags the
 * handle; this:
 *   - restores the saved width/height on mount (height only when expanded);
 *   - observes resizes and re-saves (debounced) — width always, height only while
 *     expanded (the stored height is the EXPANDED height);
 *   - on collapse, clears the inline height so the panel shrinks to its header; on
 *     expand, restores the saved expanded height.
 * Width persists through collapse (a narrowed header stays narrowed). React never
 * manages width/height in its `style` prop, so it doesn't fight the browser's resize.
 * Attach the returned ref to the panel's root element.
 */
export function usePanelSize<T extends HTMLElement>(id: string, collapsed: boolean) {
  const key = `orbit.panel.${id}.size`;
  const ref = useRef<T>(null);
  const saved = useRef<PanelSize>(loadSize(key));

  // Apply the saved size on mount (height only if starting expanded).
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (saved.current.w) el.style.width = `${saved.current.w}px`;
    if (saved.current.h && !collapsed) el.style.height = `${saved.current.h}px`;
    // mount only — collapse/expand + resize handled by the effects below
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Stash/clear the height across collapse so it shrinks to the header, restore on expand.
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (collapsed) {
      el.style.height = '';
    } else if (saved.current.h) {
      el.style.height = `${saved.current.h}px`;
    }
  }, [collapsed]);

  // Persist size on resize (debounced). Only record a dimension the user DELIBERATELY
  // set — the browser writes inline width/height solely when the resize handle is
  // dragged, so an empty inline value means "auto-fit content" and is left unsaved
  // (otherwise a panel would freeze to its first-rendered height and stop growing with
  // its content). Height is tracked only while expanded.
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    let timer: number | undefined;
    const ro = new ResizeObserver(() => {
      const rect = el.getBoundingClientRect();
      const w = el.style.width ? Math.round(rect.width) : saved.current.w;
      const h = !collapsed && el.style.height ? Math.round(rect.height) : saved.current.h;
      if (w === saved.current.w && h === saved.current.h) return;
      saved.current = { w, h };
      if (timer) window.clearTimeout(timer);
      timer = window.setTimeout(() => {
        try {
          localStorage.setItem(key, JSON.stringify(saved.current));
        } catch {
          /* ignore storage failures */
        }
      }, 250);
    });
    ro.observe(el);
    return () => {
      ro.disconnect();
      if (timer) window.clearTimeout(timer);
    };
  }, [key, collapsed]);

  return ref;
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
