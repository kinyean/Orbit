// Auth configuration (Phase 10, US-AUTH-02). Two modes, chosen by the
// VITE_AUTH_MODE build env:
//   - "stub"  (default): no IdP; the backend injects a dev user. Local dev works
//     with zero auth infrastructure — matches the backend's orbit.auth.mode=stub.
//   - "oidc":  the SPA runs the OIDC auth-code + PKCE flow against a Keycloak
//     realm and sends bearer tokens; the backend validates them.
//
// The issuer + client id come from the same realm the backend's OIDC_ISSUER_URI
// points at. Keep these two sides in step.

import type { AuthProviderProps } from 'react-oidc-context';
import { WebStorageStateStore } from 'oidc-client-ts';

export type AuthMode = 'stub' | 'oidc';

// Runtime config (window.__ORBIT_ENV__ from /env.js, written by the container
// entrypoint) wins over build-time import.meta.env, so one built image is
// portable across deployments. Both absent → stub (local dev default).
const runtime = window.__ORBIT_ENV__ ?? {};
const rawMode = runtime.AUTH_MODE ?? import.meta.env.VITE_AUTH_MODE;
const issuer = runtime.OIDC_ISSUER ?? import.meta.env.VITE_OIDC_ISSUER ?? '';
const clientId = runtime.OIDC_CLIENT_ID ?? import.meta.env.VITE_OIDC_CLIENT_ID ?? 'orbit-frontend';

export const authMode: AuthMode = rawMode === 'oidc' ? 'oidc' : 'stub';

/**
 * react-oidc-context / oidc-client-ts settings for the auth-code + PKCE flow.
 * `redirect_uri` is the app origin (Keycloak must list it as a valid redirect);
 * tokens live in localStorage so a reload keeps the session; silent renew keeps
 * the access token fresh for the long-lived WebSocket streams.
 */
export const oidcConfig: AuthProviderProps = {
  authority: issuer,
  client_id: clientId,
  redirect_uri: window.location.origin + '/',
  post_logout_redirect_uri: window.location.origin + '/',
  scope: 'openid profile email',
  response_type: 'code',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.localStorage }),
  // Strip ?code=&state= from the URL after the callback so a reload doesn't
  // replay the exchange.
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};
