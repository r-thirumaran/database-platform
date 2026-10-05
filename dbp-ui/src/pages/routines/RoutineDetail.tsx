import { Link, useParams } from 'react-router-dom';
import { useDatabases, useRoutineSummary, useTeams } from '../../api/hooks';
import type { RoutineSummary } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card, KV } from '../../components/Card';
import { Loading, ErrorState, EmptyState } from '../../components/States';
import { Badge, Confidence, EngineBadge, KindBadge, RoutineKindBadge, SourceBadge } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { links } from '../../lib/links';
import { CopyId } from '../../components/CopyId';
import { DataTable } from '../../components/DataTable';
import { compact } from '../../lib/format';

export function RoutineDetail() {
  const { id = '' } = useParams();
  const s = useRoutineSummary(id);
  if (s.isPending) return <Loading />;
  if (s.isError) return <ErrorState error={s.error} retry={() => s.refetch()} />;
  return <RoutineView s={s.data} />;
}

function RoutineView({ s }: { s: RoutineSummary }) {
  const r = s.routine;
  const dbs = useDatabases();
  const teams = useTeams();
  const db = s.database ?? dbs.data?.find((d) => d.id === r.databaseId);
  const team = s.ownerTeam ?? teams.data?.find((t) => t.id === r.ownerTeamId);
  const fq = `${r.schema}.${r.name}`;
  const outgoing = s.dependencies.filter((d) => d.fromId === r.id);
  // The contract returns incoming dependencies as `referencedBy`; older servers only had `dependencies`.
  const incoming = s.referencedBy ?? s.dependencies.filter((d) => d.toId === r.id);
  const triggerTableLabel = s.triggerTable ? `${s.triggerTable.schema}.${s.triggerTable.name}` : 'table';
  const linkFor = (type: 'TABLE' | 'ROUTINE', id: string) => (type === 'TABLE' ? links.table(id) : links.routine(id));
  return (
    <>
      <PageHeader title={fq} crumb={fq} badges={<><RoutineKindBadge kind={r.kind} /><EngineBadge engine={db?.engine} /><Badge tone={r.status === 'VALID' ? 'green' : 'red'}>{r.status}</Badge></>}
        subtitle={<span className="row">{r.kind === 'TRIGGER' && r.triggerTableId ? <span>{r.triggerEvent} on <Link to={links.table(r.triggerTableId)}>{triggerTableLabel}</Link></span> : null}<CopyId value={r.id} /></span>}
        actions={<Link className="btn" to={links.graph(`routine:${r.id}`, 2)}>Open in graph</Link>} />
      <div className="grid cols-3">
        <Card title="Details">
          <KV items={[
            ['Database', db ? <Link to={links.database(db.id)}>{db.name}</Link> : r.databaseId],
            ['Schema', r.schema],
            ['Owner', team ? <Link to={links.team(team.id)}>{team.displayName}</Link> : <span className="muted">unowned</span>],
            ['Trigger', r.triggerTableId ? <span>{r.triggerEvent} · <Link to={links.table(r.triggerTableId)}>{triggerTableLabel}</Link></span> : null],
            ['Last DDL', <RelativeTime value={r.lastDdlAt} />],
            ['Last seen', <RelativeTime value={r.lastSeenAt} staleDays={30} />],
          ]} />
        </Card>
        <Card title="Callers" hint="applications observed calling it">
          {s.callers.length === 0 ? <EmptyState inline title="No callers observed" /> : (
            <div className="stack" style={{ gap: 6 }}>
              {s.callers.map((c, i) => (
                <div key={`${c.application.id}-${c.source ?? ''}-${i}`} className="row between">
                  <span className="row" style={{ gap: 6 }}><Link to={links.application(c.application.id)}>{c.application.name}</Link>{c.team && <span className="muted small">{c.team.name}</span>}</span>
                  <span className="row" style={{ gap: 6 }}><SourceBadge source={c.source} /><span className="muted small" title="calls observed">{compact(c.queryCount)}</span></span>
                </div>
              ))}
            </div>
          )}
        </Card>
        <Card title="Tables touched" hint="transitively through dependencies">
          {s.tables.length === 0 ? <EmptyState inline title="No table dependencies" /> : <div className="inline-list">{s.tables.map((t) => <Link key={t.id} to={links.table(t.id)} className="badge outline">{t.schema}.{t.name}</Link>)}</div>}
        </Card>
        <Card className="span-2" title="Dependencies" hint="what this routine references" tight>
          {outgoing.length === 0 ? <EmptyState inline title="No outgoing dependencies" /> : (
            <DataTable compact rows={outgoing} rowKey={(d) => d.id} columns={[
              { key: 'kind', header: 'Kind', render: (d) => <KindBadge kind={d.kind} /> },
              { key: 'to', header: 'Object', render: (d) => <Link to={linkFor(d.toType, d.toId)}>{d.toName ?? d.toId}</Link> },
              { key: 'type', header: 'Type', render: (d) => <Badge outline>{d.toType}</Badge> },
              { key: 'src', header: 'Source', render: (d) => <span className="row" style={{ gap: 6 }}><SourceBadge source={d.source} /><Confidence value={d.confidence} /></span> },
              { key: 'seen', header: 'Last seen', render: (d) => <RelativeTime value={d.lastSeenAt} /> },
            ]} />
          )}
        </Card>
        <Card title="Referenced by" hint="dependencies pointing here" tight>
          {incoming.length === 0 ? <EmptyState inline title="Nothing references this routine" /> : (
            <DataTable compact rows={incoming} rowKey={(d) => d.id} columns={[
              { key: 'from', header: 'Object', render: (d) => <Link to={linkFor(d.fromType, d.fromId)}>{d.fromName ?? d.fromId}</Link> },
              { key: 'kind', header: 'Kind', render: (d) => <KindBadge kind={d.kind} /> },
            ]} />
          )}
        </Card>
      </div>
    </>
  );
}
