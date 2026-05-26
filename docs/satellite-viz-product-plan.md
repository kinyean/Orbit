# Satellite visualization platform

A product plan, feature inventory, tech stack rationale, and v1 build guide.

---

## 1. Context

Existing satellite visualization tools fall into two camps. The professional ones (LeoLabs, AstriaGraph) look gorgeous but are read-only marketing showcases — you can't query them, share specific states, or extend them. The free ones (Stuff in Space, N2YO, satvis, Heavens-Above) cover the basic mechanics but feel dated, ad-heavy, or built like a thesis project.

The opportunity is in the middle: a public tool that looks as good as LeoLabs, has real analytical features, runs smoothly on mobile, and is genuinely useful for both space enthusiasts and professionals.

As of early 2026, there are approximately 14,500 active satellites in Earth orbit (roughly 9,900 of which are SpaceX Starlink). Including defunct spacecraft, rocket bodies, and tracked debris fragments, the total number of tracked objects exceeds 43,000. Both numbers are growing fast — Starlink alone is adding ~60 satellites per week.

---

## 2. Pain points in existing tools

| Tool | Strengths | Gaps |
|---|---|---|
| LeoLabs Visualization | Beautiful, accurate, professional feel | Read-only, no analytics for public, no sharing, no historical playback |
| Stuff in Space | Loved, simple 3D | Dated UI, no filtering, no analytics, no time controls |
| N2YO | Functional pass predictions | Ad-heavy, ugly UI, weak 3D |
| Heavens-Above | Best for ground observers | Almost no 3D, dense UI |
| satvis (open source) | Solid pass prediction, PWA-capable | Thesis-project aesthetic, niche features |
| AstriaGraph | Research-grade accuracy | Technical, hard to navigate, not for general audience |

Common gaps across the market:

