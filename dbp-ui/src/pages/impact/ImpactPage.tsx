import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ExternalLink } from 'lucide-react';
import { useColumnImpact, useColumns, useTableImpact } from '../../api/hooks';
import type { Graph, GraphEdge, GraphNode, Impact } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card, KV } from '../../components/Card';
import { Loading, ErrorState, EmptyState } from '../../components/States';
import { Badge, KindBadge, SourceBadge } from '../../components/Badge';
import { EntityPicker, type EntityOption } from '../../components/EntityPicker';
import { GraphCanvas } from '../../charts/GraphCanvas';
import { RiskRing } from './RiskRing';
import { RelativeTime } from '../../components/RelativeTime';
import { DataTable } from '../../components/DataTable';
import { StatTile } from '../../components/StatTile';
import { links } from '../../lib/links';
import { compact, formatMs } from '../../lib/format';
import { Field } from '../../components/Field';

export function ImpactPage() {
  const [sp, setSp] = useSearchParams();
  const tableId = sp.get('table') ?? '';
  const columnId = sp.get('column') ?? '';
  const [picked, setPicked] = useState<EntityOption | null>(null);
  const tableImpact = useTableImpact(columnId ? '' : tableId);
  const columnImpact = useColumnImpact(columnId);
  const impact = columnId ? columnImpact : tableImpact;
  const columns = useColumns(tableId);
  const set = (k: 'table' | 'column', v?: string) => { const n = new URLSearchParams(); if (k === 'column' && v) { n.set('column', v); if (tableId) n.set('table', tableId); } else if (v) n.set('table', v); setSp(n, { replace: true }); };

  return (
    <>
      <PageHeader title="Impact analysis" subtitle="What breaks if this table or column changes: owners, producers, direct and indirect consumers, teams, database-side logic and a risk score." />
      <div className="filters" style={{ alignItems: 'flex-end' }}>
        <div className="field grow" style={{ minWidth: 320 }}>
          <label htmlFor="impact-root">Table</label>
          <EntityPicker id="impact-root" types={['table']} placeholder="Search a table…" value={picked ?? (tableId && impact.data ? { type: 'table', id: tableId, label: impact.data.target.label.split('.').slice(0, 2).join('.') } : null)} onChange={(o) => { setPicked(o); set('table', o.id); }} autoFocus={!tableId} />
        </div>
        {tableId && (
          <Field label="Column (optional)">{(id) => (
            <select id={id} className="input" value={columnId} onChange={(e) => set('column', e.target.value || undefined)} style={{ minWidth: 200 }}>
              <option value="">whole table</option>
              {columns.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}</Field>
        )}
      </div>
      {!tableId && !columnId && <EmptyState title="Pick a table to analyse" hint="Or start from any table page with the Impact analysis button." />}
      {(tableId || columnId) && impact.isPending && <Loading />}
      {(tableId || columnId) && impact.isError && <ErrorState error={impact.error} retry={() => impact.refetch()} />}
      {impact.data && <ImpactView impact={impact.data} />}
    </>
  );
}

export function ImpactView({ impact }: { impact: Impact }) {
  const mini = useMemo(() => buildMiniGraph(impact), [impact]);
  const consumers = [...impact.directConsumers.map((c) => ({ ...c, indirect: false })), ...impact.indirectConsumers.map((c) => ({ ...c, indirect: true }))];
  const targetLink = impact.target.type === 'COLUMN' ? undefined : links.table(impact.target.id);
  return (
    <div className="stack">
      <div className="grid cols-3">
        <Card title="Target">
          <div className="row between" style={{ marginBottom: 10 }}>
            <div>
              <div className="muted small">{impact.target.type.toLowerCase()}</div>
              <h2 style={{ wordBreak: 'break-word' }}>{impact.target.label}</h2>
            </div>
            {targetLink && <Link className="btn sm" to={targetLink}><ExternalLink /> Open</Link>}
          </div>
          <KV items={[
            ['Owner', impact.owner ? <Link to={links.team(impact.owner.id)}>{impact.owner.displayName}</Link> : <Badge tone="amber">unowned</Badge>],
            ['Producer', impact.producer ? <Link to={links.application(impact.producer.id)}>{impact.producer.name}</Link> : <span className="muted">none declared</span>],
            ['Queries', <span>{compact(impact.queryStats.count24h)} / 24h · {compact(impact.queryStats.count7d)} / 7d</span>],
            ['Last seen', <RelativeTime value={impact.queryStats.lastSeenAt} />],
          ]} />
        </Card>
        <Card title="Risk" className="span-2">
          <div className="risk">
            <RiskRing score={impact.riskScore} />
            <div style={{ flex: 1 }}>
              <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 8 }}>
                <StatTile label="Teams affected" value={impact.teamsAffected.length} raw />
                <StatTile label="Direct consumers" value={impact.directConsumers.length} raw />
                <StatTile label="Indirect consumers" value={impact.indirectConsumers.length} raw />
                <StatTile label="Routines / triggers / views" value={`${impact.routines.length} / ${impact.triggers.length} / ${impact.dependentViews.length}`} />
                <StatTile label="FK dependents" value={impact.foreignKeyDependents.length} raw />
              </div>
              <div className="factor-list" style={{ marginTop: 10 }}>
                {impact.riskFactors.length ? impact.riskFactors.map((f) => <Badge key={f} outline lg>{f}</Badge>) : <span className="muted">No risk factors identified.</span>}
              </div>
            </div>
          </div>
        </Card>

        <Card className="span-2" title="Consumers" hint="direct and via routines" tight>
          {consumers.length === 0 ? <EmptyState inline title="No consumers observed" /> : (
            <DataTable compact rows={consumers} rowKey={(c) => `${c.application.id}-${c.kind}-${c.viaRoutine?.id ?? 'd'}`} initialSort={{ key: 'q', dir: 'desc' }} columns={[
              { key: 'app', header: 'Application', render: (c) => <Link to={links.application(c.application.id)}>{c.application.name}</Link> },
              { key: 'team', header: 'Team', render: (c) => (c.team ? <Link to={links.team(c.team.id)} style={impact.owner && c.team.id !== impact.owner.id ? { color: 'var(--warning)' } : undefined}>{c.team.name}</Link> : <span className="muted">—</span>) },
              { key: 'kind', header: 'Access', render: (c) => <KindBadge kind={c.kind} /> },
              { key: 'path', header: 'Path', render: (c) => (c.indirect && c.viaRoutine ? <span className="row" style={{ gap: 4 }}><span className="muted small">via</span><Link to={links.routine(c.viaRoutine.id)}>{c.viaRoutine.name}</Link></span> : <span className="muted">direct</span>) },
              { key: 'src', header: 'Source', render: (c) => <SourceBadge source={c.source} /> },
              { key: 'q', header: 'Queries', align: 'right', sort: (c) => c.queryCount ?? 0, render: (c) => compact(c.queryCount ?? 0) },
              { key: 'last', header: 'Last seen', render: (c) => <RelativeTime value={c.lastSeenAt} staleDays={30} /> },
            ]} />
          )}
        </Card>
        <Card title="Teams affected">
          {impact.teamsAffected.length === 0 ? <EmptyState inline title="No other teams" /> : (
            <div className="stack" style={{ gap: 8 }}>
              {impact.teamsAffected.map((t) => (
                <div key={t.id} className="row between">
                  <Link to={links.team(t.id)}>{t.displayName}</Link>
                  <span className="muted small">{t.contacts.join(', ')}</span>
                </div>
              ))}
            </div>
          )}
        </Card>

        <Card title="Database-side logic" hint="routines, triggers, views">
          <KV items={[
            ['Routines', impact.routines.length ? <span className="inline-list">{impact.routines.map((r) => <Link key={r.id} to={links.routine(r.id)} className="badge pink">{r.name}</Link>)}</span> : <span className="muted">none</span>],
            ['Triggers', impact.triggers.length ? <span className="inline-list">{impact.triggers.map((r) => <Link key={r.id} to={links.routine(r.id)} className="badge red">{r.name}</Link>)}</span> : <span className="muted">none</span>],
            ['Dependent views', impact.dependentViews.length ? <span className="inline-list">{impact.dependentViews.map((v) => <Link key={v.id} to={links.table(v.id)} className="badge teal">{v.schema}.{v.name}</Link>)}</span> : <span className="muted">none</span>],
            ['FK dependents', impact.foreignKeyDependents.length ? <span className="inline-list">{impact.foreignKeyDependents.map((t) => <Link key={t.id} to={links.table(t.id)} className="badge outline">{t.schema}.{t.name}</Link>)}</span> : <span className="muted">none</span>],
          ]} />
        </Card>
        <Card title="Mini graph" hint="target, consumers, logic, FKs" className="span-2" tight>
          <div className="mini-graph" style={{ border: 0, borderRadius: 0 }}>
            <GraphCanvas graph={mini} rootId={`target:${impact.target.id}`} />
          </div>
          <div className="card-footer"><span>{mini.nodes.length} nodes · {mini.edges.length} edges</span>{impact.target.type !== 'COLUMN' && <Link to={links.graph(`table:${impact.target.id}`, 2)}>Open full graph →</Link>}</div>
        </Card>

        {impact.queriesReferencingColumn && (
          <Card className="span-2" title="Queries referencing the column" hint="from normalised SQL" tight>
            {impact.queriesReferencingColumn.length === 0 ? <EmptyState inline title="No query mentions this column by name" hint="Unqualified SELECT * or dynamic SQL cannot be attributed." /> : (
              <DataTable compact rows={impact.queriesReferencingColumn} rowKey={(q) => `${q.sqlHash}-${q.applicationId}`} columns={[
                { key: 'sql', header: 'SQL', className: 'truncate', render: (q) => <span className="sql" title={q.sqlNormalized}>{q.sqlNormalized}</span> },
                { key: 'op', header: 'Op', render: (q) => <Badge outline>{q.operation}</Badge> },
                { key: 'app', header: 'Application', render: (q) => (q.applicationId ? <Link to={links.application(q.applicationId)}>{q.applicationName}</Link> : <span className="muted">unknown</span>) },
                { key: 'count', header: 'Count', align: 'right', render: (q) => compact(q.count) },
                { key: 'avg', header: 'Avg', align: 'right', render: (q) => formatMs(q.avgDurationMs) },
              ]} />
            )}
          </Card>
        )}
      </div>
    </div>
  );
}

