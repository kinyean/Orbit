# Decisions

The single source of truth for "why this, not that" in the satellite tracker.
Read this before deviating — the alternatives listed here have already been
weighed.

Format: each decision has **Context** (what problem), **Decision** (what we
picked), **Why** (the reasoning), **Alternatives considered** (what we rejected
and why), and **Consequences** (what this commits us to).

The decisions are roughly ordered from broadest (whole-app architecture) to
narrowest (specific implementation choices).

## Table of contents

1. [Client-only architecture, no backend in v1](#1-client-only-architecture-no-backend-in-v1)
2. [Framework: React + TypeScript](#2-framework-react--typescript)
3. [Build tool: Vite](#3-build-tool-vite)
4. [3D engine: CesiumJS](#4-3d-engine-cesiumjs)
5. [Propagation: satellite.js](#5-propagation-satellitejs)
6. [State management: Zustand](#6-state-management-zustand)
7. [Data source: CelesTrak](#7-data-source-celestrak)
8. [Hosting: Cloudflare Pages](#8-hosting-cloudflare-pages)
9. [Heavy compute: Web Workers](#9-heavy-compute-web-workers)
10. [Time source of truth: Zustand](#10-time-source-of-truth-zustand)
11. [Coordinate frame: ECEF for rendering, geodetic on demand](#11-coordinate-frame-ecef-for-rendering-geodetic-on-demand)
12. [Worker boundary contract](#12-worker-boundary-contract)
13. [IndexedDB cache schema: one blob per group](#13-indexeddb-cache-schema-one-blob-per-group)
14. [Cache TTL: stale-while-revalidate, 6 hours](#14-cache-ttl-stale-while-revalidate-6-hours)
15. [Render primitive: PointPrimitiveCollection + Entity for selection](#15-render-primitive-pointprimitivecollection--entity-for-selection)
16. [Selection mechanism: Cesium pick + padded hit radius](#16-selection-mechanism-cesium-pick--padded-hit-radius)
17. [Filter semantics: CelesTrak groups + OBJECT_TYPE](#17-filter-semantics-celestrak-groups--object_type)
18. [Deferred decisions (revisit before launch)](#deferred-decisions-revisit-before-launch)

---

## 1. Client-only architecture, no backend in v1

**Context.** A satellite tracker could be built as a server-rendered app, a
backend-with-API model, or as a pure static client. Each adds different
operational cost.

**Decision.** Everything runs in the browser. TLE (Two-Line Element) data
fetched directly from CelesTrak, propagation done in-browser, state held in
memory and IndexedDB. Static files served from a CDN. No servers, no
database, no API.

**Why.** Each user gets an identical self-contained app pulling public data.
~$0/month at any scale; scales to millions of users with no architectural
changes. None of the features that require a backend (user accounts, shared
state, push notifications, hidden API keys) are needed in v1.

**Alternatives considered.**
- *Backend with API.* Required for v3 features (public API tier, historical
  TLE archive, user accounts). Adds it then, not now.
- *Server-rendered (Next.js or similar).* Pointless — Google can't index a
  WebGL canvas, and the whole app is one screen.

**Consequences.**
- Things deferred to a future backend: user accounts that sync across
  devices, historical playback before the cache window, push notifications,
  hidden API keys, the public API tier.
- Anything that needs CORS-blocked APIs needs a proxy layer (not in v1).

---

## 2. Framework: React + TypeScript

**Context.** A single-page interactive app with a 3D canvas and a chrome of
panels around it. Many UI frameworks could do the job.

**Decision.** React (with hooks, function components, `react-jsx` transform)
+ TypeScript in strict mode.

**Why.** React has the deepest ecosystem, the most documentation, and the
largest talent pool. Nothing about this app is unusual enough to justify an
exotic choice. TypeScript matters more here than in a typical web app because
orbital math is full of unit pitfalls (km vs meters, degrees vs radians) —
types catch many before runtime.

**Alternatives considered.**
- *Vue.* What `satvis` uses. Slightly smaller ecosystem, otherwise comparable.
  Fine if forking satvis.
- *Svelte / SolidJS.* Better runtime perf, smaller ecosystems, fewer
  tutorials. Worth picking only if you're already fluent.

**Consequences.**
- Component design uses hooks and per-slice subscriptions to the Zustand
  store (see [Decision 6](#6-state-management-zustand)).
- Strict mode stays on; type errors get fixed, not suppressed.

---

## 3. Build tool: Vite

**Context.** Need a TypeScript bundler that produces static output, runs a
dev server, supports HMR (Hot Module Replacement), and integrates with the
CesiumJS asset story.

**Decision.** Vite + `@vitejs/plugin-react` + `vite-plugin-cesium`.

**Why.** Vite outputs plain static files (deploy anywhere), rebuilds in
sub-second time in dev (vs 2–10 seconds for older tools), has zero server
complexity, and the Cesium plugin handles `CESIUM_BASE_URL` and asset copy.

**Alternatives considered.**
- *Next.js.* Built for apps with a server — SSR, API routes, page-level
  code-splitting. None of which we need.
- *Create React App.* Deprecated, slow, no longer maintained.
- *Webpack directly.* Too much config for the same outcome.

**Consequences.**
- Two `tsconfig.json` files needed: one for browser code in `src/`, one for
  `vite.config.ts` itself (runs in Node).
- Cesium static assets (workers, shaders, imagery, fonts) are copied at build
  time by the plugin.

---

## 4. 3D engine: CesiumJS

**Context.** Need a 3D engine that handles a realistic Earth, day/night
lighting, time-driven scenes, accurate geodetic coordinates, and renders
many objects on a globe.

**Decision.** CesiumJS. This is the single most consequential technical
choice.

**Why.** Cesium solves the boring parts of geospatial 3D natively:
- Accurate Earth ellipsoid (Earth is slightly squashed, not a sphere).
- Terrain elevation.
- Day/night terminator and city-lights imagery.
- Atmosphere glow at the limb.
- A built-in clock object with time-driven scenes — exactly what a satellite
  tracker needs.
- Geodetic coordinate math.

Building these on Three.js takes months. The math is subtle.

**Alternatives considered.**
- *Three.js.* General-purpose 3D engine. Smaller (~600 KB) and stylizable,
  but you'd reimplement everything in the list above. Rejected.
- *deck.gl.* Best-in-class GPU point rendering — could push 100k+ points
  easily. Could layer on top of Cesium for the point cloud in v2. Not a v1
  choice on its own.

**Consequences.**
- Bundle weight is ~3 MB minified (~800 KB gzipped). Acceptable for this
  app's audience and use case.
- Hard to stylize — Cesium is opinionated about looking like a realistic
  Earth.
- Imagery comes from Cesium ion (5 GB egress free, then paid). Self-hosting
  tiles becomes necessary at scale; revisit when usage approaches the cap.

---

## 5. Propagation: satellite.js

**Context.** TLE (Two-Line Element) data is not positions — it's an orbital
element set encoded in two text lines. Turning those into 3D positions at a
given time requires SGP4 (Simplified General Perturbations 4), the standard
analytic propagator.

**Decision.** Use `satellite.js`, the de facto JavaScript SGP4
implementation.

**Why.** Runs in the browser without compilation steps. De facto standard
for amateur satellite tracking, well-tested, ships its own TypeScript
declarations.

**Alternatives considered.**
- *Native C/Fortran SGP4 via WebAssembly.* Roughly 10× faster, but requires
  a build toolchain and an extra layer. Premature optimization for v1.
  Revisit only if profiling shows propagation is the bottleneck after
  workers and interpolation.

**Consequences.**
- Performance ceiling is set by satellite.js's throughput. Worker +
  interpolation hides this for v1's scale.
- Future port to WASM stays an open path.

---

## 6. State management: Zustand

**Context.** The app has shared state (current time, selected satellite,
active filters, camera) read by many components. Need a way to share it
without prop drilling, and without triggering unnecessary re-renders.

**Decision.** Zustand. Components subscribe per-slice (e.g.,
`useStore(s => s.currentTime)`) rather than reading the whole store.

**Why.** Zustand is ~1 KB, has no boilerplate, supports per-slice
subscriptions natively. Per-slice subscriptions avoid the Context API
performance trap where any state change re-renders every consumer.

**Alternatives considered.**
- *React Context.* Has a performance trap: any change to the context value
  re-renders every component that uses it, even for unrelated state. With
  60fps animations this causes visible jank.
- *Redux Toolkit.* Industry standard but overkill for this app — too much
  boilerplate for the amount of state involved.
- *Jotai.* Similar size and ergonomics to Zustand with an atom-based mental
  model. Reasonable alternative; Zustand was picked for the simpler API.

**Consequences.**
- Per-slice subscriptions are the convention, enforced by code review.
- Components do not pass state through props when both ends could subscribe
  to Zustand directly.

---

## 7. Data source: CelesTrak

**Context.** The authoritative source for orbital elements is the U.S. Space
Force's 18th Space Defense Squadron. They distribute through Space-Track.org
(requires registration, no CORS) and CelesTrak (free, no auth, CORS-enabled,
republished from the same upstream).

**Decision.** Fetch from CelesTrak directly in the browser. Endpoint:
`https://celestrak.org/NORAD/elements/gp.php?GROUP=<group>&FORMAT=json`.

**Why.** Free, no auth, browser-fetchable, same underlying data as
Space-Track. The `gp.php` endpoint returns full catalog records (not just
TLE lines) with all the metadata we need for filtering.

**Alternatives considered.**
- *Space-Track.org.* Required for historical data (CelesTrak only serves
  current). Needs registration and a backend proxy for CORS. Add in v2 when
  historical playback is built.

**Consequences.**
- v1 has no historical data; the time scrubber's useful range is "around
  now" only.
- Group endpoints (`starlink`, `oneweb`, etc.) drive the constellation
  filter design (see [Decision 17](#17-filter-semantics-celestrak-groups--object_type)).

---

## 8. Hosting: Cloudflare Pages

**Context.** Static files need a CDN. The free tier matters because
satellite tools can go viral and bandwidth caps elsewhere are tight.

**Decision.** Cloudflare Pages.

**Why.** Unlimited bandwidth on the free tier; 300+ city CDN footprint. For
a tool with international appeal, this is the right tradeoff.

**Alternatives considered.**
- *Vercel.* Polished DX, but 100 GB/month bandwidth cap (~30k uniques with
  a 3 MB initial load).
- *Netlify.* Comparable to Vercel; same cap.

**Consequences.**
- Cloudflare's developer experience is rougher than Vercel's. If it slows
  development meaningfully, migrating takes ~1 hour — build output is
  identical static files.

---

## 9. Heavy compute: Web Workers

**Context.** Propagating 15,000 satellites with SGP4 takes 50–200 ms per
pass. Running this on the main thread blocks rendering, dropping the UI to
~5 fps with visible stutter.

**Decision.** Propagation runs in a Web Worker on a separate thread.
Snapshots come back to the main thread every ~500 ms; main thread linearly
interpolates between snapshots for per-frame motion.

**Why.** A 500 ms snapshot cadence with linear interpolation introduces at
most a few kilometers of error for a fast LEO satellite. At globe scale
(Earth radius ~6,400 km, satellite as one pixel), this is invisible. The
user sees smooth 60 fps motion that's indistinguishable from per-frame
propagation, at a fraction of the compute cost.

**Alternatives considered.**
- *Per-frame propagation on the main thread.* Blocks rendering. Rejected.
- *Per-frame propagation in a worker.* Possible, but wastes CPU —
  interpolation handles the per-frame motion correctly with no perceptible
  difference.

**Consequences.**
- Not built in Phase 2 — Phase 2 propagates on the main thread for a smaller
  subset (a few thousand satellites) to keep iteration speed up. Phase 3
  introduces the worker with the contract defined in
  [Decision 12](#12-worker-boundary-contract).
- The store does not hold per-satellite position objects — see
  [Decision 12](#12-worker-boundary-contract) for the array shape.

---

## 10. Time source of truth: Zustand

**Context.** Three pieces of code care about "what time is it": the time
scrubber and play/pause UI, the satellite propagator, and the Cesium scene
(sun position, animations). If each owns its own clock, they drift apart.

**Decision.** The Zustand store's `currentTime: Date` is the single source
of truth. Cesium's `viewer.clock` is a downstream consumer — an effect in
the Globe component pushes Zustand's `currentTime` into
`viewer.clock.currentTime` whenever it changes. During playback, a single
`requestAnimationFrame` loop advances `currentTime` in the store; Cesium
follows.

**Why.** One-way data flow eliminates desync. The UI naturally writes to
Zustand (every component reads from it); making Zustand authoritative keeps
the UI in charge.

**Alternatives considered.**
- *Cesium clock as source of truth.* Forces the UI to read Cesium imperative
  state on every render. Awkward, and reintroduces the React-vs-imperative
  boundary we already manage.
- *Both as sources with bidirectional sync.* Always ends in infinite loops
  or inconsistent state. Rejected on principle.

**Consequences.**
- All time mutations go through `setCurrentTime`.
- Cesium's internal clock multiplier is unused; speed control is implemented
  in the main animation loop multiplying its delta.
- Conversion to `Cesium.JulianDate` happens only at the Cesium boundary.

---

## 11. Coordinate frame: ECEF for rendering, geodetic on demand

**Context.** Three frames are in play:
- **ECI** (Earth-Centered Inertial): what SGP4 propagation outputs.
- **ECEF** (Earth-Centered Earth-Fixed): what Cesium needs for placing
  objects on the rotating globe.
- **Geodetic** (latitude, longitude, altitude): what humans read in the
  info panel.

Going between frames is a few matrix operations, but doing it for 15,000
objects per frame adds up.

**Decision.** In-memory positions (and the worker boundary) are **ECEF
Cartesians** as `Float32Array` of `[x, y, z, x, y, z, …]`. Geodetic is
computed **on demand** for the selected satellite only, when the info panel
needs it.

**Why.** Cesium consumes ECEF directly with no conversion. Computing
geodetic once per click is free; computing it 15,000 times per second is
wasteful.

**Alternatives considered.**
- *Store geodetic, convert in the renderer.* Current state of
  `propagator.ts`. ~15,000 `Cartesian3.fromDegrees` calls per frame is slow.
- *Store ECI, convert at render time.* Moves work to the render hot path
  for no gain.

**Consequences.**
- `propagator.ts` outputs ECEF (using satellite.js `eciToEcf` after
  `propagate`).
- A small helper `ecefToGeodetic(x, y, z)` for the info panel.
- The worker contract carries ECEF.

---

## 12. Worker boundary contract

**Context.** The worker contract — message shapes, cadence, transfer model
— must be fixed before propagation code is written, or it becomes
load-bearing in two places.

**Decision.** Message types:

```typescript
// main → worker
type ToWorker =
  | { kind: 'init'; catalog: TLE[] }
  | { kind: 'tick'; timestampMs: number };

// worker → main
type FromWorker =
  | { kind: 'positions'; timestampMs: number; ids: Int32Array; ecef: Float32Array }
  | { kind: 'error'; message: string };
```

Cadence: worker propagates every **500 ms**. Main thread holds the last two
snapshots and interpolates linearly between them for each frame.
ArrayBuffers are **transferred** (not copied) via the `postMessage` transfer
list to avoid marshalling cost.

**Why.** Float32Array + transfer is the standard high-performance pattern
(~0 ms overhead vs ~5 ms for clone of 180 KB). Per-satellite objects would
have ~100× the serialization cost.

**Alternatives considered.**
- *Per-satellite object messages.* Easier to debug, dramatically slower.
- *SharedArrayBuffer.* Faster still (no transfer at all), but needs COOP/COEP
  HTTP headers and a more careful synchronization protocol. Revisit if
  transfer becomes a bottleneck.

**Consequences.**
- The store holds `ids: Int32Array` and `ecef: Float32Array` snapshots, not
  a map of satellites.
- Looking up a satellite by NORAD ID at render time uses a separate index
  (`Map<number, number>` from NORAD ID to array offset).

---

## 13. IndexedDB cache schema: one blob per group

**Context.** TLE data is ~10 MB of JSON. Re-fetching on every page load
wastes bandwidth and slows boot. IndexedDB is the browser's structured local
database (much higher quota than localStorage).

**Decision.** One object store called `tle-groups`, keyed by group name
(`'active'`, `'starlink'`, etc.). Each row:

```typescript
{
  group: 'active',
  fetchedAt: 1716688800000,   // ms epoch
  records: CelestrakRecord[],
}
```

**Why.** The runtime never queries IndexedDB by satellite ID — it loads the
whole catalog into memory at boot. Per-satellite schema would optimize for
queries we never run.

**Alternatives considered.**
- *Row per satellite, indexed by NORAD ID.* In principle supports queries
  by ID and per-satellite updates. In practice we never use either:
  runtime queries hit in-memory data, and CelesTrak only provides
  whole-group fetches with no diff endpoint. YAGNI (You Aren't Gonna Need
  It).
- *localStorage.* Synchronous, ~5 MB quota, string-only. Too small.

**Consequences.**
- If user-specific per-satellite data is added later (favorites, notes,
  view counts), it goes in a **separate** object store, not mixed with the
  catalog.
- Cache invalidation is per-group, not per-satellite.

---

## 14. Cache TTL: stale-while-revalidate, 6 hours

**Context.** CelesTrak data updates every few hours. Refreshing more often
wastes bandwidth; refreshing less often shows outdated positions.

**Decision.** Wall-clock TTL (Time To Live) of 6 hours. If the cached entry
is fresh, use it. If stale, **serve the stale data immediately for instant
boot**, and kick off a background fetch that updates the store when it
arrives.

```typescript
const STALE_MS = 6 * 60 * 60 * 1000;
const isStale = (entry) => Date.now() - entry.fetchedAt > STALE_MS;
```

**Why.** Stale-while-revalidate gives the best UX: globe populates instantly
on every boot after the first; refreshes silently if needed. Users never
see a spinner blocking the UI.

**Alternatives considered.**
- *Hard TTL.* Block boot on refetch if stale. Worse UX for marginal
  accuracy gain.
- *Per-record `EPOCH` check.* Each TLE has an `EPOCH` indicating when those
  orbital elements were computed; records past ~14 days are inaccurate.
  Overkill for v1; revisit if showing accuracy warnings becomes a feature.

**Consequences.**
- First-time visitors wait for the initial fetch.
- Returning visitors get an instant globe.
- `fetchedAt` should be surfaced somewhere (likely the stats overlay) as a
  "last updated" indicator.

---

## 15. Render primitive: PointPrimitiveCollection + Entity for selection

**Context.** Cesium offers three rendering abstraction levels: `Entity`
(high-level, ~1k feasible), `PointPrimitiveCollection` (batched, ~50k
feasible), and custom `Primitive` with GPU instancing (no practical limit,
huge code cost).

**Decision.** All catalog satellites are drawn as a single
`PointPrimitiveCollection`. The **selected** satellite *additionally* gets
an `Entity` overlay carrying its orbit polyline, ground track, and label.

**Why.** PointPrimitiveCollection handles 15k objects in one batched draw
call. Per-object features (orbit lines, labels, follow-cam) only matter for
the one selected satellite, so paying Entity overhead there is trivial.

Bonus: Cesium's `viewer.trackedEntity = entity` gives smooth camera-follow
for free, but only on Entities. Using an Entity for the selected satellite
gets follow-cam without writing a custom camera controller.

**Alternatives considered.**
- *All Entities.* Won't hit 60 fps past ~1,000 satellites.
- *Custom Primitive with GPU instancing.* Best performance ceiling but
  requires writing WebGL shaders. Defer until we hit a real bottleneck.

**Consequences.**
- Two parallel render paths: bulk (PointPrimitiveCollection) and selected
  (Entity).
- Filters that hide objects can either remove from the collection or set
  `pixelSize: 0`; pick after profiling.

---

## 16. Selection mechanism: Cesium pick + padded hit radius

**Context.** Satellites render as 3-pixel points and move fast. Clicking
accurately on a moving 3-pixel target is hard.

**Decision.** Use `ScreenSpaceEventHandler` for click events, then
`viewer.scene.pick(position)` for the primary hit test. If `pick` returns
nothing, scan a ±5-pixel square around the click and return the closest
satellite in screen space. On a successful pick:

1. Set `selectedId` in the store.
2. Add (or update) the selection Entity with orbit polyline, label,
   ground track.
3. Set `viewer.trackedEntity` to that Entity for smooth camera follow.
4. Update the info panel.
5. Update the URL (e.g., `?sat=25544`).

**Why.** Native `scene.pick` is pixel-perfect and fast; the surrounding
square pick adds forgiveness without sacrificing precision when the user
clicks accurately. Camera follow via `trackedEntity` is built-in and
smooth.

**Alternatives considered.**
- *Manual raycasting.* More work, same result.
- *Auto-pause on hover.* Feels wrong when the user is panning. Pause on
  **select** is acceptable.

**Consequences.**
- Time speed control in `TimeController` lets users slow down playback to
  click on fast-moving objects.
- URL state must support encoding/decoding selection — the deep-linking
  contract is established at the same time as selection logic.

---

## 17. Filter semantics: CelesTrak groups + OBJECT_TYPE

**Context.** Users will toggle constellations ("show Starlink", "show
GPS") and object types ("hide debris"). We need a reliable way to
identify which satellites belong to each group.

**Decision.** Hybrid:
- **Constellations** are identified by fetching the CelesTrak group
  endpoint (`?GROUP=starlink`, `?GROUP=oneweb`, etc.) once and building
  `Set<NORAD_ID>` membership tables.
- **Object types** (PAYLOAD / DEBRIS / ROCKET BODY) come from the
  `OBJECT_TYPE` field on each CelesTrak record.

Filter check is O(1) per satellite per frame: Set lookup for
constellation, string equality for type.

**Why.** CelesTrak's groups are curated by domain experts. Substring
matching on `OBJECT_NAME` is brittle (false positives and negatives).
Hardcoded NORAD ID lists go stale.

**Alternatives considered.**
- *Name-prefix matching ("STARLINK-*").* Brittle across constellations.
- *Hardcoded NORAD ID lists.* Stale on day one.

**Consequences.**
- Boot fetches multiple CelesTrak endpoints (the `active` set plus one per
  supported constellation). Total bytes are similar — group fetches
  return subsets of `active`.
- `recordToTLE` is extended to preserve `OBJECT_TYPE`.
- A `loadCatalog()` orchestrator fans out the requests and assembles the
  membership tables.

---

## Deferred decisions (revisit before launch)

Explicitly **not** decided yet. Each has a tracked reason.

- **Runtime data validation (Zod / Valibot).** Currently trusting
  CelesTrak's response shape. Fine for development. Add before launch — a
  silent shape change at the source will otherwise crash the app with
  `undefined` propagation.
- **Testing strategy.** No tests yet. Minimum bar before launch:
  propagator unit tests against known `(TLE, time, expected position)`
  fixtures.
- **Error handling.** No policy for CelesTrak 5xx, parse failures,
  IndexedDB quota errors. Default to log-and-skip for now; revisit when
  designing the error UI.
- **Mobile design.** Plan ships responsive in v1. Designs and performance
  budgets not yet set — revisit before Phase 7.
- **URL state schema.** Phase 6 work. Selection encoding will be set
  alongside Phase 4 (selection mechanism); camera and filter encoding are
  open.
- **Bundle splitting / lazy Cesium load.** Defer until Lighthouse audit in
  Phase 7.
- **Self-hosting Cesium imagery tiles.** Using Cesium ion for v1. Switch
  trigger: hitting the 5 GB/month free-tier ceiling.
- **Analytics tooling.** Plausible or Umami at launch — pick when there's
  something to measure.
- **Error monitoring.** Plausible tracks page views, not exceptions.
  Sentry's free tier is enough; add before launch.
