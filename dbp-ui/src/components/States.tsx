import type { ReactNode } from 'react';
import { AlertTriangle, Inbox, RefreshCw } from 'lucide-react';
import type { UseQueryResult } from '@tanstack/react-query';
import { errorMessage } from '../api/client';

export function Loading({ label = 'Loading…', rows = 3, inline = false }: { label?: string; rows?: number; inline?: boolean }) {
  return (
    <div className={`state ${inline ? 'inline' : ''}`} role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <span className="sr-only">{label}</span>
      <div style={{ width: '100%', maxWidth: 520, display: 'flex', flexDirection: 'column', gap: 8 }} aria-hidden="true">
        {Array.from({ length: rows }, (_, i) => (
          <div key={i} className="skeleton" style={{ width: `${90 - i * 12}%` }} />
        ))}
      </div>
    </div>
  );
}

export function ErrorState({ error, retry, inline = false }: { error: unknown; retry?: () => void; inline?: boolean }) {
  return (
    <div className={`state error ${inline ? 'inline' : ''}`} role="alert">
      <AlertTriangle />
      <div className="title">Something went wrong</div>
      <div className="small">{errorMessage(error)}</div>
      {retry && (
        <button className="btn sm" onClick={retry}>
          <RefreshCw /> Retry
        </button>
      )}
    </div>
  );
}

export function EmptyState({ title = 'Nothing here yet', hint, action, inline = false }: { title?: string; hint?: ReactNode; action?: ReactNode; inline?: boolean }) {
  return (
    <div className={`state ${inline ? 'inline' : ''}`}>
      <Inbox />
      <div className="title">{title}</div>
      {hint && <div className="small">{hint}</div>}
      {action}
    </div>
  );
}

/** Renders loading / error / empty / data for a React Query result. */
export function QueryBoundary<T>({
  query,
  children,
  isEmpty,
  empty,
  inline = true,
}: {
  query: UseQueryResult<T, Error>;
  children: (data: T) => ReactNode;
  isEmpty?: (data: T) => boolean;
  empty?: ReactNode;
  inline?: boolean;
}) {
  if (query.isPending) return <Loading inline={inline} />;
  if (query.isError) return <ErrorState error={query.error} retry={() => query.refetch()} inline={inline} />;
  const data = query.data as T;
  const emptyCheck = isEmpty ?? ((d: T) => Array.isArray(d) && d.length === 0);
  if (emptyCheck(data)) return <>{empty ?? <EmptyState inline={inline} />}</>;
  return <>{children(data)}</>;
}
