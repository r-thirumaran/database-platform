import { useEffect, type ReactNode } from 'react';
import { setCrumb } from '../lib/crumb';

/** Page title block; also publishes the title for the breadcrumb trail and the document title. */
export function PageHeader({ title, crumb, subtitle, badges, actions }: { title: ReactNode; crumb?: string; subtitle?: ReactNode; badges?: ReactNode; actions?: ReactNode }) {
  useEffect(() => {
    const t = crumb ?? (typeof title === 'string' ? title : undefined);
    setCrumb(t);
    document.title = t ? `${t} · Database Access Platform` : 'Database Access Platform';
    return () => setCrumb(undefined);
  }, [title, crumb]);
  return (
    <div className="page-header">
      <div className="title">
        <h1>
          {title}
          {badges && <span className="badge-row">{badges}</span>}
        </h1>
        {subtitle && <div className="subtitle">{subtitle}</div>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}
