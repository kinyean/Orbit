/**
 * Performance instrumentation (Phase 11, US-UX-03 — SRS §5.1). A module
 * singleton in the Decision-5 posture: per-frame marks are plain array writes
 * that never touch React/Zustand; the HUD subscribes at 2 Hz.
 *
 * What it measures, mapped to the §5.1 targets:
 *  - Globe / proximity FPS (avg over the recent window + the worst recent
 *    frame) — §5.1.1 proximity ≥60 fps, §5.1.2 global ≥30 fps.
 *  - Scrub latency: a `markSeek()` (user seek/step) is closed by the NEXT
 *    rendered frame of each mounted view; the sample is the slower of the two
 *    (input → rendered frame) — §5.1.3 ≤200 ms.
 *  - Scenario load: `markLoadStart()` (load click) to both stream payloads
 *    applied (CZML + relative) — §5.1.4 ≤5 s for 24 h.
 */

const FRAME_RING = 256; // ~8.5 s of globe frames at 30 fps / ~4.3 s at 60
const SCRUB_RING = 64;

interface FrameTrack {
  times: Float64Array;
  n: number; // total frames ever (ring index = n % FRAME_RING)
}

const globe: FrameTrack = { times: new Float64Array(FRAME_RING), n: 0 };
const prox: FrameTrack = { times: new Float64Array(FRAME_RING), n: 0 };

// Scrub-latency state: one open seek at a time; each view closes its half on
// its next rendered frame; the sample records when every pending view closed.
let seekAt = -1;
let seekPendingGlobe = false;
let seekPendingProx = false;
let seekWorstMs = 0;
const scrubSamples = new Float64Array(SCRUB_RING);
let scrubN = 0;

// Scenario-load state.
let loadStartAt = -1;
let loadCzmlReady = false;
let loadRelativeReady = false;
let lastLoadMs: number | null = null;

export interface PerfSnapshot {
  globeFps: number | null;
  globeWorstMs: number | null;
  proxFps: number | null;
  proxWorstMs: number | null;
  scrubLastMs: number | null;
  scrubP95Ms: number | null;
  lastLoadMs: number | null;
}

function markFrame(track: FrameTrack, isGlobe: boolean): void {
  const now = performance.now();
  track.times[track.n % FRAME_RING] = now;
  track.n++;
  if (seekAt >= 0) {
    if (isGlobe && seekPendingGlobe) {
      seekPendingGlobe = false;
      seekWorstMs = Math.max(seekWorstMs, now - seekAt);
    } else if (!isGlobe && seekPendingProx) {
      seekPendingProx = false;
      seekWorstMs = Math.max(seekWorstMs, now - seekAt);
    }
    if (!seekPendingGlobe && !seekPendingProx) {
      scrubSamples[scrubN % SCRUB_RING] = seekWorstMs;
      scrubN++;
      seekAt = -1;
    }
  }
}

export function markGlobeFrame(): void {
  markFrame(globe, true);
}

export function markProxFrame(): void {
  markFrame(prox, false);
}

/**
 * A user seek/step happened (Timeline drag, step buttons). Closed by the next
 * rendered frame of each view that has rendered recently (a view that isn't
 * mounted can't close its half, so only "live" views are awaited).
 */
export function markSeek(): void {
  const now = performance.now();
  const live = (t: FrameTrack) => t.n > 0 && now - t.times[(t.n - 1) % FRAME_RING] < 1000;
  seekAt = now;
  seekWorstMs = 0;
  seekPendingGlobe = live(globe);
  seekPendingProx = live(prox);
  if (!seekPendingGlobe && !seekPendingProx) seekAt = -1;
}

export function markLoadStart(): void {
  loadStartAt = performance.now();
  loadCzmlReady = false;
  loadRelativeReady = false;
}

/** A scenario-stream payload was applied; load completes when both have arrived. */
export function markStreamReady(kind: 'czml' | 'relative'): void {
  if (loadStartAt < 0) return;
  if (kind === 'czml') loadCzmlReady = true;
  else loadRelativeReady = true;
  if (loadCzmlReady && loadRelativeReady) {
    lastLoadMs = performance.now() - loadStartAt;
    loadStartAt = -1;
  }
}

function fpsOf(track: FrameTrack, windowMs: number): [number | null, number | null] {
  const now = performance.now();
  const count = Math.min(track.n, FRAME_RING);
  if (count < 2) return [null, null];
  // Walk back through the ring collecting frames inside the window.
  let frames = 0;
  let oldest = now;
  let worst = 0;
  let prev = -1;
  for (let i = 0; i < count; i++) {
    const t = track.times[(track.n - 1 - i) % FRAME_RING];
    if (now - t > windowMs) break;
    if (prev >= 0) worst = Math.max(worst, prev - t);
    prev = t;
    oldest = t;
    frames++;
  }
  if (frames < 2 || now - oldest < 1) return [null, null];
  // A stopped view (no frame in the last second) reads as stale — report null.
  if (now - track.times[(track.n - 1) % FRAME_RING] > 1000) return [null, null];
  return [((frames - 1) / (track.times[(track.n - 1) % FRAME_RING] - oldest)) * 1000, worst];
}

export function snapshot(): PerfSnapshot {
  const [gFps, gWorst] = fpsOf(globe, 2000);
  const [pFps, pWorst] = fpsOf(prox, 2000);
  let scrubLast: number | null = null;
  let scrubP95: number | null = null;
  const sCount = Math.min(scrubN, SCRUB_RING);
  if (sCount > 0) {
    scrubLast = scrubSamples[(scrubN - 1) % SCRUB_RING];
    const sorted = Array.from(scrubSamples.slice(0, sCount)).sort((a, b) => a - b);
    scrubP95 = sorted[Math.min(sCount - 1, Math.floor(sCount * 0.95))];
  }
  return {
    globeFps: gFps,
    globeWorstMs: gWorst,
    proxFps: pFps,
    proxWorstMs: pWorst,
    scrubLastMs: scrubLast,
    scrubP95Ms: scrubP95,
    lastLoadMs,
  };
}

// --- 2 Hz subscription for the HUD (interval runs only while subscribed) -----

const listeners = new Set<() => void>();
let timer = 0;

export function subscribePerf(listener: () => void): () => void {
  listeners.add(listener);
  if (listeners.size === 1) {
    timer = window.setInterval(() => listeners.forEach((l) => l()), 500);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) window.clearInterval(timer);
  };
}