/** Build a small client-side graph around the impact target so the picture is readable at a glance. */
function buildMiniGraph(impact: Impact): Graph {
  const nodes = new Map<string, GraphNode>();
  const edges: GraphEdge[] = [];
  const targetId = `target:${impact.target.id}`;
  nodes.set(targetId, { id: targetId, type: 'TABLE', label: impact.target.label, refId: impact.target.id, attrs: {} });
  const add = (n: GraphNode) => { if (!nodes.has(n.id)) nodes.set(n.id, n); return n.id; };
  const edge = (from: string, to: string, kind: GraphEdge['kind']) => { const id = `${from}-${kind}-${to}`; if (!edges.some((e) => e.id === id)) edges.push({ id, from, to, kind, attrs: {} }); };
  if (impact.owner) edge(add({ id: `team:${impact.owner.id}`, type: 'TEAM', label: impact.owner.name, refId: impact.owner.id, attrs: {} }), targetId, 'OWNS');
  for (const c of impact.directConsumers) {
    const a = add({ id: `application:${c.application.id}`, type: 'APPLICATION', label: c.application.name, refId: c.application.id, attrs: {} });
    edge(a, targetId, c.kind === 'CALLS' ? 'READS' : c.kind);
    if (c.team) edge(a, add({ id: `team:${c.team.id}`, type: 'TEAM', label: c.team.name, refId: c.team.id, attrs: {} }), 'BELONGS_TO');
  }
  for (const r of impact.routines) edge(add({ id: `routine:${r.id}`, type: 'ROUTINE', label: r.name, refId: r.id, attrs: {} }), targetId, 'REFERENCES');
  for (const v of impact.dependentViews) edge(add({ id: `table:${v.id}`, type: 'TABLE', label: `${v.schema}.${v.name}`, refId: v.id, attrs: { kind: 'VIEW' } }), targetId, 'REFERENCES');
  for (const r of impact.triggers) edge(targetId, add({ id: `routine:${r.id}`, type: 'ROUTINE', label: r.name, refId: r.id, attrs: {} }), 'TRIGGERS');
  for (const c of impact.indirectConsumers) {
    const a = add({ id: `application:${c.application.id}`, type: 'APPLICATION', label: c.application.name, refId: c.application.id, attrs: {} });
    if (c.viaRoutine) edge(a, add({ id: `routine:${c.viaRoutine.id}`, type: 'ROUTINE', label: c.viaRoutine.name, refId: c.viaRoutine.id, attrs: {} }), 'CALLS');
    if (c.team) edge(a, add({ id: `team:${c.team.id}`, type: 'TEAM', label: c.team.name, refId: c.team.id, attrs: {} }), 'BELONGS_TO');
  }
  for (const t of impact.foreignKeyDependents) edge(add({ id: `table:${t.id}`, type: 'TABLE', label: `${t.schema}.${t.name}`, refId: t.id, attrs: {} }), targetId, 'FOREIGN_KEY');
  return { nodes: [...nodes.values()], edges, truncated: false };
}
