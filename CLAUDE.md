# Satellite tracker — Claude context

@./docs/satellite-viz-product-plan.md
@./docs/decisions.md

## Current phase
Phase 1 complete. Working on phase 2: wiring CelesTrak data into the globe.

## Architecture rules
- All client-side, no backend
- State in Zustand, subscribe per-slice
- TLE direct from CelesTrak, cache in IndexedDB
- Heavy propagation goes in a Web Worker (phase 3+)
- TypeScript strict mode is on — fix errors, don't disable

## Build commands
- `npm run dev` — Vite dev server
- `npm run type-check` — TS without emit

## What's next
- `useTLEData` hook: fetch on mount, store in Zustand
- Render satellites as Cesium PointPrimitiveCollection
- Click-to-select wired to info panel

## Conventions
- Per-slice Zustand subscriptions (not whole store)
- Ask before adding dependencies
- Commit after each working feature