- Poor or nonexistent mobile experience
- No storytelling — users land on the site and don't know what to do
- No shareable URLs (you can't link someone to a specific moment)
- Weak search — usually only by name or NORAD ID
- No density visualization (where is space actually crowded?)
- No historical playback (rewind to launch day, see deployment)
- Limited conjunction analysis for non-paying users
- Closed APIs — no developer access tier

---

## 3. Feature tiers

### Must-haves (table stakes)

- **3D Earth** with day/night terminator, city lights on the dark side, atmospheric limb glow
- **Time controller** — play, pause, scrub forward/back, speed multipliers (1x to 1000x), snap-to-now button
- **Search & filter panel** — by name, NORAD ID, country, owner, type (active/debris/rocket body), orbital regime
- **Click-to-select** with full info panel: name, NORAD ID, launch date, country, owner, mass, altitude, speed, period, inclination
- **Camera follow mode** — smooth orbital camera tracking the selected satellite while everything else moves naturally
- **Orbit path visualization** — one full orbit drawn as a line for the selected satellite
- **Ground track** — the path traced over Earth's surface
- **Performance at scale** — 15,000+ objects at 60fps via GPU instancing

### Good-to-haves (competitive)

- **Pass predictions** for the user's location — when is the ISS overhead?
- **Constellation grouping** — toggle Starlink, OneWeb, GPS, Galileo, BeiDou, Iridium as units
- **Coverage cones / footprints** — show what each satellite can see
- **Conjunction analysis** — flag close approaches between objects
- **Orbital regime shells** — translucent bands for LEO, MEO, GEO
- **Ground station network overlay** — show tracking stations and active links
- **Historical playback** — rewind to any date, watch constellations deploy
- **Re-entry predictions** for decaying objects
- **Compare mode** — pin 2-3 satellites with overlaid orbits and stats
- **Shareable URLs** — encode camera, time, and selection into the URL
- **Notification system** — alert me when a visible pass is upcoming

### Differentiation (what nobody does well)

- **Storytelling / tour mode** — guided narratives ("watch Starlink fill the sky 2019–2025", "trace the Iridium-Cosmos collision moment")
- **Density heatmap** — overlay showing orbital congestion by altitude shell
- **Time machine with historical markers** — Sputnik 1957, Apollo era, Iridium-Cosmos 2009, first Starlink 2019
- **Notable events overlay** — debris field from 2007 Chinese ASAT test, Skylab re-entry zone, ISS handovers
- **Mobile-first + AR mode** — point your phone at the sky and see what's overhead
- **Natural-language search** — "show me all Chinese imaging satellites in sun-sync orbit launched after 2020"
- **Public API** — free tier for developers, paid for higher volume
- **Community annotations** — Wikipedia-style notes on satellites and events
- **Beautiful aesthetic** — match LeoLabs visually, exceed it interactively

---

## 4. Tech stack

| Layer | Choice |
|---|---|
| Framework | React + TypeScript |
| Build tool | Vite |
| 3D engine | CesiumJS |
| Propagation | satellite.js |
| State | Zustand |
| Data source | CelesTrak GP API |
| Hosting | Cloudflare Pages |
| Heavy compute | Web Workers |

The rationale for each — alternatives considered, tradeoffs, what gets deferred — lives in [decisions.md](./decisions.md). That is the single source of truth for technical choices. This document focuses on scope, feature tiers, and the build roadmap.

---

## 5. End-to-end data flow

### Boot sequence (page open → fully populated globe)

1. **Static files load from CDN** (~500ms). DNS lookup, TLS handshake, Cloudflare edge serves HTML and JS bundles to the browser.
2. **JavaScript parses and executes** (~2000ms). Browser parses ~1MB of JS (React + Cesium + satellite.js + app code). On weak devices this is the slowest step.
3. **React mounts the UI shell** (~2500ms). Empty panels and controls visible. Loading skeletons appear where the globe will be.
4. **Cesium initializes the globe** (~2500–3500ms, parallel to step 5). WebGL context, Earth imagery, atmosphere shader, camera.
5. **TLE fetch from CelesTrak** (~2500–3000ms, parallel to step 4). 10MB JSON response with ~14,500 satellites and their orbital elements.
6. **satellite.js propagates initial positions** (~3500–3700ms). For each satellite, compute XYZ at current time. Output: Float32Array.
7. **GPU upload and first render** (~3700ms). Position array uploaded to GPU buffer. One instanced draw call paints all dots.
8. **Steady state** — render loop at 60fps. Clock advances, positions interpolate from cached Worker output, GPU composes frames.

Total time-to-interactive: ~3–4 seconds on desktop, ~7–10 seconds on mid-range mobile.

### Interaction flows

**Click on a satellite:**
- Mouse event fires
- Cesium pick API raycasts and returns satellite ID
- `setSelectedId(id)` updates Zustand store
- Info panel re-renders with details
- Orbit path component draws polyline for one full orbit
- Camera controller smoothly transitions to follow mode
- URL updates: `?sat=25544`

**Scrub time slider:**
- Slider `onChange` fires `setCurrentTime(t)`
- Cesium clock advances → Earth rotates
- Worker receives new timestamp, recomputes all positions
- Position array uploaded to GPU on next frame
- All satellites snap to their location at the new time

**Toggle a filter (e.g., hide debris):**
- `setFilter({ debris: false })`
- Visible array recomputed (filter on cached catalog)
- GPU instance buffer rebuilt with visible subset
- Stats panel updates count

No network round-trips on interactions after initial TLE fetch — all data is already in memory.

---

## 6. V1 build plan

### Goal

Ship a beautiful, performant 3D satellite tracker that beats most existing public tools on UX in 6–8 weeks of focused work.

### V1 scope (cut everything else)

1. 3D globe with day/night terminator
2. ~15,000 active satellite rendering at 60fps
3. Time controller with play/pause/scrub
4. Click-to-select with info panel and camera follow
5. Orbit path + ground track for selected satellite
6. Constellation filter toggles (Starlink, GPS, OneWeb)
7. Pass predictions for the user's location
8. Shareable URLs
9. Mobile responsive (good experience, AR can wait for v2)

### Build phases

**Phase 1 — Foundation (week 1)**

- Bootstrap project (Vite + React + TypeScript)
- Get CesiumJS rendering a globe with day/night
- Time controller component scaffold
- Camera controls

**Phase 2 — Data pipeline (week 2)**

- Fetch TLE from CelesTrak
- Parse and normalize records
- Cache in IndexedDB
- Wire satellite.js for propagation

**Phase 3 — Rendering at scale (week 3)**

- Render all satellites as instanced points
- Update positions per frame
- Color by category
- Profile and optimize for 60fps

**Phase 4 — Interaction (week 4)**

- Click selection with enlarged hitbox
- Info panel UI
- Camera follow with smooth transition
- Orbit path + ground track drawing

**Phase 5 — Filtering & UI (week 5)**

- Search bar with fuzzy match
- Constellation toggles
- Orbit-regime / country / type filters
- Stats panel

**Phase 6 — Pass predictions & sharing (week 6)**

- Geolocation
- Pass prediction algorithm
- "Visible passes tonight" panel
- URL state encoding and deep linking

**Phase 7 — Mobile & polish (week 7)**

- Responsive layout
- Touch gestures
- Loading states, error handling
- Lighthouse audit

**Phase 8 — Launch (week 8)**

- Deploy to Cloudflare Pages
- Privacy-friendly analytics (Plausible/Umami)
- Launch post
- Submit to HN, /r/space, /r/spaceporn

### Key technical challenges

- **15k objects at 60fps**: GPU instancing. Pre-compute position windows in Web Workers and interpolate on the main thread.
- **Tiny moving points are hard to click**: enlarge hitboxes (logical area >> visual size); snap selection to nearest within N pixels.
- **Mobile WebGL is weaker**: detect device capabilities; render fewer points and lower-res Earth on mobile.
- **TLE update vs rate limits**: cache aggressively. CelesTrak updates every few hours; 6-hour refetches are plenty.
- **Time scrubbing with 15k SGP4 calls**: debounce, batch, or pre-compute position cache for a time window.

### Success metrics for v1

- Lighthouse performance >90 desktop, >75 mobile
- 60fps with 15,000 objects on a 2022-era laptop
- 30fps with 5,000 objects on a mid-range phone
- Page load to first interaction <3 seconds
- Time to fully populated globe <5 seconds

---

## 7. Roadmap beyond v1

**V2 (months 2–3) — Analytics**

- Conjunction analysis with miss-distance flagging
- Historical playback (requires Space-Track integration + backend)
- Density heatmap by altitude shell
- Compare mode

**V3 (months 3–6) — Differentiation**

- Storytelling tours
- Natural-language search (LLM-powered)
- Mobile AR mode
- Public API with free tier
- Community annotations

**V4 (months 6+) — Platform**

- Real-time ground station link visualization
- Coverage analysis tools
- Mission planning utilities
- Educational mode with guided lessons

---

## 8. Open questions to resolve early

- Monetization: freemium API is the cleanest path if needed. Sponsorship/grants (ESA, Astra Carta) fit a free-only model.
- Catalog gap: you can only show what's in CelesTrak/Space-Track. Be transparent about this.
- Branding voice: hobbyist tool, professional product, or educational platform? Aesthetic follows.
- Cesium ion costs: free up to 5GB egress/month. Plan to self-host imagery tiles once you scale.

---

## 9. Inspirational references

- [LeoLabs Visualization](https://platform.leolabs.space/visualization) — visual benchmark
- [Stuff in Space](https://stuffin.space) — interaction model
- [satvis](https://github.com/Flowm/satvis) — open-source fork-friendly starting point
- [CelesTrak GP data formats](https://celestrak.org/NORAD/documentation/gp-data-formats.php) — data spec
- [satellite.js](https://github.com/shashwatak/satellite-js) — propagation library
- [CesiumJS Sandcastle](https://sandcastle.cesium.com) — code examples for the globe
- [Cloudflare Pages docs](https://developers.cloudflare.com/pages/) — hosting
