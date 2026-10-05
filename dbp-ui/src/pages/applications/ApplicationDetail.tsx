import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { KeyRound, Pencil, Plus, Trash2, AlertTriangle } from 'lucide-react';
import { useAccessGrantMutations, useAllTables, useApiKeys, useApplicationMutations, useApplicationSummary, useDatasources, useRelationships, useRoutines, useTopQueries } from '../../api/hooks';
import type { ApiKeyCreated, Application, ApplicationSummary, IdentityRules } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card, KV } from '../../components/Card';
import { Loading, ErrorState, EmptyState, QueryBoundary } from '../../components/States';
import { AppKindBadge, Badge, KindBadge, SourceBadge, Confidence } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { ConfirmDialog, Modal } from '../../components/Modal';
import { ApplicationForm } from './ApplicationForm';
import { GrantDialog } from '../datasources/DatasourceDetail';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';
import { CopyId, CopyButton } from '../../components/CopyId';
import { DataTable } from '../../components/DataTable';
import { ChipInput } from '../../components/ChipInput';
import { Field } from '../../components/Field';
import { StatTile } from '../../components/StatTile';
import { compact, formatMs } from '../../lib/format';

export function ApplicationDetail() {
  const { id = '' } = useParams();
  const summary = useApplicationSummary(id);
  if (summary.isPending) return <Loading />;
  if (summary.isError) return <ErrorState error={summary.error} retry={() => summary.refetch()} />;
  return <ApplicationView s={summary.data} />;
}

function ApplicationView({ s }: { s: ApplicationSummary }) {
  const a = s.application;
  const navigate = useNavigate();
  const toast = useToast();
  const dss = useDatasources();
  const { update, remove } = useApplicationMutations(a.id);
  const [edit, setEdit] = useState(false);
  const [del, setDel] = useState(false);
  const dsName = (id: string) => dss.data?.find((d) => d.id === id)?.name ?? id;
  return (
    <>
      <PageHeader
        title={a.name}
        crumb={a.name}
        badges={<><AppKindBadge kind={a.kind} /><Badge outline>{a.runtime}</Badge></>}
        subtitle={<span className="row">{a.displayName}{a.description ? ` — ${a.description}` : ''}<CopyId value={a.id} /></span>}
        actions={<><Link className="btn" to={links.graph(`application:${a.id}`, 2)}>Open in graph</Link><button className="btn" onClick={() => setEdit(true)}><Pencil /> Edit</button><button className="btn danger" onClick={() => setDel(true)}><Trash2 /> Delete</button></>}
      />
      <div className="kpi-row" style={{ marginBottom: 16 }}>
        <StatTile label="Proxy connections" value={s.connections.proxy} />
        <StatTile label="Gateway logical sessions" value={s.connections.gatewayLogical} sub={`${s.connections.gatewayPhysical} physical`} />
        <StatTile label="Queries 24h" value={s.queryStats.count24h} sub={`${compact(s.queryStats.count7d)} in 7d`} />
        <StatTile label="Avg duration" value={formatMs(s.queryStats.avgDurationMs)} sub={s.queryStats.errors24h ? `${s.queryStats.errors24h} errors 24h` : 'no errors 24h'} alert={!!s.queryStats.errors24h} />
        <StatTile label="Last query" value={<RelativeTime value={s.queryStats.lastSeenAt} />} />
      </div>
      <div className="grid cols-3">
        <Card title="Details">
          <KV items={[
            ['Team', s.team ? <Link to={links.team(s.team.id)}>{s.team.displayName}</Link> : <span className="muted">no team</span>],
            ['Kind', <AppKindBadge kind={a.kind} />],
            ['Runtime', a.runtime],
            ['Tags', a.tags.length ? <span className="badge-row">{a.tags.map((t) => <Badge key={t} outline>{t}</Badge>)}</span> : null],
            ['Created', <RelativeTime value={a.createdAt} />],
            ['Updated', <RelativeTime value={a.updatedAt} />],
          ]} />
        </Card>
        <GrantsCard app={a} grants={s.grants} dsName={dsName} />
        <ApiKeysCard app={a} />
        <IdentityRulesCard app={a} className="span-2" />
        <Card title="Routines called" hint={`${s.calls.length}`}>
          {s.calls.length === 0 ? <EmptyState inline title="No routine calls observed" /> : <div className="inline-list">{s.calls.map((r) => <Link key={r.id} to={links.routine(r.id)} className="badge pink">{r.schema}.{r.name}</Link>)}</div>}
        </Card>
        <AccessCard app={a} className="span-2" />
        <Card title="Top queries" hint="24h">
          <TopQueries appId={a.id} />
        </Card>
      </div>
      <Modal open={edit} title={`Edit ${a.name}`} onClose={() => setEdit(false)} wide>
        <ApplicationForm initial={a} busy={update.isPending} onCancel={() => setEdit(false)} onSubmit={(v) => update.mutate({ id: a.id, body: v }, { onSuccess: () => { toast.success('Saved'); setEdit(false); }, onError: (e) => toast.error(errorMessage(e)) })} />
      </Modal>
      <ConfirmDialog open={del} title="Delete application" danger confirmLabel="Delete" busy={remove.isPending} onClose={() => setDel(false)}
        message={<p>Delete <strong>{a.name}</strong>? Its API keys stop working immediately and its relationships are removed.</p>}
        onConfirm={() => remove.mutate(a.id, { onSuccess: () => { toast.success('Application deleted'); navigate(links.applications()); }, onError: (e) => toast.error(errorMessage(e)) })} />
    </>
  );
}

