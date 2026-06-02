// Streaming contract version the client expects. Must match the backend's
// StreamContract.VERSION (docs/streaming-contract.md). The client refuses a
// mismatched stream (R12); the status chip also surfaces the backend's
// reported version from /health.
export const STREAM_CONTRACT_VERSION = '1';
