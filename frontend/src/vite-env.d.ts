/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CESIUM_ION_TOKEN: string;
  /** Backend base URL. Defaults to http://localhost:8081 if not set. */
  readonly VITE_BACKEND_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
