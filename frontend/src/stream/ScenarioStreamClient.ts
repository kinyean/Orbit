// WebSocket client for the per-scenario feed (docs/streaming-contract.md,
// Phase 4 / US-STREAM-02).
//
// Connects to /api/stream/scenario/{id} — relative, so the browser stays
// same-origin and the Vite dev proxy (ws: true) forwards to the backend. Mirrors
// CatalogStreamClient (gzip via DecompressionStream, contract-version refusal,
// exponential-backoff reconnect on transport drops) but:
//   - routes by message `type`: scenario-czml → onCzml, scenario-relative →
//     onRelative (the proximity view, slice 4B);
//   - does NOT reconnect on the application close codes 4400/4403/4404/4422
//     (fatal: bad id / not found / unprocessable) — only on transport drops.
//
// On success the backend keeps the socket open (idle) for the reserved Phase-5
// control channel, so onclose normally only fires on a real disconnect.

export interface ScenarioCzmlMessage {
  contractVersion: string;
  type: string;
  epoch: string;
  satelliteCount: number;
  stepSeconds: number;
  czml: unknown[];
}

/** The compact LVLH relative-state block (consumed by the proximity view in 4B). */
export interface ScenarioRelativeMessage {
  contractVersion: string;
  type: string;
  epoch: string;
  stepSeconds: number;
  frame: string;
  [k: string]: unknown;
}

export type ScenarioStreamStatus =
  | 'connecting'
  | 'open'
  | 'closed'
  | 'version-mismatch'
  | 'rejected';

export interface ScenarioStreamHandlers {
  onCzml: (czml: unknown[], msg: ScenarioCzmlMessage) => void;
  onRelative?: (msg: ScenarioRelativeMessage) => void;
  onStatus?: (status: ScenarioStreamStatus) => void;
}

const MAX_BACKOFF_MS = 30_000;
const INITIAL_BACKOFF_MS = 1_000;

/** Application close codes the backend uses to signal a fatal, non-retryable refusal. */
const FATAL_CLOSE_CODES = new Set([4400, 4403, 4404, 4422]);

export class ScenarioStreamClient {
  private ws: WebSocket | null = null;
  private closedByUser = false;
  private backoff = INITIAL_BACKOFF_MS;
  private reconnectTimer: number | null = null;

  constructor(
    private readonly url: string,
    private readonly expectedVersion: string,
    private readonly handlers: ScenarioStreamHandlers,
  ) {}

  /** Endpoint for a scenario id, derived from the page origin (via the Vite proxy). */
  static urlForScenario(id: string): string {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${window.location.host}/api/stream/scenario/${encodeURIComponent(id)}`;
  }

  connect(): void {
    this.closedByUser = false;
    this.open();
  }

  close(): void {
    this.closedByUser = true;
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.ws?.close();
    this.ws = null;
  }

  private open(): void {
    this.handlers.onStatus?.('connecting');
    const ws = new WebSocket(this.url);
    this.ws = ws;
    ws.binaryType = 'arraybuffer';

    ws.onopen = () => {
      this.backoff = INITIAL_BACKOFF_MS;
      this.handlers.onStatus?.('open');
    };

    ws.onmessage = (event) => {
      const data = event.data;
      if (typeof data === 'string') {
        this.handleText(data, ws);
      } else if (data instanceof ArrayBuffer) {
        inflateGzip(data)
          .then((text) => this.handleText(text, ws))
          .catch(() => {
            /* drop an undecodable frame */
          });
      } else if (data instanceof Blob) {
        data.arrayBuffer().then(inflateGzip).then((text) => this.handleText(text, ws)).catch(() => {});
      }
    };

    ws.onclose = (event) => {
      this.ws = null;
      if (this.closedByUser) return;
      if (FATAL_CLOSE_CODES.has(event.code)) {
        // The server refused this scenario — don't hammer it with reconnects.
        this.closedByUser = true;
        this.handlers.onStatus?.('rejected');
        return;
      }
      this.handlers.onStatus?.('closed');
      this.scheduleReconnect();
    };

    ws.onerror = () => {
      ws.close(); // onclose drives the reconnect decision
    };
  }

  private handleText(text: string, ws: WebSocket): void {
    let msg: { contractVersion?: string; type?: string };
    try {
      msg = JSON.parse(text);
    } catch {
      return; // ignore unparseable frames
    }
    if (msg.contractVersion !== this.expectedVersion) {
      this.closedByUser = true;
      this.handlers.onStatus?.('version-mismatch');
      ws.close();
      return;
    }
    if (msg.type === 'scenario-czml') {
      this.handlers.onCzml((msg as ScenarioCzmlMessage).czml, msg as ScenarioCzmlMessage);
    } else if (msg.type === 'scenario-relative') {
      this.handlers.onRelative?.(msg as ScenarioRelativeMessage);
    }
  }

  private scheduleReconnect(): void {
    const delay = this.backoff;
    this.backoff = Math.min(this.backoff * 2, MAX_BACKOFF_MS);
    this.reconnectTimer = window.setTimeout(() => this.open(), delay);
  }
}

/** Inflate a gzip-compressed frame using the native DecompressionStream. */
async function inflateGzip(buffer: ArrayBuffer): Promise<string> {
  const stream = new Blob([buffer]).stream().pipeThrough(new DecompressionStream('gzip'));
  return await new Response(stream).text();
}