function GrantsCard({ app, grants, dsName }: { app: Application; grants: ApplicationSummary['grants']; dsName: (id: string) => string }) {
  const { create, update, remove } = useAccessGrantMutations();
  const toast = useToast();
  const [editing, setEditing] = useState<typeof grants[number] | 'new' | null>(null);
  return (
    <Card title="Datasource grants" actions={<button className="btn sm" onClick={() => setEditing('new')}><Plus /> Grant</button>} tight>
      {grants.length === 0 ? <EmptyState inline title="No grants" hint="The gateway refuses this application until it is granted a datasource." /> : (
        <DataTable compact rows={grants} rowKey={(g) => g.id} columns={[
          { key: 'ds', header: 'Datasource', render: (g) => <Link to={links.datasource(g.datasourceId)}>{dsName(g.datasourceId)}</Link> },
          { key: 'limits', header: 'Limits', render: (g) => <span className="small">{g.maxLogicalConnections} logical · {g.maxProxyConnections} proxy</span> },
          { key: 'flags', header: '', render: (g) => <span className="badge-row">{g.readOnly && <Badge tone="amber">read-only</Badge>}{!g.enabled && <Badge tone="red">disabled</Badge>}{g.poolModeOverride && <Badge outline>{g.poolModeOverride}</Badge>}</span> },
          { key: 'a', header: '', render: (g) => <div className="row-actions"><button className="btn sm ghost icon" aria-label="Edit grant" onClick={() => setEditing(g)}><Pencil /></button><button className="btn sm ghost icon" aria-label="Revoke grant" onClick={() => remove.mutate(g.id, { onSuccess: () => toast.success('Grant revoked'), onError: (e) => toast.error(errorMessage(e)) })}><Trash2 /></button></div> },
        ]} />
      )}
      {editing && <GrantDialog applicationId={app.id} grant={editing === 'new' ? null : editing} busy={create.isPending || update.isPending} onClose={() => setEditing(null)}
        onSave={(g) => editing === 'new' ? create.mutate(g, { onSuccess: () => { toast.success('Grant created'); setEditing(null); }, onError: (e) => toast.error(errorMessage(e)) }) : update.mutate({ id: editing.id, body: g }, { onSuccess: () => { toast.success('Grant saved'); setEditing(null); }, onError: (e) => toast.error(errorMessage(e)) })} />}
    </Card>
  );
}

