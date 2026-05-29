// Typed REST client for the Orbit backend.
//
// Built on the OpenAPI types in ./schema.d.ts (regenerated via `npm run gen:api`
// against the running backend's /v3/api-docs). openapi-fetch is a ~1KB
// wrapper around fetch that uses those types to give end-to-end type safety.
//
// Why a generated client (vs hand-rolled fetch):
//   - Contract drift between backend and frontend becomes a compile error,
//     not a runtime surprise (R12 in docs/risks.md).
//   - Adding a new endpoint backend-side and `npm run gen:api` on the frontend
//     is the entire integration.
//
// Base URL is a relative `/api` prefix. The Vite dev server's same-origin
// proxy (see vite.config.ts) forwards /api/* to the backend service. The
// browser therefore only ever talks to the frontend's own origin — no CORS
// in dev, and only one port needs to be reachable from outside the host.
// In production the same /api prefix is served by whatever reverse proxy
// fronts the static build.

import createClient from 'openapi-fetch';
import type { paths } from './schema';

export const api = createClient<paths>({ baseUrl: '/api' });
