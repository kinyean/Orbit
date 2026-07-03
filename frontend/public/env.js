// Runtime config placeholder (Phase 10). In dev this empty default is served as a
// real file (so index.html's <script src="/env.js"> doesn't 404 into the HTML
// fallback), and the app falls back to import.meta.env. In the prod container the
// entrypoint overwrites this file via envsubst with the deployment's real values.
window.__ORBIT_ENV__ = window.__ORBIT_ENV__ || {};