function ApiKeysCard({ app }: { app: Application }) {
  const keys = useApiKeys(app.id);
  const { createApiKey, revokeApiKey } = useApplicationMutations(app.id);
  const toast = useToast();
  const [label, setLabel] = useState('');
  const [created, setCreated] = useState<ApiKeyCreated | null>(null);
  const [revoke, setRevoke] = useState<string | null>(null);
  return (
    <Card title="API keys" hint="gateway credential" tight>
      <div className="card-body" style={{ borderBottom: '1px solid var(--border)' }}>
        <form className="row" onSubmit={(e) => { e.preventDefault(); createApiKey.mutate(label || 'default', { onSuccess: (k) => { setCreated(k); setLabel(''); }, onError: (err) => toast.error(errorMessage(err)) }); }}>
          <input className="input sm" style={{ flex: 1 }} value={label} onChange={(e) => setLabel(e.target.value)} placeholder="label (prod, staging…)" aria-label="API key label" />
          <button className="btn sm primary" type="submit" disabled={createApiKey.isPending}><KeyRound /> Create key</button>
        </form>
      </div>
      <QueryBoundary query={keys} empty={<EmptyState inline title="No API keys" hint="Create one and put it in the application's jdbc:dbp:// URL or DBP_API_KEY." />}>
        {(rows) => (
          <DataTable compact rows={rows} rowKey={(k) => k.id} columns={[
            { key: 'prefix', header: 'Key', render: (k) => <code>dbp_{k.prefix}_…</code> },
            { key: 'label', header: 'Label', render: (k) => k.label },
            { key: 'created', header: 'Created', render: (k) => <RelativeTime value={k.createdAt} /> },
            { key: 'used', header: 'Last used', render: (k) => <RelativeTime value={k.lastUsedAt} /> },
            { key: 'status', header: '', render: (k) => (k.revokedAt ? <Badge tone="red" title={k.revokedAt}>revoked</Badge> : <button className="btn sm ghost" onClick={() => setRevoke(k.id)}>Revoke</button>) },
          ]} />
        )}
      </QueryBoundary>
      <Modal open={!!created} title="API key created" onClose={() => setCreated(null)} footer={<button className="btn primary" onClick={() => setCreated(null)}>I have stored it</button>}>
        <div className="notice warning" style={{ marginBottom: 12 }}><AlertTriangle /><div>This is the only time the key is shown. The control plane stores a hash.</div></div>
        <div className="secret-box"><span style={{ flex: 1 }}>{created?.apiKey}</span>{created && <CopyButton value={created.apiKey} />}</div>
        <p className="muted small" style={{ marginTop: 10 }}>Use it as <code>jdbc:dbp://gateway:7420/&lt;datasource&gt;?apiKey=…</code> or in <code>DBP_API_KEY</code>.</p>
      </Modal>
      <ConfirmDialog open={!!revoke} title="Revoke API key" danger confirmLabel="Revoke" busy={revokeApiKey.isPending} onClose={() => setRevoke(null)}
        message={<p>Connections authenticating with this key will be refused on their next session.</p>}
        onConfirm={() => revoke && revokeApiKey.mutate(revoke, { onSuccess: () => { toast.success('Key revoked'); setRevoke(null); }, onError: (e) => toast.error(errorMessage(e)) })} />
    </Card>
  );
}

const RULE_FIELDS: Array<{ key: keyof IdentityRules; label: string; help: string; placeholder: string }> = [
  { key: 'serviceAliases', label: 'Service aliases', help: 'Proxy: <datasource>.<alias> requested as service name / database. Highest precedence.', placeholder: 'orders-service' },
  { key: 'pgApplicationNames', label: 'PostgreSQL application_name', help: 'Matched against the startup parameter.', placeholder: 'orders-service' },
  { key: 'programNames', label: 'Program names', help: 'Oracle CID PROGRAM / V$SESSION.PROGRAM.', placeholder: 'JDBC Thin Client/orders' },
  { key: 'machinePatterns', label: 'Machine patterns', help: 'Glob on the client host name (Oracle CID HOST, V$SESSION.MACHINE).', placeholder: 'orders-service-*' },
  { key: 'cidrs', label: 'CIDRs', help: 'Client address ranges. Lowest precedence.', placeholder: '10.20.0.0/16' },
];

function IdentityRulesCard({ app, className }: { app: Application; className?: string }) {
  const { update } = useApplicationMutations(app.id);
  const toast = useToast();
  const [rules, setRules] = useState<IdentityRules>(app.identityRules);
  useEffect(() => setRules(app.identityRules), [app.identityRules]);
  const dirty = JSON.stringify(rules) !== JSON.stringify(app.identityRules);
  return (
    <Card className={className} title="Identity rules" hint="how proxy and collectors attribute connections" actions={dirty && <button className="btn sm ghost" onClick={() => setRules(app.identityRules)}>Reset</button>}>
      <form onSubmit={(e) => { e.preventDefault(); update.mutate({ id: app.id, body: { identityRules: rules } }, { onSuccess: () => toast.success('Identity rules saved'), onError: (err) => toast.error(errorMessage(err)) }); }}>
        <div className="form-grid">
          {RULE_FIELDS.map((f) => (
            <Field key={f.key} label={f.label} help={f.help} full={f.key === 'serviceAliases'}>{(id) => <ChipInput id={id} value={rules[f.key]} onChange={(v) => setRules({ ...rules, [f.key]: v })} placeholder={f.placeholder} />}</Field>
          ))}
        </div>
        <div className="form-actions"><button type="submit" className="btn primary sm" disabled={!dirty || update.isPending}>{update.isPending && <span className="spinner" />} Save rules</button></div>
      </form>
    </Card>
  );
}

