import type { ReactNode } from 'react';

let seq = 0;
export function Field({ label, help, children, full = false, id }: { label: ReactNode; help?: ReactNode; children: (id: string) => ReactNode; full?: boolean; id?: string }) {
  const fid = id ?? `f-${++seq}`;
  return (
    <div className={`field ${full ? 'full' : ''}`}>
      <label htmlFor={fid}>{label}</label>
      {children(fid)}
      {help && <div className="help">{help}</div>}
    </div>
  );
}

export function Toggle({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode; disabled?: boolean }) {
  return (
    <label className="switch">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} disabled={disabled} />
      <span className="track" aria-hidden="true" />
      <span>{label}</span>
    </label>
  );
}
