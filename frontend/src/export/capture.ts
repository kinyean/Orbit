/**
 * PNG snapshot + shared view-composition helpers (Phase 11B, US-IO-01 — SRS §4.2.3).
 *
 * All pixel reads follow the captureRegistry contract: `renderNow()` then a
 * SYNCHRONOUS `drawImage` copy into a 2D canvas in the same JS task (no
 * `preserveDrawingBuffer` anywhere — see Decision 29). `toBlob` (async) runs on
 * the 2D copy, which persists.
 */

import { getCaptureSource, type CaptureSource, type CaptureSourceId } from './captureRegistry';
import { downloadBlob, slugify, timeStamp } from './download';

export type SnapshotSource = CaptureSourceId | 'both';

const PNG_MAX_EDGE = 3840;
const HEADER_PX = 28;

export interface ComposeLayout {
  width: number; // both dims even (H.264 requires it; harmless for PNG)
  height: number;
  tiles: { x: number; y: number; w: number; h: number }[];
}

/** Side-by-side layout at a common height, long edge capped, dims rounded even. */
export function computeLayout(
  sizes: { width: number; height: number }[],
  maxEdge: number,
  headerPx = 0,
): ComposeLayout {
  const commonH = Math.max(1, Math.min(...sizes.map((s) => s.height)));
  const widths = sizes.map((s) => Math.max(1, (s.width * commonH) / s.height));
  const totalW = widths.reduce((a, b) => a + b, 0);
  const scale = Math.min(1, maxEdge / Math.max(totalW, commonH + headerPx));
  const even = (n: number) => Math.max(2, 2 * Math.floor((n * scale) / 2));
  const tileH = even(commonH);
  const tiles: ComposeLayout['tiles'] = [];
  let x = 0;
  for (const w of widths) {
    const tw = even(w);
    tiles.push({ x, y: headerPx, w: tw, h: tileH });
    x += tw;
  }
  return { width: x, height: tileH + headerPx, tiles };
}

/** Resolve capture sources for a snapshot/export selection (throws if a view is absent). */
export function resolveSources(source: SnapshotSource): CaptureSource[] {
  const ids: CaptureSourceId[] = source === 'both' ? ['globe', 'proximity'] : [source];
  return ids.map((id) => {
    const s = getCaptureSource(id);
    if (!s) {
      throw new Error(
        id === 'proximity'
          ? 'The proximity view is not open — load a scenario first.'
          : 'The global view is not available.',
      );
    }
    return s;
  });
}

/**
 * Render each source and synchronously composite them into `ctx` per `layout`.
 * MUST complete in one JS task (WebGL drawing-buffer validity).
 */
export function renderComposite(
  ctx: CanvasRenderingContext2D,
  sources: CaptureSource[],
  layout: ComposeLayout,
): void {
  ctx.fillStyle = '#0b1020';
  ctx.fillRect(0, 0, layout.width, layout.height);
  sources.forEach((s, i) => {
    s.renderNow();
    const t = layout.tiles[i];
    ctx.drawImage(s.canvas, t.x, t.y, t.w, t.h);
  });
}

/** Small caption drawn into the composed frame (header strip or corner chip). */
export function drawCaption(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  yMid: number,
): void {
  ctx.fillStyle = '#cbd5e1';
  ctx.font = '13px system-ui, -apple-system, sans-serif';
  ctx.textBaseline = 'middle';
  ctx.fillText(text, x, yMid);
}

export function captionFor(scenarioName: string | null, simTime: Date): string {
  const t = simTime.toISOString().replace('T', ' ').slice(0, 19);
  return `${scenarioName ?? 'Catalog'} — ${t} UTC`;
}

/** One-shot PNG snapshot of the selected view(s), downloaded with a header strip. */
export async function snapshotPng(
  source: SnapshotSource,
  scenarioName: string | null,
  simTime: Date,
): Promise<void> {
  const sources = resolveSources(source);
  const layout = computeLayout(
    sources.map((s) => ({ width: s.canvas.width, height: s.canvas.height })),
    PNG_MAX_EDGE,
    HEADER_PX,
  );
  const staging = document.createElement('canvas');
  staging.width = layout.width;
  staging.height = layout.height;
  const ctx = staging.getContext('2d', { alpha: false });
  if (!ctx) throw new Error('2D canvas unavailable');
  renderComposite(ctx, sources, layout); // same-task render + copy
  drawCaption(ctx, captionFor(scenarioName, simTime), 10, HEADER_PX / 2);

  const blob = await new Promise<Blob | null>((resolve) => staging.toBlob(resolve, 'image/png'));
  if (!blob) throw new Error('PNG encoding failed');
  const which = source === 'both' ? 'views' : source;
  downloadBlob(blob, `orbit-${slugify(scenarioName ?? 'catalog')}-${which}-${timeStamp()}.png`);
}
