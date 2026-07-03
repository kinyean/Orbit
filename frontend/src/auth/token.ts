// Bridges the React auth context to imperative (non-React) code — the
// openapi-fetch REST middleware and the WebSocket clients — which can't call
// useAuth(). AuthGate keeps `currentAccessToken` in sync with the OIDC user;
// these helpers read it. In stub mode the token stays null and everything
// behaves exactly as before (no Authorization header, no ?access_token=).

let currentAccessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  currentAccessToken = token;
}

export function getAccessToken(): string | null {
  return currentAccessToken;
}

/**
 * Append the current access token to a WebSocket URL as `?access_token=…`.
 * Browsers can't set an Authorization header on `new WebSocket()`, so the stream
 * handshakes carry the token in the query string (the backend's bearer-token
 * resolver reads it there in oidc mode). No-op when there is no token (stub mode).
 */
export function withAccessToken(url: string): string {
  if (!currentAccessToken) return url;
  const sep = url.includes('?') ? '&' : '?';
  return `${url}${sep}access_token=${encodeURIComponent(currentAccessToken)}`;
}
