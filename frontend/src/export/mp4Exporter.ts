/**
 * MP4 sequence export (Phase 11B, US-IO-02 — SRS §4.2.3).
 *
 * A deterministic frame-stepped OFFLINE render: pause the shared clock, hand
 * both viewports' render loops to the exporter (captureRegistry export mode),
 * then for each output frame set `store.currentTime`, render synchronously,
 * composite to an offscreen 2D canvas, and encode with WebCodecs `VideoEncoder`
 * (H.264) into an MP4 via `mp4-muxer`. Output smoothness is independent of the
 * machine's live frame rate — a dropped-frame-free video regardless of load
 * (unlike MediaRecorder realtime capture; see Decision 29).
 *
 * Clock discipline (Decision 11): the exporter never adds a time writer — it
 * pauses playback (`seek`) and steps time through the existing `setCurrentTime`
 * action, exactly the path user scrubbing takes; `clockEngine.tick` only writes
 * while `isPlaying`. Clock state is restored in `finally` (also on cancel).
 */

import { ArrayBufferTarget, Muxer } from 'mp4-muxer';
import { useStore } from '../store/useStore';
import {
  computeLayout,
  drawCaption,
  renderComposite,
  resolveSources,
  type SnapshotSource,
} from './capture';
import { downloadBlob, slugify, timeStamp } from './download';

export interface Mp4ExportOptions {
  source: SnapshotSource;
  /** Sim-time range to render, ms since epoch (already clamped to the scenario window). */
  startMs: number;
  endMs: number;
  /** Video playback speed: sim-seconds covered per video-second. */
  simSecondsPerVideoSecond: number;
  fps: number;
  scenarioName: string;
  onProgress?: (frame: number, total: number) => void;
}

export interface Mp4ExportHandle {
  /** Abort after the current frame; clock + render loops are restored. */
  cancel(): void;
  /** Resolves on completion or cancel; rejects on error (state still restored). */
  done: Promise<void>;
}

/** Hard cap on output frames (memory/time guard — 60 s of video at 30 fps). */
export const MP4_MAX_FRAMES = 1800;
/** Output long-edge cap (keeps the encode within H.264 level 4.0). */
export const MP4_MAX_EDGE = 1920;
const BITRATE = 8_000_000;

/** Descending codec ladder: High 4.0 → Main 4.0 → Baseline 3.1. */
const CODEC_LADDER = ['avc1.640028', 'avc1.4d0028', 'avc1.42E01F'];

/** WebCodecs present at all? (Fine-grained config support is checked per export.) */
export function isMp4Supported(): boolean {
  return typeof VideoEncoder !== 'undefined' && typeof VideoFrame !== 'undefined';
}

/** Frames a given range/speed/fps produces, after the MAX_FRAMES clamp. */
export function mp4FrameCount(rangeMs: number, simSecondsPerVideoSecond: number, fps: number): number {
  if (rangeMs <= 0 || simSecondsPerVideoSecond <= 0 || fps <= 0) return 0;
  const frames = Math.ceil((rangeMs / 1000 / simSecondsPerVideoSecond) * fps);
  return Math.max(1, Math.min(MP4_MAX_FRAMES, frames));
}

const nextTask = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

async function pickCodec(width: number, height: number, fps: number): Promise<string | null> {
  for (const codec of CODEC_LADDER) {
    try {
      const res = await VideoEncoder.isConfigSupported({
        codec,
        width,
        height,
        bitrate: BITRATE,
        framerate: fps,
      });
      if (res.supported) return codec;
    } catch {
      // malformed/unknown config on this browser — try the next rung
    }
  }
  return null;
}

