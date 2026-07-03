// Top-level auth gate (Phase 10, US-AUTH-02/03).
//
//   - stub mode: renders children directly — no provider, no login. Local dev
//     and the backend's stub filter keep working unchanged.
//   - oidc mode: wraps the app in react-oidc-context's <AuthProvider>, redirects
//     to Keycloak when unauthenticated, mirrors the access token into token.ts
//     (for the REST middleware + WS clients), and shows a small sign-in / error
//     screen while the flow completes.

import { useEffect, type ReactNode } from 'react';
import { AuthProvider, useAuth } from 'react-oidc-context';
import { authMode, oidcConfig } from './config';
import { setAccessToken } from './token';

export default function AuthGate({ children }: { children: ReactNode }) {
  if (authMode === 'stub') return <>{children}</>;
  return (
    <AuthProvider {...oidcConfig}>
      <OidcGate>{children}</OidcGate>
    </AuthProvider>
  );
}

function OidcGate({ children }: { children: ReactNode }) {
  const auth = useAuth();

  // Keep the imperative token holder in step with the OIDC user (initial login
  // and every silent renew). This MUST run during render, not in a useEffect:
  // React fires effects child-first, so a useEffect here would run AFTER a
  // descendant's mount effect (e.g. ScenarioPanel's loadScenarios) — the first
  // /api call would then race an unset token and 401. Writing to the module-level
  // holder during render is idempotent and guarantees the token is in place
  // before any child effect fires (children only render when authenticated).
  setAccessToken(auth.isAuthenticated ? auth.user?.access_token ?? null : null);

  // Auto-redirect to the IdP once, when we're settled and unauthenticated.
  useEffect(() => {
    if (!auth.isLoading && !auth.isAuthenticated && !auth.activeNavigator && !auth.error) {
      void auth.signinRedirect();
    }
  }, [auth.isLoading, auth.isAuthenticated, auth.activeNavigator, auth.error, auth]);

  if (auth.error) {
    return (
      <AuthScreen>
        <p className="auth-error">Sign-in failed: {auth.error.message}</p>
        <button onClick={() => void auth.signinRedirect()}>Try again</button>
      </AuthScreen>
    );
  }
  if (auth.isLoading || !auth.isAuthenticated) {
    return (
      <AuthScreen>
        <p>Signing in…</p>
      </AuthScreen>
    );
  }
  return <>{children}</>;
}

function AuthScreen({ children }: { children: ReactNode }) {
  return (
    <div className="auth-screen">
      <div className="auth-brand">Orbit</div>
      {children}
    </div>
  );
}
