import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import cesium from 'vite-plugin-cesium';

// The frontend talks to the backend through a same-origin proxy:
//   browser → http://<frontend-host>:5174/api/*  → Vite dev server  → backend
//
// Only the frontend port needs to be reachable from outside; the backend
// stays on the docker network or on localhost. Side benefit: no CORS in dev
// because everything is one origin from the browser's POV.
//
// PROXY_TARGET picks the upstream URL:
//   - docker-compose sets it to http://backend:8080 (service name on the
//     docker network)
//   - local-only dev (frontend on host, backend in a published-port container)
//     falls back to http://localhost:8081
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxyTarget = env.PROXY_TARGET ?? 'http://localhost:8081';

  return {
    plugins: [react(), cesium()],
    server: {
      port: 5173,
      // Use polling for the dev file-watcher instead of inotify. On Linux,
      // Node's recursive watch creates one inotify INSTANCE per directory, and
      // big deps (cesium + three) push past the host's low
      // fs.inotify.max_user_instances (128 here) → the dev server crashes with
      // EMFILE. Polling only src (node_modules ignored) is cheap and avoids
      // inotify entirely. Dev-only; the production build has no watcher.
      watch: {
        usePolling: true,
        interval: 300,
        ignored: ['**/node_modules/**', '**/dist/**', '**/build/**'],
      },
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
          // Strip the /api prefix so backend routes stay un-prefixed
          // (the backend serves /health, not /api/health).
          rewrite: (path) => path.replace(/^\/api/, ''),
          ws: true, // proxy WebSocket upgrades too (Phase 2+ streaming)
        },
      },
    },
  };
});
