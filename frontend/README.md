# Orbit — satellite tracker

A real-time 3D visualization of objects in Earth orbit. Built with React, TypeScript, Vite, CesiumJS, and satellite.js.

## Quick start

1. Install dependencies:

   ```
   npm install
   ```

2. Get a free Cesium ion access token at https://ion.cesium.com/signup, then copy `.env.example` to `.env` and fill it in:

   ```
   cp .env.example .env
   ```

3. Run the dev server:

   ```
   npm run dev
   ```

The app will be at http://localhost:5173.

## Build for production

```
npm run build
npm run preview
```

Deploy the `dist/` folder to any static host. Cloudflare Pages is recommended for the unlimited-bandwidth free tier.

## Project structure

```
satellite-tracker/
├── index.html              Entry HTML
├── vite.config.ts          Vite + Cesium plugin config
├── tsconfig.json           TypeScript config (strict mode)
├── src/
│   ├── main.tsx            React entry point
│   ├── App.tsx             Root component, layout composition
│   ├── App.css             App-level styles for panels
│   ├── index.css           Global resets and base styles
│   ├── components/
│   │   ├── Globe.tsx       CesiumJS viewer wrapper
│   │   ├── TimeController.tsx
│   │   ├── InfoPanel.tsx
│   │   ├── StatsOverlay.tsx
│   │   └── FilterPanel.tsx
│   ├── store/
│   │   └── useStore.ts     Zustand global state
│   ├── lib/
│   │   ├── celestrak.ts    TLE data fetching
│   │   └── propagator.ts   satellite.js wrapper
│   └── types/
│       └── satellite.ts    Shared TypeScript types
```

## What's in this scaffold

- Working "hello globe" — a fully initialized CesiumJS viewer with day/night lighting
- App shell with all UI panels in place but mostly empty
- Zustand store wired up with time, selection, and filter state
- CelesTrak fetcher and satellite.js propagator stubbed and ready to use

## What's not in this scaffold (next steps)

- Phase 2: wire the CelesTrak fetcher to populate the store, render satellite positions on the globe
- Phase 3: GPU instancing for performance at scale, Web Worker for off-thread propagation
- Phase 4: click selection, info panel data binding, camera follow
- Phase 5: search bar logic, country/type filters
- Phase 6: pass predictions, URL state encoding

See the product plan for the full roadmap.

## Data sources

- TLE data: [CelesTrak](https://celestrak.org/) — free, no auth, CORS-enabled
- Earth imagery: [Cesium ion](https://ion.cesium.com/) — free up to 5GB egress per month