export function exportMp4(opts: Mp4ExportOptions): Mp4ExportHandle {
  let aborted = false;
  return { cancel: () => (aborted = true), done: run() };

  async function run(): Promise<void> {
    if (!isMp4Supported()) throw new Error('WebCodecs is not available in this browser.');
    const sources = resolveSources(opts.source);
    const layout = computeLayout(
      sources.map((s) => ({ width: s.canvas.width, height: s.canvas.height })),
      MP4_MAX_EDGE,
    );
    const total = mp4FrameCount(opts.endMs - opts.startMs, opts.simSecondsPerVideoSecond, opts.fps);
    if (total === 0) throw new Error('Empty export range.');

    const codec = await pickCodec(layout.width, layout.height, opts.fps);
    if (!codec) throw new Error('No supported H.264 encoder configuration (WebCodecs).');

    const target = new ArrayBufferTarget();
    const muxer = new Muxer({
      target,
      video: { codec: 'avc', width: layout.width, height: layout.height, frameRate: opts.fps },
      fastStart: 'in-memory',
    });

    let encoderError: unknown = null;
    const encoder = new VideoEncoder({
      output: (chunk, meta) => muxer.addVideoChunk(chunk, meta),
      error: (e) => {
        encoderError = e;
      },
    });
    encoder.configure({
      codec,
      width: layout.width,
      height: layout.height,
      bitrate: BITRATE,
      framerate: opts.fps,
      latencyMode: 'quality',
      avc: { format: 'avc' },
    });

    const compose = document.createElement('canvas');
    compose.width = layout.width;
    compose.height = layout.height;
    const ctx = compose.getContext('2d', { alpha: false });
    if (!ctx) throw new Error('2D canvas unavailable.');

    // Freeze the clock (seek pauses — the sanctioned writer path) and take over
    // both render loops for the duration.
    const st = useStore.getState();
    const saved = { time: st.currentTime, isPlaying: st.isPlaying };
    st.seek(new Date(opts.startMs));
    sources.forEach((s) => s.setExportMode(true));

    try {
      // Warm-up render at t0 (lets Cesium swap in pending imagery tiles; far-zoom
      // imagery may still refine during the clip — documented in the panel).
      sources.forEach((s) => s.renderNow());

      const simStepMs = (opts.simSecondsPerVideoSecond * 1000) / opts.fps;
      for (let i = 0; i < total; i++) {
        if (aborted) return;
        if (encoderError) throw encoderError;

        const simT = new Date(opts.startMs + i * simStepMs);
        useStore.getState().setCurrentTime(simT);
        renderComposite(ctx, sources, layout); // same-task render + copy of every view
        // Sim-time chip (bottom-left) so the clip is briefing-readable.
        ctx.fillStyle = 'rgba(11, 16, 32, 0.65)';
        ctx.fillRect(8, layout.height - 28, 196, 20);
        drawCaption(ctx, `${simT.toISOString().replace('T', ' ').slice(0, 19)} UTC`, 14, layout.height - 18);

        const frame = new VideoFrame(compose, {
          timestamp: Math.round((i * 1e6) / opts.fps),
          duration: Math.round(1e6 / opts.fps),
        });
        encoder.encode(frame, { keyFrame: i % (opts.fps * 2) === 0 });
        frame.close();
        opts.onProgress?.(i + 1, total);

        // Backpressure (encode-as-you-go, nothing retained) + UI responsiveness.
        while (encoder.encodeQueueSize > 2) await nextTask();
        if (i % 2 === 1) await nextTask();
      }

      await encoder.flush();
      muxer.finalize();
      downloadBlob(
        new Blob([target.buffer], { type: 'video/mp4' }),
        `orbit-${slugify(opts.scenarioName)}-${timeStamp()}.mp4`,
      );
    } finally {
      try {
        if (encoder.state !== 'closed') encoder.close();
      } catch {
        // already closed / errored — nothing to release
      }
      sources.forEach((s) => s.setExportMode(false));
      const restore = useStore.getState();
      restore.setCurrentTime(saved.time);
      if (saved.isPlaying && !restore.isPlaying) restore.togglePlay();
    }
  }
}
