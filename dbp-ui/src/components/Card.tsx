import type { ReactNode } from 'react';

export function Card({ title, hint, actions, children, tight = false, footer, className = '', id }: {
  title?: ReactNode; hint?: ReactNode; actions?: ReactNode; children: ReactNode; tight?: boolean; footer?: ReactNode; className?: string; id?: string;
}) {
  return (
    <section className={`card ${className}`} id={id} aria-label={typeof title === 'string' ? title : undefined}>
      {(title || actions) && (
        <header className="card-header">
          <h2>
            {title}
            {hint && <span className="hint">{hint}</span>}
          </h2>
          {actions && <div className="row">{actions}</div>}
        </header>
      )}
      <div className={`card-body ${tight ? 'tight' : ''}`}>{children}</div>
      {footer && <footer className="card-footer">{footer}</footer>}
    </section>
  );
}

export function KV({ items }: { items: Array<[ReactNode, ReactNode] | null | false | undefined> }) {
  return (
    <dl className="kv">
      {items.filter((x): x is [ReactNode, ReactNode] => !!x).map(([k, v], i) => (
        <div key={i} style={{ display: 'contents' }}>
          <dt>{k}</dt>
          <dd>{v ?? <span className="muted">—</span>}</dd>
        </div>
      ))}
    </dl>
  );
}
