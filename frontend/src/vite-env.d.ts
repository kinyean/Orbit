/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CESIUM_ION_TOKEN: string;
  // Phase 10 auth (US-AUTH-02). VITE_AUTH_MODE: "stub" (default, no IdP) or
  // "oidc"; the issuer + client id are the Keycloak realm the SPA logs into.
  readonly VITE_AUTH_MODE?: string;
  readonly VITE_OIDC_ISSUER?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

// Runtime config injected by the container entrypoint into /env.js (Phase 10),
// so one built image is portable across deployments. Absent in dev → the app
// falls back to import.meta.env. See auth/config.ts + deploy/frontend.
interface OrbitRuntimeEnv {
  AUTH_MODE?: string;
  OIDC_ISSUER?: string;
  OIDC_CLIENT_ID?: string;
}
interface Window {
  __ORBIT_ENV__?: OrbitRuntimeEnv;
}