function AccessCard({ app, className }: { app: Application; className?: string }) {
  const rels = useRelationships({ applicationId: app.id });
  const [kind, setKind] = useState<'all' | 'READS' | 'WRITES' | 'CALLS'>('all');
  return (
    <Card className={className} title="Reads, writes and calls" hint="from telemetry and declarations" tight
      actions={<div className="segmented" role="group" aria-label="Filter by kind">{(['all', 'READS', 'WRITES', 'CALLS'] as const).map((k) => <button key={k} type="button" aria-pressed={kind === k} onClick={() => setKind(k)}>{k.toLowerCase()}</button>)}</div>}>
      <QueryBoundary query={rels} empty={<EmptyState inline title="No access observed yet" hint="Relationships appear once the gateway, proxy or collectors see this application." />}>
        {(rows) => (
          <RelationshipTable rows={rows.filter((r) => kind === 'all' || r.kind === kind)} />
        )}
      </QueryBoundary>
    </Card>
  );
}

function RelationshipTable({ rows }: { rows: ReturnType<typeof useRelationships>['data'] & object }) {
  const names = useObjectNames();
  return (
    <DataTable compact rows={rows} rowKey={(r) => r.id} initialSort={{ key: 'count', dir: 'desc' }} empty={<EmptyState inline title="Nothing of that kind" />} columns={[
      { key: 'kind', header: 'Kind', render: (r) => <KindBadge kind={r.kind} /> },
      { key: 'obj', header: 'Object', render: (r) => <Link to={r.objectType === 'TABLE' ? links.table(r.objectId) : links.routine(r.objectId)}>{names(r.objectType, r.objectId)}</Link> },
      { key: 'via', header: 'Via', render: (r) => (r.viaRoutineId ? <Link to={links.routine(r.viaRoutineId)} className="small">{names('ROUTINE', r.viaRoutineId)}</Link> : <span className="muted">direct</span>) },
      { key: 'source', header: 'Source', render: (r) => <span className="row" style={{ gap: 6 }}><SourceBadge source={r.source} /><Confidence value={r.confidence} />{r.confirmed && <Badge tone="green">confirmed</Badge>}</span> },
      { key: 'count', header: 'Queries', align: 'right', sort: (r) => r.queryCount, render: (r) => compact(r.queryCount) },
      { key: 'last', header: 'Last seen', sort: (r) => r.lastSeenAt, render: (r) => <RelativeTime value={r.lastSeenAt} staleDays={30} /> },
    ]} />
  );
}

/** Resolve object ids to names using the lists already in the cache. */
function useObjectNames() {
  const tables = useAllTables();
  const routines = useRoutines();
  return (type: 'TABLE' | 'ROUTINE', id: string) => {
    const o = type === 'TABLE' ? tables.data?.find((t) => t.id === id) : routines.data?.find((r) => r.id === id);
    return o ? `${o.schema}.${o.name}` : id;
  };
}

function TopQueries({ appId }: { appId: string }) {
  const q = useTopQueries({ by: 'count', window: '24h', applicationId: appId, limit: 6 });
  return (
    <QueryBoundary query={q} empty={<EmptyState inline title="No queries in the window" />}>
      {(rows) => (
        <div className="stack" style={{ gap: 10 }}>
          {rows.map((r) => (
            <div key={r.sqlHash}>
              <div className="row between small"><span className="row" style={{ gap: 6 }}><Badge outline>{r.operation}</Badge>{r.tables.slice(0, 3).map((t) => <Link key={t.id} to={links.table(t.id)} className="muted">{t.schema}.{t.name}</Link>)}</span><span className="muted">{compact(r.count)} × {formatMs(r.avgDurationMs)}</span></div>
              <div className="sql clamp" title={r.sqlNormalized}>{r.sqlNormalized}</div>
            </div>
          ))}
          <Link to={links.queries({ applicationId: appId })} className="small">All queries of this application →</Link>
        </div>
      )}
    </QueryBoundary>
  );
}
