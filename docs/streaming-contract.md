# Streaming contract (v1)

The wire format between the backend propagation service and the frontend
viewports. This is the decoupling seam of [Decision 9](./decisions.md); the
frontend never propagates, it only renders what arrives here
([Decision 10](./decisions.md)).

Versioned to survive backend/frontend skew ([R12](./risks.md)): every message
carries `contractVersion`. The client refuses to process a message whose
version it doesn't recognise and surfaces a clear error rather than rendering
garbage.

**Current version: `1`.** Phase 2 implements only the **catalog** channel. The
per-scenario channel (CZML + relative-state for the proximity view) is added in
Phase 4 and will extend, not replace, this contract.

---

## Transport

- **WebSocket**, raw text frames (not STOMP, not SockJS).
- Catalog endpoint: `/stream/catalog` — from the browser, reached as
  `/api/stream/catalog` through the Vite dev proxy (`ws: true` is already set
  in `vite.config.ts`), so the browser stays same-origin.
- One shared broadcast: every connected client receives the same catalog
  messages ([Decision 13](./decisions.md) — one SGP4 pass, fan-out to all).

## Wire framing — gzip binary

Each message is the JSON envelope below, **gzip-compressed and sent as a binary
WebSocket frame**. CZML is ~10× compressible; an uncompressed multi-MB frame
drains fine over loopback but resets over a real network within the send-time
limit. The client inflates with the native `DecompressionStream('gzip')` and
then parses JSON. (A plain text frame is also tolerated by the client as a
fallback.)

## Message envelope

After inflation, each frame is one JSON object:

```jsonc
{
  "contractVersion": "1",
  "type": "catalog-czml",
  "epoch": "2026-06-02T01:00:00Z",   // ISO-8601; the t=0 reference for cartesian samples
  "satelliteCount": 15501,
  "czml": [ /* a CZML packet array — see below */ ]
}
```

Client algorithm:
1. Parse the frame as JSON.
2. If `contractVersion !== "1"` → do **not** process; show "stream version
   mismatch" and stop. (Forward-compat: a newer client may accept a set of
   versions.)
3. Pass `message.czml` to `CzmlDataSource.process(...)`.

`process()` merges packets **by `id`**, so re-sending a satellite's packet with
a fresh `position` block updates that entity in place — no full reload. The
first message a client receives doubles as the warm start (the handler sends
the latest pass immediately on connect).

## CZML payload (`czml` array)

Standard CesiumJS CZML. First element is the document packet; the rest are one
packet per satellite.

```jsonc
[
  { "id": "document", "name": "orbit-catalog", "version": "1.0" },

  {
    "id": "sat-25544",                       // "sat-<NORAD>"
    "name": "ISS (ZARYA)",                   // OBJECT_NAME
    "properties": {                          // constant custom props, typed
      "noradId":        { "number": 25544 },
      "inclinationDeg": { "number": 51.64 },
      "periodMinutes":  { "number": 92.8 }
    },
    "point": { "pixelSize": 3, "color": { "rgba": [180, 200, 255, 200] } },
    "position": {
      "epoch": "2026-06-02T01:00:00Z",
      "interpolationAlgorithm": "LAGRANGE",
      "interpolationDegree": 3,              // clamped to min(5, samples-1)
      "referenceFrame": "FIXED",             // ECEF / ITRF
      "cartesian": [ 0, X0,Y0,Z0,  60, X1,Y1,Z1,  120, X2,Y2,Z2,  180, X3,Y3,Z3 ]
    }
  }
  // ... ~15k more
]
```

### Conventions (read carefully — frame mismatch is the #1 footgun)

- **`referenceFrame: "FIXED"`** → positions are **ECEF/ITRF, in metres**. The
  backend does the TEME→ECEF transform (Orekit); the client renders directly
  over the rotating globe with no client-side frame math.
- **`cartesian` is a flat interleaved array** `[t, X, Y, Z, t, X, Y, Z, …]`
  where `t` is **seconds since `epoch`** (a number, not an ISO string), and
  `X/Y/Z` are ECEF metres. It is NOT parallel arrays.
- Values are **rounded to whole units** (1 m position, 1 s time). At a 3-pixel
  dot and globe scale this is far below visible resolution and roughly halves
  the payload.
- `interpolationDegree` is clamped server-side to `min(5, sampleCount-1)` so a
  short sample window never asks for a higher-degree fit than it has points
  for.
- **No `clock` packet.** The frontend owns the clock (Decision 11): it runs the
  Cesium viewer clock at real time and interpolates the FIXED samples as that
  clock advances. Phase 4 replaces this with the shared simulation clock.

### Picking

Point entities are pickable by default. On click,
`viewer.scene.pick(position).id` is the Cesium `Entity`. Read identity/metadata:
- NORAD id: parse the entity `id` (`"sat-25544"` → `25544`) or
  `entity.properties.noradId.getValue(time)`.
- name: `entity.name`.
- inclination / period: `entity.properties.inclinationDeg|periodMinutes.getValue(time)`.
- current lat/lon/alt: derive client-side from the entity's current position
  via `Cesium.Cartographic.fromCartesian(...)` — not sent on the wire.

## Cadence (Phase 2 defaults, tuned in Step K)

| Parameter | Default | Config key |
|---|---|---|
| Propagation pass interval | 30 s | `orbit.catalog.propagation-interval-ms` |
| Sample window | 180 s | `orbit.catalog.window-seconds` |
| Sample step | 60 s | `orbit.catalog.step-seconds` |

Each pass propagates `[now, now+window]` at `step` intervals and broadcasts the
full document + per-satellite packets. With a 180 s window refreshed every 30 s,
the real-time viewer clock always sits inside the latest interpolation window.

Outbound message size: a ~15 k-satellite chunk is multi-MB of JSON, but CZML is
highly repetitive and `permessage-deflate` compresses it heavily; Tomcat
imposes no outbound per-message buffer cap (the oft-cited 8 KB limit is inbound
only).
