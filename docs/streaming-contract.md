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

**Plain JSON, not CZML** — the three.js proximity view consumes it directly. Sent
as the **second** gzip binary frame on connect (after `scenario-czml`), on the
**same** socket. One per-deputy entry (the chief is the LVLH origin, excluded),
on the **same time grid** as the CZML so both views interpolate to the same
instants.

```jsonc
{
  "contractVersion": "1",
  "type": "scenario-relative",
  "epoch": "2026-06-11T00:00:00Z",   // t=0 reference for the samples
  "stepSeconds": 30,                 // effective step (matches the CZML grid)
  "frame": "LVLH",                   // R = radial, I = in-track, C = cross-track
  "chiefId": 25544,
  "includeVelocity": true,           // gates the stride (orbit.scenario.include-relative-velocity)
  "stride": 7,                       // 4 = [t,R,I,C]; 7 = [t,R,I,C,vR,vI,vC]
  "fidelity": "cw",                  // scenario fidelity (Phase 5C); drives the CW warning
  "maxSeparationM": 3200,            // largest chief-relative range over the window
  "chiefEccentricity": 0.0007,       // CW assumes a near-circular chief
  "deputies": [
    { "noradId": 25545, "name": "DEPUTY-1", "interpolationDegree": 5,
      "tcaEpoch": "2026-06-11T00:42:10Z",   // closest approach (Phase 5A); optional
      "tcaDistanceM": 1842,                  // chief-relative range at tcaEpoch (m)
      "samples": [ t, R,I,C, vR,vI,vC,  t, R,I,C, vR,vI,vC,  … ] }
  ]
}
```

- **R/I/C** are metres in the chief's LVLH frame (radial-out / in-track / cross-track);
  velocities (when present) are m/s. The client maps **R→+X, I→+Y, C→+Z** (1 unit = 1 m).
- **Velocity correctness (R15):** the backend builds the LVLH frame **once** from the
  *live* chief propagator and transforms each deputy's ECI state per step — so the
  relative velocity carries the frame's rotation rate. It does **not** use the
  single-epoch `FrameService.toRelativeState` (which would drop that term).
- Positions rounded to whole metres; velocities to mm/s (whole-metre rounding would
  zero out small relative velocities).
- **`tcaEpoch` / `tcaDistanceM`** (Phase 5A, US-REL-02, additive — `VERSION` stays
  `"1"`): the deputy's closest approach to the chief over `[start,end]`, computed on
  the **live propagators at full resolution** (a golden-section refine of the
  coarse sample-grid minimum), not by scanning the clamped samples. Omitted if it
  could not be computed; the client displays it in the relative readout and as a
  timeline tick. Deterministic (fixed-iteration refine — R11).
- **`fidelity` / `maxSeparationM` / `chiefEccentricity`** (Phase 5C, US-REL-03,
  additive): the scenario fidelity plus the CW validity hints. The client shows a
  warning when `fidelity === "cw"` and the separation exceeds ~10 km or the chief
  is not near-circular (CW is a small-separation linearization). In CW mode the
  chief propagates with SGP4 and each deputy is a closed-form CW state-transition
  provider seeded R15-correctly from the chief's live LVLH frame.
- **`chiefRadiusM`** (Phase 6, US-PROX-05, additive): the chief's geocentric radius
  at the epoch — the proximity view places the Earth backdrop at `(−chiefRadiusM,0,0)`.

#### Phase 7 additive fields (sensors, attitude, events — `VERSION` stays `"1"`, R12)

```jsonc
{
  // … all of the above, plus:
  "chief": {                         // the LVLH origin's attitude + sensors (it has no R/I/C)
    "noradId": 25544,
    "att": [ t, qx,qy,qz,qw,  … ],   // stride 5; body orientation in the chief-LVLH scene
    "sensors": [ /* Sensor, see below */ ]
  },
  "deputies": [
    { "noradId": 25545, "…": "…",
      "att": [ t, qx,qy,qz,qw,  … ], // stride 5, on the position grid (Phase 7)
      "sensors": [
        { "id": "…", "kind": "optical", "name": "V-bar imager",
          "fov": { "type": "rect", "halfAngleDeg": 0, "hDeg": 20, "vDeg": 15 },  // or type "cone" → halfAngleDeg
          "minRangeM": 100, "maxRangeM": 50000,
          "mount": { "boresightBody": [1,0,0], "clockDeg": 0 } }
      ] }
  ],
  "events": [                        // acquisition / loss-of-sight (US-EVT-01); omitted if empty
    { "type": "acquisition", "hostId": 25544, "sensorId": "…", "targetId": 25545,
      "epoch": "2026-06-11T00:49:25Z", "rangeM": 565 },
    { "type": "los",         "hostId": 25544, "sensorId": "…", "targetId": 25545,
      "epoch": "2026-06-11T00:58:02Z", "rangeM": 1820 }
  ]
}
```

- **`att`** (per-deputy + in the `chief` block): body-orientation **quaternion samples**
  (three.js convention `(x,y,z,w)`) expressing the craft's body frame in the chief-LVLH
  *scene* frame, on the position grid (stride 5: `[t,qx,qy,qz,qw]`). Computed on the
  backend from a per-craft **attitude profile** (`lvlh` = LVLH-aligned from the orbital
  state — nose +Y along velocity, top +Z radial-out; or `fixed` = constant ECI). The
  client SLERPs between samples; when absent it falls back to a derived estimate. This
  retires the Phase-6 frontend "estimated" orientation (now backend-authoritative).
