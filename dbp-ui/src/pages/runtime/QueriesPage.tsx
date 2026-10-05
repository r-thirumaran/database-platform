import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { useApplications, useDatabases, useTopQueries } from '../../api/hooks';
import type { QueryStat, TopQueriesBy, Window } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card } from '../../components/Card';
import { QueryBoundary, EmptyState } from '../../components/States';
import { DataTable } from '../../components/DataTable';
import { Badge } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { compact, formatMs, formatNumber } from '../../lib/format';
import { links } from '../../lib/links';
import { CopyButton } from '../../components/CopyId';

const OP_TONE: Record<string, 'blue' | 'orange' | 'pink' | 'red' | 'violet' | ''> = { SELECT: 'blue', INSERT: 'orange', UPDATE: 'orange', DELETE: 'red', MERGE: 'orange', CALL: 'pink', DDL: 'violet', TXN: '', OTHER: '' };

export function QueriesPage() {
  const [sp, setSp] = useSearchParams();
  const by = (sp.get('by') as TopQueriesBy) || 'count';
  const window = (sp.get('window') as Window) || '24h';
  const databaseId = sp.get('databaseId') ?? '';
  const applicationId = sp.get('applicationId') ?? '';
  const set = (k: string, v: string) => { const n = new URLSearchParams(sp); if (v) n.set(k, v); else n.delete(k); setSp(n, { replace: true }); };
  const q = useTopQueries({ by, window, databaseId: databaseId || undefined, applicationId: applicationId || undefined, limit: 100 });
  const dbs = useDatabases();
  const apps = useApplications();
  const [open, setOpen] = useState<string | null>(null);
  return (
    <>
      <PageHeader title="Queries" subtitle="Normalised statements aggregated by hash, with volume, latency percentiles and errors." />
      <div className="filters">
        <div className="field"><span className="label">Window</span><div className="segmented" role="group" aria-label="Window">{(['1h', '24h', '7d'] as Window[]).map((w) => <button key={w} type="button" aria-pressed={window === w} onClick={() => set('window', w)}>{w}</button>)}</div></div>
        <div className="field"><span className="label">Rank by</span><div className="segmented" role="group" aria-label="Rank by">{(['count', 'duration', 'rows'] as TopQueriesBy[]).map((b) => <button key={b} type="button" aria-pressed={by === b} onClick={() => set('by', b)}>{b}</button>)}</div></div>
        <div className="field"><label htmlFor="q-db">Database</label><select id="q-db" className="input" value={databaseId} onChange={(e) => set('databaseId', e.target.value)}><option value="">all</option>{dbs.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></div>
        <div className="field"><label htmlFor="q-app">Application</label><select id="q-app" className="input" value={applicationId} onChange={(e) => set('applicationId', e.target.value)}><option value="">all</option>{apps.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></div>
      </div>
      <Card tight>
        <QueryBoundary query={q} empty={<EmptyState title="No queries in this window" hint="The gateway reports every statement; the proxy path only yields queries when the collector correlates sessions." />}>
          {(rows) => (
            <div className={q.isFetching ? 'chart-wrap refetching' : ''}>
              <DataTable rows={rows} rowKey={(r) => `${r.sqlHash}-${r.applicationId}-${r.databaseId}`} onRowClick={(r) => setOpen(open === r.sqlHash ? null : r.sqlHash)} columns={[
                { key: 'x', header: '', width: 24, render: (r) => (open === r.sqlHash ? <ChevronDown size={14} /> : <ChevronRight size={14} />) },
                { key: 'sql', header: 'Statement', render: (r) => <QueryCell r={r} expanded={open === r.sqlHash} /> },
                { key: 'op', header: 'Op', sort: (r) => r.operation, render: (r) => <Badge tone={OP_TONE[r.operation] ?? ''}>{r.operation}</Badge> },
                { key: 'app', header: 'Application', sort: (r) => r.applicationName ?? '', render: (r) => (r.applicationId ? <Link to={links.application(r.applicationId)}>{r.applicationName}</Link> : <span className="muted">unknown</span>) },
                { key: 'count', header: 'Count', align: 'right', sort: (r) => r.count, render: (r) => compact(r.count) },
                { key: 'avg', header: 'Avg', align: 'right', sort: (r) => r.avgDurationMs, render: (r) => formatMs(r.avgDurationMs) },
                { key: 'p95', header: 'p95', align: 'right', sort: (r) => r.p95DurationMs, render: (r) => formatMs(r.p95DurationMs) },
                { key: 'max', header: 'Max', align: 'right', sort: (r) => r.maxDurationMs, render: (r) => formatMs(r.maxDurationMs) },
                { key: 'rows', header: 'Rows', align: 'right', sort: (r) => r.rows, render: (r) => compact(r.rows) },
                { key: 'err', header: 'Errors', align: 'right', sort: (r) => r.errors, render: (r) => (r.errors ? <span style={{ color: 'var(--danger)', fontWeight: 600 }}>{formatNumber(r.errors)}</span> : <span className="muted">0</span>) },
                { key: 'last', header: 'Last seen', sort: (r) => r.lastSeenAt, render: (r) => <RelativeTime value={r.lastSeenAt} /> },
              ]} />
            </div>
          )}
        </QueryBoundary>
      </Card>
    </>
  );
}

function QueryCell({ r, expanded }: { r: QueryStat; expanded: boolean }) {
  return (
    <div style={{ maxWidth: expanded ? undefined : 480 }}>
      <div className={`sql ${expanded ? '' : 'clamp'}`} title={expanded ? undefined : r.sqlNormalized}>{r.sqlNormalized}</div>
      <div className="row" style={{ gap: 6, marginTop: 4 }}>
        {r.tables.map((t) => <Link key={t.id} to={links.table(t.id)} className="badge outline">{t.schema}.{t.name}</Link>)}
        {expanded && <><code className="muted small">{r.sqlHash}</code><CopyButton value={r.sqlNormalized} label="Copy SQL" className="btn sm ghost" /></>}
      </div>
    </div>
  );
}
