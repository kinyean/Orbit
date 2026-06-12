# Streaming contract (v1)

The wire format between the backend propagation service and the frontend
viewports. This is the decoupling seam of [Decision 9](./decisions.md); the
frontend never propagates, it only renders what arrives here
([Decision 10](./decisions.md)).

Versioned to survive backend/frontend skew ([R12](./risks.md)): every message
carries `contractVersion`. The client refuses to process a message whose
version it doesn't recognise and surfaces a clear error rather than rendering
garbage.

**Current version: `1`.** Phase 2 implemented the **catalog** channel; Phase 4
adds the **per-scenario** channel (slice 4A: `scenario-czml` for the global-view
scenario layer; slice 4B: `scenario-relative` for the proximity view). These
are purely additive — same `VERSION = "1"` (R12).

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

### Live time-travel: `seek` → `catalog-snapshot` (Phase 4, Decision 21)

By default the catalog is a shared realtime broadcast. To step/scrub the live
catalog to another instant — or to *play forward/backward from* it — the client
sends an inbound text frame on the *same* `/stream/catalog` socket:

```jsonc
{ "kind": "seek", "epoch": "2026-01-01T00:00:00Z", "windowSeconds": 800 }
```

`windowSeconds` is optional (omit/0 → the default broadcast window; clamped
server-side to ≤2400 s). The client widens it with the playback rate when
playing from a traveled time, and re-requests the next window *before* the clock
reaches the current one's edge (rolling prefetch), so motion stays continuous up
to the 100× live-mode cap. The backend propagates the whole tracked set over
`[epoch, epoch+windowSeconds]` (past or future) and replies **to that one
session** with a message identical in shape to the broadcast but tagged
`type: "catalog-snapshot"`:

```jsonc
{ "contractVersion": "1", "type": "catalog-snapshot", "epoch": "2026-01-01T00:00:00Z",
  "satelliteCount": 15679, "czml": [ /* same packets as the broadcast */ ] }
```

Client rule: apply `catalog-snapshot` **always**; apply the live `catalog-czml`
broadcast **only while live** (not frozen / not in a scenario), so a traveled
view isn't yanked back to "now". Unknown inbound frames are ignored. This is a
per-user, on-demand computation (a bounded extension of Decision 13 — the
broadcast still serves the common "now" view).

### Orbit path: `orbit` → `catalog-orbit` (Phase 4)

Single-clicking a satellite toggles a dashed orbit-path polyline on the globe
(multiple at once; click again to remove). The catalog stream only carries a
~180 s window, so the path is fetched on demand from the same socket:

```jsonc
{ "kind": "orbit", "noradId": 25544, "epoch": "2026-06-11T00:00:00Z" }
```

`epoch` is optional (omit → server "now"). The backend propagates that one
satellite over **one orbital period** from `epoch` and replies to that session
with its ECEF path (positions only, no time):

```jsonc
{ "contractVersion": "1", "type": "catalog-orbit", "noradId": 25544,
  "cartesian": [ X,Y,Z, X,Y,Z, ... ] }   // ECEF metres, ~181 points (one period)
```

The client draws it as a `PolylineDashMaterialProperty` line (`arcType: NONE`,
so it follows the orbit at altitude rather than clamping to the surface) and
**re-requests at the current clock as the sim time advances**, so the path stays
live (it precesses west over time in ECEF, matching the moving dots). An unknown
NORAD id yields no reply.

---

## Per-scenario channel (Phase 4)

The scenario channel streams one saved scenario's chief + deputies for playback.
Unlike the catalog (one shared broadcast), this is **per-connection**.

### Transport + endpoint

- WebSocket, gzip binary frames (same framing as the catalog).
- Endpoint: `/stream/scenario/{id}` — from the browser, `/api/stream/scenario/{id}`
  through the Vite proxy. The `{id}` is the scenario UUID.
- **Precompute-once model.** Scenarios are bounded (≤11 spacecraft, finite
  `[start,end]`). On connect the backend computes the **whole** ephemeris in one
  pass and pushes it; playback (scrub/rate/reverse) is then pure client-side
  clock math over the delivered samples (Decision 11). The socket stays open
  idle afterward — a client→server control channel is **reserved** for Phase 5
  re-propagation (maneuver edits); messages on it are ignored in v1.

### Identity + authorization

Identity is captured at the **handshake** (the WS thread runs outside the
servlet security filter window). The connection is gated to the scenario's owner;
a missing / soft-deleted / not-owned scenario all collapse to one error so ids
can't be enumerated.

### Close codes (application range)

| Code | Meaning |
|---|---|
| `4400` | malformed scenario id in the path |
| `4404` | scenario does not exist, is soft-deleted, **or is not owned** (collapsed) |
| `4422` | unprocessable body: CW fidelity (Phase 5), non-TLE state, or TLE parse failure |

The client treats `4400/4403/4404/4422` as **fatal** (no reconnect) and only
reconnects on transport drops.

### Message: `scenario-czml` (slice 4A — global-view scenario layer)

Same envelope/packet shape as the catalog, plus a `stepSeconds` echo and, per
satellite, a `role` property + role-colored marker + an orbit-`path` trail. The
trail is a **dotted** `polylineDash` line one orbital period long
(`leadTime`/`trailTime` ≈ half a period each), so it **sweeps with the clock**
rather than showing the whole run statically. Packet ids are `scn-<NORAD>` (no
collision with catalog `sat-<NORAD>`).

```jsonc
{
  "contractVersion": "1",
  "type": "scenario-czml",
  "epoch": "2026-06-11T00:00:00Z",
  "satelliteCount": 2,
  "stepSeconds": 30,                 // EFFECTIVE step (raised if the sample cap bites)
  "czml": [
    { "id": "document", "name": "orbit-scenario", "version": "1.0" },
    {
      "id": "scn-25544",
      "name": "ISS (ZARYA)",
      "properties": { "noradId": { "number": 25544 }, "role": "chief" },
      "point": { "pixelSize": 10, "color": { "rgba": [255,209,102,255] }, "outlineColor": {"rgba":[255,255,255,220]}, "outlineWidth": 1 },
      "path":  { "width": 1.5, "resolution": 30, "leadTime": 2780, "trailTime": 2780,
                 "material": { "polylineDash": { "color": { "rgba": [255,209,102,230] }, "dashLength": 6 } } },
      "position": { "epoch": "...", "interpolationAlgorithm": "LAGRANGE", "interpolationDegree": 5,
                    "referenceFrame": "FIXED", "cartesian": [ 0,X,Y,Z, 30,X,Y,Z, ... ] }
    }
    // ... deputies (role "deputy", cyan)
  ]
}
```

- **`stepSeconds` is the effective step**, never silently truncated: the backend
  raises it so `samples ≤ orbit.scenario.max-samples-per-sat` and echoes the
  value used (R8).
- Positions are **FIXED/ECEF metres** (whole-unit rounded), like the catalog.

### Message: `scenario-relative` (slice 4B — proximity view)

Plain JSON (not CZML); LVLH (R/I/C) samples relative to the chief, consumed
directly by the three.js proximity view. Documented when 4B lands.