- **`chief`** block: the chief is excluded from `deputies` (it's the origin), but it has
  attitude + sensors that must render — carried here. `noradId` echoes the chief id.
- **`sensors`** (per craft): static FOV descriptors the client builds geometry from —
  `fov.type` ∈ {`cone` (uses `halfAngleDeg`), `rect` (uses `hDeg`/`vDeg`)}, a range band,
  and a body-fixed `mount.boresightBody` axis.
- **`events`**: acquisition / loss-of-sight crossings over the window — a target enters
  ({`acquisition`}) or leaves ({`los`}) a host sensor's FOV with a clear (Earth-unobstructed)
  line of sight and within range. Computed on the live propagators (sample grid + bisection
  refine), deterministic (R11). The client pairs them into in-view windows on the timeline.
  v1: the FOV test is a circular bound (a rect uses its larger half-angle); occlusion is
  Earth-only; the Sun is Phase 8. See Decision 24.

#### Phase 8 additive fields (Sun/Moon, eclipse, conjunctions, constraints — `VERSION` stays `"1"`, R12)

```jsonc
{
  // … all of the above, plus:
  "sunVector":  [ t, x,y,z,  … ],    // stride 4; Sun unit direction in the chief-LVLH scene
  "moonVector": [ t, x,y,z,  … ],    // stride 4; same layout as sunVector
  "eclipses": [                      // per-craft shadow crossings (US-ENV-02); omitted if empty
    { "type": "penumbra-ingress", "noradId": 25545, "epoch": "2026-06-11T00:31:04Z" },
    { "type": "umbra-ingress",    "noradId": 25545, "epoch": "2026-06-11T00:33:40Z" },
    { "type": "umbra-egress",     "noradId": 25545, "epoch": "2026-06-11T01:06:12Z" },
    { "type": "penumbra-egress",  "noradId": 25545, "epoch": "2026-06-11T01:08:47Z" }
  ],
  "conjunctions": [                  // intra-scenario closest approaches below the
    {                                // scenario's missDistanceThresholdM (US-EVT-02); omitted if empty
      "aNoradId": 25544, "bNoradId": 25545,
      "tcaEpoch": "2026-06-11T00:42:10Z", "missDistanceM": 1842 }
  ],
  "violations": [                    // constraint violations (US-EVT-03); omitted if empty
    { "type": "violation-start",     // or "violation-end"
      "constraintId": "…", "kind": "sun-keep-out",   // or "approach-corridor"
      "hostId": 25544, "sensorId": "…",              // sensorId only for sun-keep-out
      "targetId": 25545, "epoch": "2026-06-11T00:47:00Z",
      "valueDeg": 14.21, "limitDeg": 20.0 }
  ]
}
```

- **`sunVector` / `moonVector`**: flat `[t, x, y, z, …]` unit-direction series in the
  chief-LVLH scene, on the render grid (`t` whole seconds since `epoch`; components to
  1e-6). The client drives the proximity view's `DirectionalLight` (terminator, craft
  illumination) from `sunVector`. Omitted when unavailable — older clients ignore them.
- **`eclipses`**: ingress/egress boundary crossings of the Earth's conical shadow per
  spacecraft, `type` ∈ {`penumbra-ingress`, `umbra-ingress`, `umbra-egress`,
  `penumbra-egress`}. The client pairs them into umbra/penumbra timeline bands and dims
  craft materials while shadowed. Earth shadow only (lunar eclipse of a satellite is
  negligible). Computed from geocentric ECI positions captured in the sampling loop —
  deterministic (R11). See Decision 25.
- **`conjunctions`**: every unordered pair of scenario craft whose closest approach over
  the window falls below the scenario's `missDistanceThresholdM` (schema v5, default
  ~5 km) — canonical `aNoradId < bNoradId`, golden-section-refined on the sampled arrays.
  Drawn as timeline ticks. (Catalog screening is a separate REST request/response, not a
  stream field.)
- **`violations`**: sun-keep-out / approach-corridor constraint checks (`kind`), emitted
  as start/end crossings (`type`); `valueDeg`/`limitDeg` give the offending angle vs the
  configured limit (0.01° rounding). `sensorId` is present only for sun-keep-out (the
  boresight comes from the named sensor).

#### Phase 9 additive field (link budget — `VERSION` stays `"1"`, R12)

```jsonc
{
  // … all of the above, plus:
  "linkBudgets": [                   // per (link-budget sensor ↔ target) SNR series
    { "hostId": 25544, "sensorId": "…", "targetId": 25545,
      "kind": "rf",                  // or "optical"
      "thresholdDb": 6.0,
      "series": [ t, snr,  t, snr,  … ] }   // stride 2: t whole seconds, SNR in dB (0.1 dB)
  ]
}
```

- **`linkBudgets`** (US-EVT-05): one entry per sensor that carries a `LinkBudget`
  (`ScenarioBody` schema v6) × target craft. `series` is a flat `[t, snr, …]` array on a
  strided subset of the render grid (bounded point count); a non-finite SNR is encoded as
  `-999`. The client draws a timeline SNR band, red below `thresholdDb`. Friis model —
  see Decision 27. Omitted when no sensor has a link budget.
