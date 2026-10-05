import type { ReactNode } from 'react';
import { AlertOctagon, AlertTriangle } from 'lucide-react';

/** Ratio against a limit: fill carries severity, track is a lighter step of the same ramp. Icon + label, never colour alone. */
export function Meter({ label, sub, value, max, format, warnAt = 0.75, critAt = 0.9 }: { label: ReactNode; sub?: ReactNode; value: number; max: number; format?: (v: number, m: number) => ReactNode; warnAt?: number; critAt?: number }) {
  const ratio = max > 0 ? Math.min(1, value / max) : 0;
  const severity = ratio >= critAt ? 'critical' : ratio >= warnAt ? 'warning' : '';
  return (
    <div className={`meter ${severity}`} role="meter" aria-valuemin={0} aria-valuemax={max} aria-valuenow={value} aria-label={typeof label === 'string' ? label : undefined}>
      <div className="meter-label">
        {label}
        {sub && <small>{sub}</small>}
      </div>
      <div className="track" aria-hidden="true">
        <div className="fill" style={{ width: `${ratio * 100}%` }} />
      </div>
      <div className="meter-value">
        {severity === 'critical' && <AlertOctagon size={13} style={{ color: 'var(--status-critical)' }} aria-label="critical" />}
        {severity === 'warning' && <AlertTriangle size={13} style={{ color: 'var(--status-warning)' }} aria-label="warning" />}
        {format ? format(value, max) : `${value} / ${max}`}
      </div>
    </div>
  );
}
