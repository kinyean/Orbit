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

import createClient from 'openapi-fetch';
import type { paths } from './schema';

const BASE_URL =
  import.meta.env.VITE_BACKEND_URL ?? 'http://localhost:8081';

export const api = createClient<paths>({ baseUrl: BASE_URL });
