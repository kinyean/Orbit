/**
 * Capture registry (Phase 11B, US-IO-01/02 — SRS §4.2.3).
 *
 * Each viewport registers a handle exposing its WebGL canvas plus two hooks the
 * exporters need: `renderNow()` (a synchronous render at the CURRENT store time)
 * and `setExportMode()` (suspend/resume the view's own render loop while the MP4
 * exporter drives frames deterministically).
 *
 * A module singleton — the Decision-5 idiom (like `stream/relativeBuffer`) — so
 * no React refs are lifted and no props are drilled. Consumers must treat a
 * source as ephemeral: it unregisters when its view unmounts.
 *
 * Pixel-read contract: neither context uses `preserveDrawingBuffer`, so reads
 * are only valid SYNCHRONOUSLY (same JS task) after `renderNow()` returns —
 * WebGL keeps the drawing buffer intact until the task ends. `capture.ts`
 * copies into a 2D canvas immediately after rendering; never defer the read.
 */

export type CaptureSourceId = 'globe' | 'proximity';

export interface CaptureSource {
  /** The live WebGL canvas (do NOT call toBlob on it directly — copy first). */
  canvas: HTMLCanvasElement;
  /** Synchronously render one frame at the current `store.currentTime`. */
  renderNow(): void;
  /**
   * Suspend (true) / resume (false) the view's own render loop so the MP4
   * exporter's explicit `renderNow()` calls are the only renders happening.
   */
  setExportMode(on: boolean): void;
}

const sources = new Map<CaptureSourceId, CaptureSource>();

/** Register a viewport's capture handle; returns the unregister function. */
export function registerCaptureSource(id: CaptureSourceId, src: CaptureSource): () => void {
  sources.set(id, src);
  return () => {
    // Only remove if it's still ours (a remount may have re-registered first).
    if (sources.get(id) === src) sources.delete(id);
  };
}

export function getCaptureSource(id: CaptureSourceId): CaptureSource | null {
  return sources.get(id) ?? null;
}
