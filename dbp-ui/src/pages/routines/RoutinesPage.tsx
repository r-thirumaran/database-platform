import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Search } from 'lucide-react';
import { useDatabases, useRoutines, useTeams } from '../../api/hooks';
import { PageHeader } from '../../components/PageHeader';
import { Card } from '../../components/Card';
import { QueryBoundary, EmptyState } from '../../components/States';
import { DataTable } from '../../components/DataTable';
import { Badge, EngineBadge, RoutineKindBadge } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { links } from '../../lib/links';

const KINDS = ['PROCEDURE', 'FUNCTION', 'PACKAGE', 'PACKAGE_BODY', 'TRIGGER', 'VIEW'];

export function RoutinesPage() {
  const [sp, setSp] = useSearchParams();
  const databaseId = sp.get('databaseId') ?? '';
  const kind = sp.get('kind') ?? '';
  const [q, setQ] = useState('');
  const routines = useRoutines({ databaseId: databaseId || undefined, kind: kind || undefined });
  const dbs = useDatabases();
  const teams = useTeams();
  const rows = useMemo(() => (routines.data ?? []).filter((r) => !q || `${r.schema}.${r.name}`.toLowerCase().includes(q.toLowerCase())), [routines.data, q]);
  const set = (k: string, v: string) => { const n = new URLSearchParams(sp); if (v) n.set(k, v); else n.delete(k); setSp(n, { replace: true }); };
  return (
    <>
      <PageHeader title="Routines" subtitle="Procedures, functions, packages, triggers and views crawled from the data dictionary." />
      <div className="filters">
        <div className="field grow"><label htmlFor="rt-q">Search</label><div className="search"><Search /><input id="rt-q" className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="SCHEMA.NAME" /></div></div>
        <div className="field"><label htmlFor="rt-db">Database</label><select id="rt-db" className="input" value={databaseId} onChange={(e) => set('databaseId', e.target.value)}><option value="">all</option>{dbs.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></div>
        <div className="field"><label htmlFor="rt-kind">Kind</label><select id="rt-kind" className="input" value={kind} onChange={(e) => set('kind', e.target.value)}><option value="">all</option>{KINDS.map((k) => <option key={k}>{k}</option>)}</select></div>
      </div>
      <Card tight>
        <QueryBoundary query={routines} empty={<EmptyState title="No routines" hint="Run the dictionary collector on a database." />}>
          {() => (
            <DataTable rows={rows} rowKey={(r) => r.id} rowLink={(r) => links.routine(r.id)} empty={<EmptyState inline title="No routine matches" />} columns={[
              { key: 'name', header: 'Routine', sort: (r) => `${r.schema}.${r.name}`, render: (r) => <strong><Link to={links.routine(r.id)}>{r.schema}.{r.name}</Link></strong> },
              { key: 'kind', header: 'Kind', sort: (r) => r.kind, render: (r) => <RoutineKindBadge kind={r.kind} /> },
              { key: 'db', header: 'Database', render: (r) => { const d = dbs.data?.find((x) => x.id === r.databaseId); return d ? <span className="row" style={{ gap: 6 }}><Link to={links.database(d.id)}>{d.name}</Link><EngineBadge engine={d.engine} /></span> : r.databaseId; } },
              { key: 'trigger', header: 'Trigger', render: (r) => (r.triggerTableId ? <span className="small"><Link to={links.table(r.triggerTableId)}>table</Link> · {r.triggerEvent}</span> : <span className="muted">—</span>) },
              { key: 'owner', header: 'Owner', render: (r) => { const t = teams.data?.find((x) => x.id === r.ownerTeamId); return t ? <Link to={links.team(t.id)}>{t.name}</Link> : <span className="muted">unowned</span>; } },
              { key: 'status', header: 'Status', render: (r) => <Badge tone={r.status === 'VALID' ? 'green' : 'red'}>{r.status}</Badge> },
              { key: 'seen', header: 'Last seen', sort: (r) => r.lastSeenAt, render: (r) => <RelativeTime value={r.lastSeenAt} staleDays={30} /> },
            ]} />
          )}
        </QueryBoundary>
      </Card>
    </>
  );
}
