// The simulation clock's single advance loop (Phase 4, Decision 11).
//
// One requestAnimationFrame loop is the SOLE writer of `currentTime` during
// playback: when `isPlaying`, it advances `currentTime` by
// `rate * direction * dtRealMs`, clamped to `bounds` (pausing at the edge).
// Cesium and three.js only READ `currentTime` each frame, so the two views are
// in lockstep by construction — no per-view clock can drift (US-VIEW-02).
//
// Reads `rate`/`direction`/`bounds` fresh each frame, so changing them never
// restarts the loop. Started once from App's mount effect.

import { useStore } from './useStore';

let rafId: number | null = null;
let lastTs: number | null = null;

// Cap the per-frame real delta so a backgrounded tab (rAF pauses, then resumes
// with a multi-second gap) doesn't teleport the clock — at 10000× a 1 s gap
// would jump ~2.8 h. 250 ms is generous for a 30–60 fps loop.
const MAX_FRAME_MS = 250;

function tick(ts: number): void {
  rafId = requestAnimationFrame(tick);

  const s = useStore.getState();
  if (lastTs === null) {
    lastTs = ts;
    return;
  }
  const dtRealMs = Math.min(MAX_FRAME_MS, ts - lastTs);
  lastTs = ts;

  if (!s.isPlaying || s.rate <= 0) return;

  const next = s.currentTime.getTime() + s.rate * s.direction * dtRealMs;

  const b = s.bounds;
  if (b) {
    const lo = b.start.getTime();
    const hi = b.end.getTime();
    if (next >= hi) {
      // Pause at the far edge so playback stops cleanly instead of running off.
      useStore.setState({ currentTime: new Date(hi), isPlaying: false });
      return;
    }
    if (next <= lo) {
      useStore.setState({ currentTime: new Date(lo), isPlaying: false });
      return;
    }
  }
  useStore.setState({ currentTime: new Date(next) });
}

/** Start the clock loop (idempotent). Returns a stop function. */
export function startClockEngine(): () => void {
  if (rafId === null) {
    lastTs = null;
    rafId = requestAnimationFrame(tick);
  }
  return stopClockEngine;
}

export function stopClockEngine(): void {
  if (rafId !== null) {
    cancelAnimationFrame(rafId);
    rafId = null;
  }
  lastTs = null;
}
