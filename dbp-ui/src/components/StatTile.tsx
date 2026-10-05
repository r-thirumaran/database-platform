import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { compact } from '../lib/format';

/**
 * Stat tile (data-viz "is it even a chart?" → a number): label · value (auto-compact) · optional sub/delta.
 * Values use proportional figures; no series colour on text.
 */
export function StatTile({ label, value, sub, to, alert = false, raw = false, icon }: { label: ReactNode; value: number | ReactNode | null | undefined; sub?: ReactNode; to?: string; alert?: boolean; raw?: boolean; icon?: ReactNode }) {
  const text = typeof value === 'number' ? (raw ? value.toLocaleString() : compact(value)) : (value ?? '—');
  const body = (
    <>
      <div className="label">{icon}{label}</div>
      <div className="value" title={typeof value === 'number' ? value.toLocaleString() : undefined}>{text}</div>
      {sub && <div className="sub">{sub}</div>}
    </>
  );
  const cls = `stat-tile ${alert ? 'alert' : ''}`;
  return to ? (
    <Link to={to} className={cls} style={{ color: 'inherit', textDecoration: 'none' }}>
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  );
}
