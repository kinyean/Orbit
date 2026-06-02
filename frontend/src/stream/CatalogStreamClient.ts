// WebSocket client for the shared catalog feed (docs/streaming-contract.md).
//
// Connects to /api/stream/catalog — relative, so the browser stays same-origin
// and the Vite dev proxy (ws: true) forwards to the backend. Reconnects with
// exponential backoff. Refuses a contract-version mismatch rather than feeding
// the renderer garbage (R12).

export interface CatalogMessage {
  contractVersion: string;
  type: string;
  epoch: string;
  satelliteCount: number;
  czml: unknown[];
}

export type StreamStatus = 'connecting' | 'open' | 'closed' | 'version-mismatch';

export interface CatalogStreamHandlers {
  onMessage: (msg: CatalogMessage) => void;
  onStatus?: (status: StreamStatus) => void;
}

const MAX_BACKOFF_MS = 30_000;
const INITIAL_BACKOFF_MS = 1_000;

export class CatalogStreamClient {
  private ws: WebSocket | null = null;
  private closedByUser = false;
  private backoff = INITIAL_BACKOFF_MS;
  private reconnectTimer: number | null = null;

  constructor(
    private readonly url: string,
    private readonly expectedVersion: string,
    private readonly handlers: CatalogStreamHandlers,
  ) {}

  /** Default endpoint derived from the current page origin (via the Vite proxy). */
  static defaultUrl(): string {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${window.location.host}/api/stream/catalog`;
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

    ws.onopen = () => {
      this.backoff = INITIAL_BACKOFF_MS;
      this.handlers.onStatus?.('open');
    };

    ws.binaryType = 'arraybuffer';
    ws.onmessage = (event) => {
      const data = event.data;
      if (typeof data === 'string') {
        this.handleText(data, ws); // tolerate an uncompressed text frame
      } else if (data instanceof ArrayBuffer) {
        inflateGzip(data)
          .then((text) => this.handleText(text, ws))
          .catch(() => {
            /* drop an undecodable frame; the next one self-heals */
          });
      } else if (data instanceof Blob) {
        data
          .arrayBuffer()
          .then(inflateGzip)
          .then((text) => this.handleText(text, ws))
          .catch(() => {});
      }
    };

    ws.onclose = () => {
      this.ws = null;
      if (this.closedByUser) return;
      this.handlers.onStatus?.('closed');
      this.scheduleReconnect();
    };

    ws.onerror = () => {
      // onclose will follow and drive the reconnect.
      ws.close();
    };
  }

  private handleText(text: string, ws: WebSocket): void {
    let msg: CatalogMessage;
    try {
      msg = JSON.parse(text) as CatalogMessage;
    } catch {
      return; // ignore unparseable frames
    }
    if (msg.contractVersion !== this.expectedVersion) {
      // Persistent mismatch — stop trying, surface it.
      this.closedByUser = true;
      this.handlers.onStatus?.('version-mismatch');
      ws.close();
      return;
    }
    this.handlers.onMessage(msg);
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
