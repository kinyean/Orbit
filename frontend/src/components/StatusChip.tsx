import { useEffect, useState } from 'react';
import { api } from '../api/client';

type Status = 'loading' | 'healthy' | 'degraded' | 'down';

interface HealthInfo {
  version?: string;
  buildTime?: string;
  contractVersion?: string;
  serverTime?: string;
}

/**
 * Status chip in the top bar. Calls the backend's GET /health on mount via
 * the generated OpenAPI client and reports overall stack health.
 *
 * Phase-1 use: proves the frontend↔backend pipeline works end-to-end.
 * In later phases this can grow into a richer system status panel.
 */
export default function StatusChip() {
  const [status, setStatus] = useState<Status>('loading');
  const [info, setInfo] = useState<HealthInfo>({});

  useEffect(() => {
    let cancelled = false;

    async function probe() {
      try {
        const { data, error } = await api.GET('/health');
        if (cancelled) return;
        if (error || !data) {
          setStatus('down');
          return;
        }
        setInfo({
          version: data.version,
          buildTime: data.buildTime,
          contractVersion: data.contractVersion,
          serverTime: data.serverTime,
        });
        setStatus(data.dbStatus === 'up' ? 'healthy' : 'degraded');
      } catch {
        if (!cancelled) setStatus('down');
      }
    }

    probe();
    // Re-probe every 30s so the chip catches backend restarts.
    const id = window.setInterval(probe, 30_000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, []);

  const label = labelFor(status);
  const title = info.version
    ? `backend ${info.version} · contract v${info.contractVersion} · build ${info.buildTime}`
    : 'no backend response yet';

  return (
    <div className="status-chip" data-status={status} title={title}>
      <span className="status-dot" />
      <span className="status-label">backend: {label}</span>
    </div>
  );
}

function labelFor(status: Status): string {
  switch (status) {
    case 'loading':
      return 'connecting…';
    case 'healthy':
      return 'healthy';
    case 'degraded':
      return 'db down';
    case 'down':
      return 'unreachable';
  }
}
