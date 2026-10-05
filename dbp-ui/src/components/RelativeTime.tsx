import { useEffect, useState } from 'react';
import { formatDateTime, isStale, relativeTime } from '../lib/format';

export function RelativeTime({ value, staleDays, className = '' }: { value: string | null | undefined; staleDays?: number; className?: string }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(t);
  }, []);
  if (!value) return <span className={`muted ${className}`}>never</span>;
  const stale = staleDays !== undefined && isStale(value, staleDays);
  return (
    <time dateTime={value} title={formatDateTime(value)} className={`${stale ? 'muted' : ''} ${className}`} style={stale ? { fontStyle: 'italic' } : undefined}>
      {relativeTime(value, now)}
      {stale ? ' (stale)' : ''}
    </time>
  );
}
