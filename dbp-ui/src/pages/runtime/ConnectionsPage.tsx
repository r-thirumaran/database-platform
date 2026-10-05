import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { RefreshCw, Search } from 'lucide-react';
import { useApplications, useLiveConnections } from '../../api/hooks';
import type { LiveConnection } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card } from '../../components/Card';
import { QueryBoundary, EmptyState } from '../../components/States';
import { DataTable } from '../../components/DataTable';
import { Badge, EngineBadge } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { formatDuration } from '../../lib/format';
import { links } from '../../lib/links';
import { StatTile } from '../../components/StatTile';

type GroupKey = 'application' | 'team' | 'datasource' | 'database' | 'source';

export function ConnectionsPage() {
  const live = useLiveConnections();
  const apps = useApplications();
  const [q, setQ] = useState('');
  const [source, setSource] = useState<'' | 'PROXY' | 'COLLECTOR'>('');
  const [app, setApp] = useState('');
  const [group, setGroup] = useState<GroupKey>('application');
  const rows = useMemo(() => (live.data ?? []).filter((c) =>
    (!source || c.source === source) && (!app || c.application === app || (app === '__unknown' && !c.application)) &&
    (!q || `${c.application ?? ''} ${c.clientAddr ?? ''} ${c.program ?? ''} ${c.machine ?? ''} ${c.dbUser ?? ''} ${c.osUser ?? ''} ${c.currentSql ?? ''}`.toLowerCase().includes(q.toLowerCase()))), [live.data, q, source, app]);
  const groups = useMemo(() => {
    const m = new Map<string, { key: string; total: number; active: number; proxy: number; collector: number }>();
    for (const c of rows) {
      const key = (group === 'source' ? c.source : c[group]) ?? 'unknown';
      const g = m.get(key) ?? { key, total: 0, active: 0, proxy: 0, collector: 0 };
      g.total++; if ((c.status ?? '').toUpperCase() === 'ACTIVE') g.active++; if (c.source === 'PROXY') g.proxy++; else g.collector++;
      m.set(key, g);
    }
    return [...m.values()].sort((a, b) => b.total - a.total);
  }, [rows, group]);
  const unknown = rows.filter((c) => !c.application).length;
  const appLink = (name: string | null) => { const a = apps.data?.find((x) => x.name === name); return a ? <Link to={links.application(a.id)}>{name}</Link> : name ? <span>{name}</span> : <Badge tone="amber">unknown</Badge>; };

  return (
    <>
      <PageHeader title="Connections" subtitle="Live connections: the proxy's snapshot of client sessions and the collector's view of database sessions."
        actions={<button className="btn ghost" onClick={() => live.refetch()} disabled={live.isFetching}><RefreshCw /> Refresh</button>} />
      <div className="kpi-row" style={{ marginBottom: 16 }}>
        <StatTile label="Connections" value={rows.length} raw sub={live.dataUpdatedAt ? <>as of <RelativeTime value={new Date(live.dataUpdatedAt).toISOString()} /></> : undefined} />
        <StatTile label="Active" value={rows.filter((c) => (c.status ?? '').toUpperCase() === 'ACTIVE').length} raw />
        <StatTile label="Through the proxy" value={rows.filter((c) => c.source === 'PROXY').length} raw />
        <StatTile label="Collector sessions" value={rows.filter((c) => c.source === 'COLLECTOR').length} raw />
        <StatTile label="Unattributed" value={unknown} raw alert={unknown > 0} sub="no application identity matched" />
      </div>
      <div className="filters">
        <div className="field grow"><label htmlFor="conn-q">Search</label><div className="search"><Search /><input id="conn-q" className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="address, program, machine, user, SQL…" /></div></div>
        <div className="field"><label htmlFor="conn-src">Source</label><select id="conn-src" className="input" value={source} onChange={(e) => setSource(e.target.value as typeof source)}><option value="">both</option><option value="PROXY">proxy</option><option value="COLLECTOR">collector</option></select></div>
        <div className="field"><label htmlFor="conn-app">Application</label><select id="conn-app" className="input" value={app} onChange={(e) => setApp(e.target.value)}><option value="">all</option><option value="__unknown">unknown only</option>{apps.data?.map((a) => <option key={a.id} value={a.name}>{a.name}</option>)}</select></div>
      </div>
      <div className="grid cols-3">
        <Card title="Grouped counts" tight actions={<select className="input sm" aria-label="Group by" value={group} onChange={(e) => setGroup(e.target.value as GroupKey)}>{(['application', 'team', 'datasource', 'database', 'source'] as GroupKey[]).map((g) => <option key={g} value={g}>{g}</option>)}</select>}>
          {groups.length === 0 ? <EmptyState inline title="Nothing to group" /> : (
            <DataTable compact rows={groups} rowKey={(g) => g.key} columns={[
              { key: 'k', header: group, render: (g) => g.key },
              { key: 'total', header: 'Total', align: 'right', sort: (g) => g.total, render: (g) => g.total },
              { key: 'active', header: 'Active', align: 'right', render: (g) => g.active },
              { key: 'proxy', header: 'Proxy', align: 'right', render: (g) => g.proxy },
              { key: 'coll', header: 'Collector', align: 'right', render: (g) => g.collector },
            ]} />
          )}
        </Card>
        <Card className="span-2" title="Live connections" hint={`${rows.length}`} tight>
          <QueryBoundary query={live} empty={<EmptyState title="No live connections" hint="Proxies report their snapshot with each heartbeat; collectors sample V$SESSION / pg_stat_activity." />}>
            {() => (
              <DataTable rows={rows} rowKey={(c) => rowKey(c)} compact empty={<EmptyState inline title="No connection matches" />} initialSort={{ key: 'opened', dir: 'desc' }} columns={[
                { key: 'src', header: 'Source', render: (c) => <Badge tone={c.source === 'PROXY' ? 'violet' : 'teal'}>{c.source.toLowerCase()}</Badge> },
                { key: 'app', header: 'Application', sort: (c) => c.application ?? '', render: (c) => <>{appLink(c.application)}<div className="muted small">{c.team}</div></> },
                { key: 'ds', header: 'Datasource / database', render: (c) => <span className="row" style={{ gap: 6 }}>{c.datasource ?? <span className="muted">—</span>}<span className="muted small">{c.database}</span><EngineBadge engine={c.engine} /></span> },
                { key: 'client', header: 'Client', render: (c) => <><code>{c.clientAddr}</code><div className="muted small">{[c.machine, c.program].filter(Boolean).join(' · ')}</div></> },
                { key: 'user', header: 'Users', render: (c) => <span className="small">{c.osUser ?? '—'} / <code>{c.dbUser ?? '—'}</code></span> },
                { key: 'status', header: 'Status', sort: (c) => c.status ?? '', render: (c) => <Badge tone={(c.status ?? '').toUpperCase() === 'ACTIVE' ? 'green' : ''}>{c.status ?? '—'}</Badge> },
                { key: 'opened', header: 'Opened', sort: (c) => c.openedAt ?? '', render: (c) => <><RelativeTime value={c.openedAt} /><div className="muted small">{formatDuration(c.durationSeconds)}</div></> },
                { key: 'sql', header: 'Current SQL', className: 'truncate', render: (c) => (c.currentSql ? <span className="sql" title={c.currentSql}>{c.sqlId && <span className="muted">[{c.sqlId}] </span>}{c.currentSql}</span> : <span className="muted">—</span>) },
              ]} />
            )}
          </QueryBoundary>
        </Card>
      </div>
    </>
  );
}

const rowKey = (c: LiveConnection) => `${c.source}-${c.clientAddr}-${c.openedAt}-${c.sqlId}-${c.application}-${c.dbUser}-${c.durationSeconds}`;
