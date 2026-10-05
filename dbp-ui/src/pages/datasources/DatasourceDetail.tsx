import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowRight, ArrowLeftRight, Pencil, Plus, Trash2 } from 'lucide-react';
import {
  useAccessGrantMutations, useApplications, useDatabases, useDatasourceMutations, useDatasources, useDatasourceSummary, useMigrationEvents,
} from '../../api/hooks';
import type { AccessGrant, AccessGrantInput, Datasource, DatasourceSummary, PoolMode, PoolPolicy, RoutingRule, RoutingRuleInput } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card, KV } from '../../components/Card';
import { Loading, ErrorState, EmptyState } from '../../components/States';
import { Badge, EngineBadge, StateBadge } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { ConfirmDialog, Modal } from '../../components/Modal';
import { DatasourceForm } from './DatasourceForm';
import { SwitchDialog } from './SwitchDialog';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';
import { CopyId } from '../../components/CopyId';
import { DataTable } from '../../components/DataTable';
import { Field, Toggle } from '../../components/Field';
import { Meter } from '../../components/Meter';

export function DatasourceDetail() {
  const { id = '' } = useParams();
  const summary = useDatasourceSummary(id);
  if (summary.isPending) return <Loading />;
  if (summary.isError) return <ErrorState error={summary.error} retry={() => summary.refetch()} />;
  return <DatasourceView s={summary.data} />;
}

function DatasourceView({ s }: { s: DatasourceSummary }) {
  const ds = s.datasource;
  const navigate = useNavigate();
  const toast = useToast();
  const dbs = useDatabases();
  const apps = useApplications();
  const events = useMigrationEvents(ds.id);
  const { update, remove } = useDatasourceMutations(ds.id);
  const [edit, setEdit] = useState(false);
  const [del, setDel] = useState(false);
  const [sw, setSw] = useState(false);
  const dbName = (id: string | null) => dbs.data?.find((d) => d.id === id)?.name ?? id ?? '—';
  const appName = (id: string | null) => apps.data?.find((a) => a.id === id)?.name ?? id ?? '—';

  return (
    <>
      <PageHeader
        title={ds.name}
        crumb={ds.name}
        badges={<StateBadge state={ds.state} lg />}
        subtitle={<span className="row">{ds.displayName}{ds.description ? ` — ${ds.description}` : ''}<CopyId value={ds.id} /></span>}
        actions={
          <>
            <button className="btn primary" onClick={() => setSw(true)}><ArrowLeftRight /> Switch database</button>
            <button className="btn" onClick={() => setEdit(true)}><Pencil /> Edit</button>
            <button className="btn danger" onClick={() => setDel(true)}><Trash2 /> Delete</button>
          </>
        }
      />

      <div className="grid cols-3">
        <Card title="Routing">
          <div className="row" style={{ gap: 10, marginBottom: 12 }}>
            <div className="stat-tile" style={{ flex: 1, padding: '8px 12px' }}>
              <div className="label">Current database</div>
              <div className="row" style={{ gap: 6 }}>{s.currentDatabase ? <><strong><Link to={links.database(s.currentDatabase.id)}>{s.currentDatabase.name}</Link></strong><EngineBadge engine={s.currentDatabase.engine} /></> : <span className="muted">unknown</span>}</div>
            </div>
            <ArrowRight className="muted" />
            <div className="stat-tile" style={{ flex: 1, padding: '8px 12px', borderStyle: s.targetDatabase ? 'solid' : 'dashed' }}>
              <div className="label">Target database</div>
              <div className="row" style={{ gap: 6 }}>{s.targetDatabase ? <><strong><Link to={links.database(s.targetDatabase.id)}>{s.targetDatabase.name}</Link></strong><EngineBadge engine={s.targetDatabase.engine} /></> : <span className="muted">none planned</span>}</div>
            </div>
          </div>
          <KV items={[
            ['Owner team', s.ownerTeam ? <Link to={links.team(s.ownerTeam.id)}>{s.ownerTeam.displayName}</Link> : null],
            ['Tags', ds.tags.length ? <span className="badge-row">{ds.tags.map((t) => <Badge key={t} outline>{t}</Badge>)}</span> : null],
            ['Resolution', <span className="small muted">first enabled rule by priority: application match → tag match → current database</span>],
            ['Updated', <RelativeTime value={ds.updatedAt} />],
          ]} />
        </Card>

        <Card title="Pool policy" hint="applies to every gateway">
          <PoolPolicyEditor policy={ds.poolPolicy} busy={update.isPending} onSave={(p) => update.mutate({ id: ds.id, body: { poolPolicy: p } }, { onSuccess: () => toast.success('Pool policy saved'), onError: (e) => toast.error(errorMessage(e)) })} />
        </Card>

        <Card title="Gateway pools" hint="live">
          {s.pools.length === 0 ? <EmptyState inline title="No pool stats yet" hint="Gateways report pool stats every few seconds." /> : (
            s.pools.map((p) => (
              <Meter key={`${p.gatewayId}-${p.databaseId}`} label={p.gatewayId} sub={`${dbName(p.databaseId)} · cred v${p.credentialVersion}`} value={p.active + p.idle} max={p.max}
                format={(v, m) => `${p.active} active · ${p.idle} idle / ${m}${p.waiting ? ` · ${p.waiting} waiting` : ''} · ${p.logicalSessions} logical${v > m ? '' : ''}`} />
            ))
          )}
        </Card>

        <RoutingRulesCard ds={ds} dbName={dbName} appName={appName} className="span-2" />

        <Card title="Consumers" hint="applications with a grant">
          {s.consumers.length === 0 ? <EmptyState inline title="No consumers" /> : (
            <div className="stack" style={{ gap: 6 }}>
              {s.consumers.map((a) => <div key={a.id} className="row between"><Link to={links.application(a.id)}>{a.name}</Link><span className="muted small">{a.kind}</span></div>)}
            </div>
          )}
        </Card>

        <GrantsCard ds={ds} grants={s.grants} appName={appName} className="span-2" />

        <Card title="Tables routed" hint={`${s.tables.length}`}>
          {s.tables.length === 0 ? <EmptyState inline title="No tables observed" hint="Tables appear once applications query them through the platform." /> : (
            <div className="inline-list">{s.tables.map((t) => <Link key={t.id} to={links.table(t.id)} className="badge outline">{t.schema}.{t.name}</Link>)}</div>
          )}
        </Card>

        <Card title="Migration events" className="span-2">
          {events.isPending ? <Loading inline rows={2} /> : events.isError ? <ErrorState inline error={events.error} /> : events.data.length === 0 ? <EmptyState inline title="No migration events" /> : (
            <ul className="timeline">
              {events.data.map((e) => (
                <li key={e.id}>
                  <span className="dot" aria-hidden="true" />
                  <div>
                    <div><strong>{e.fromDatabaseId ? dbName(e.fromDatabaseId) : 'registered'}</strong> → <strong>{dbName(e.toDatabaseId)}</strong> <span className="muted small">by {e.by}</span></div>
                    <div className="muted small"><RelativeTime value={e.at} />{e.note ? ` · ${e.note}` : ''}</div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card title="Impact analysis">
          <p className="muted small">Before switching, the dialog shows every consumer, team, trigger and table currently routed through <strong>{ds.name}</strong>.</p>
          <div className="btn-group">
            <button className="btn" onClick={() => setSw(true)}><ArrowLeftRight /> Preview switch impact</button>
            <Link className="btn ghost" to={links.graph(`datasource:${ds.id}`, 3)}>Open in graph</Link>
          </div>
        </Card>
      </div>

      {sw && <SwitchDialog ds={ds} current={s.currentDatabase} onClose={() => setSw(false)} />}
      <Modal open={edit} title={`Edit ${ds.name}`} onClose={() => setEdit(false)} wide>
        <DatasourceForm initial={ds} busy={update.isPending} onCancel={() => setEdit(false)} onSubmit={(v) => update.mutate({ id: ds.id, body: v }, { onSuccess: () => { toast.success('Saved'); setEdit(false); }, onError: (e) => toast.error(errorMessage(e)) })} />
      </Modal>
      <ConfirmDialog open={del} title="Delete datasource" danger confirmLabel="Delete" busy={remove.isPending} onClose={() => setDel(false)}
        message={<p>Delete <strong>{ds.name}</strong>? Applications using <code>jdbc:dbp://…/{ds.name}</code> will fail to resolve immediately.</p>}
        onConfirm={() => remove.mutate(ds.id, { onSuccess: () => { toast.success('Datasource deleted'); navigate(links.datasources()); }, onError: (e) => toast.error(errorMessage(e)) })} />
    </>
  );
}

// ------------------------------------------------------------------ pool policy
function PoolPolicyEditor({ policy, onSave, busy }: { policy: PoolPolicy; onSave: (p: PoolPolicy) => void; busy: boolean }) {
  const [p, setP] = useState(policy);
  useEffect(() => setP(policy), [policy]);
  const dirty = JSON.stringify(p) !== JSON.stringify(policy);
  const num = (k: keyof PoolPolicy, label: string, help?: string) => (
    <Field key={k} label={label} help={help}>{(id) => <input id={id} className="input sm" type="number" min={0} value={p[k] as number} onChange={(e) => setP({ ...p, [k]: Number(e.target.value) })} />}</Field>
  );
  return (
    <form onSubmit={(e) => { e.preventDefault(); onSave(p); }}>
      <div className="form-grid">
        <Field label="Mode" help="TRANSACTION returns the connection after each transaction; SESSION pins it.">{(id) => (
          <select id={id} className="input sm" value={p.mode} onChange={(e) => setP({ ...p, mode: e.target.value as PoolMode })}><option>TRANSACTION</option><option>SESSION</option></select>
        )}</Field>
        {num('maxConnections', 'Max connections')}
        {num('minIdle', 'Min idle')}
        {num('connectionTimeoutMs', 'Connection timeout (ms)')}
        {num('idleTimeoutMs', 'Idle timeout (ms)')}
        {num('maxLifetimeMs', 'Max lifetime (ms)')}
        {num('statementTimeoutSeconds', 'Statement timeout (s)', '0 = none')}
        <Field label="Validation query">{(id) => <input id={id} className="input sm" value={p.validationQuery ?? ''} onChange={(e) => setP({ ...p, validationQuery: e.target.value || null })} placeholder="SELECT 1" />}</Field>
      </div>
      <div className="form-actions" style={{ marginTop: 10 }}>
        {dirty && <button type="button" className="btn ghost" onClick={() => setP(policy)}>Reset</button>}
        <button type="submit" className="btn primary sm" disabled={!dirty || busy}>{busy && <span className="spinner" />} Save policy</button>
      </div>
    </form>
  );
}

// ------------------------------------------------------------------ routing rules
function RoutingRulesCard({ ds, dbName, appName, className }: { ds: Datasource; dbName: (id: string | null) => string; appName: (id: string | null) => string; className?: string }) {
  const { addRule, replaceRules, deleteRule } = useDatasourceMutations(ds.id);
  const toast = useToast();
  const [editing, setEditing] = useState<RoutingRule | 'new' | null>(null);
  const [confirmDel, setConfirmDel] = useState<RoutingRule | null>(null);
  const rules = [...ds.routingRules].sort((a, b) => a.priority - b.priority);
  const save = (rule: RoutingRuleInput) => {
    if (editing === 'new') addRule.mutate(rule, { onSuccess: () => { toast.success('Rule added'); setEditing(null); }, onError: (e) => toast.error(errorMessage(e)) });
    else replaceRules.mutate(rules.map((r) => (r.id === rule.id ? { ...r, ...rule } : r)), { onSuccess: () => { toast.success('Rule saved'); setEditing(null); }, onError: (e) => toast.error(errorMessage(e)) });
  };
  const toggle = (r: RoutingRule) => replaceRules.mutate(rules.map((x) => (x.id === r.id ? { ...x, enabled: !x.enabled } : x)), { onError: (e) => toast.error(errorMessage(e)) });
  return (
    <Card className={className} title="Routing rules" hint="evaluated by ascending priority" actions={<button className="btn sm" onClick={() => setEditing('new')}><Plus /> Add rule</button>} tight>
      {rules.length === 0 ? <EmptyState inline title="No routing rules" hint="Every application follows the current database. Add a rule to divert one application (or a tag) to the target database." /> : (
        <DataTable compact rows={rules} rowKey={(r) => r.id} columns={[
          { key: 'prio', header: 'Priority', align: 'right', render: (r) => r.priority },
          { key: 'match', header: 'Matches', render: (r) => (r.applicationId ? <span className="row" style={{ gap: 6 }}><Badge outline>application</Badge><Link to={links.application(r.applicationId)}>{appName(r.applicationId)}</Link></span> : r.tag ? <span className="row" style={{ gap: 6 }}><Badge outline>tag</Badge><code>{r.tag}</code></span> : <span className="muted">everything</span>) },
          { key: 'db', header: 'Routes to', render: (r) => <Link to={links.database(r.databaseId)}>{dbName(r.databaseId)}</Link> },
          { key: 'ro', header: 'Read only', render: (r) => (r.readOnly ? <Badge tone="amber">read-only</Badge> : <span className="muted">no</span>) },
          { key: 'enabled', header: 'Enabled', render: (r) => <Toggle checked={r.enabled} onChange={() => toggle(r)} label={<span className="sr-only">enabled</span>} disabled={replaceRules.isPending} /> },
          { key: 'actions', header: '', render: (r) => <div className="row-actions"><button className="btn sm ghost icon" aria-label="Edit rule" onClick={() => setEditing(r)}><Pencil /></button><button className="btn sm ghost icon" aria-label="Delete rule" onClick={() => setConfirmDel(r)}><Trash2 /></button></div> },
        ]} />
      )}
      {editing && <RuleDialog ds={ds} rule={editing === 'new' ? null : editing} busy={addRule.isPending || replaceRules.isPending} onClose={() => setEditing(null)} onSave={save} />}
      <ConfirmDialog open={!!confirmDel} title="Delete routing rule" danger confirmLabel="Delete" busy={deleteRule.isPending} onClose={() => setConfirmDel(null)}
        message={<p>Applications matched by this rule will fall back to the current database.</p>}
        onConfirm={() => confirmDel && deleteRule.mutate(confirmDel.id, { onSuccess: () => { toast.success('Rule deleted'); setConfirmDel(null); }, onError: (e) => toast.error(errorMessage(e)) })} />
    </Card>
  );
}

function RuleDialog({ ds, rule, onSave, onClose, busy }: { ds: Datasource; rule: RoutingRule | null; onSave: (r: RoutingRuleInput) => void; onClose: () => void; busy: boolean }) {
  const apps = useApplications();
  const dbs = useDatabases();
  const [matchBy, setMatchBy] = useState<'application' | 'tag'>(rule?.tag ? 'tag' : 'application');
  const [r, setR] = useState<RoutingRuleInput>(rule ?? { priority: (Math.max(0, ...ds.routingRules.map((x) => x.priority)) || 0) + 10, applicationId: null, tag: null, databaseId: ds.targetDatabaseId ?? '', readOnly: false, enabled: true });
  return (
    <Modal open title={rule ? 'Edit routing rule' : 'Add routing rule'} onClose={onClose}>
      <form onSubmit={(e) => { e.preventDefault(); onSave({ ...r, applicationId: matchBy === 'application' ? r.applicationId : null, tag: matchBy === 'tag' ? r.tag : null }); }}>
        <div className="form-grid">
          <Field label="Priority" help="Lower runs first.">{(id) => <input id={id} className="input" type="number" value={r.priority} onChange={(e) => setR({ ...r, priority: Number(e.target.value) })} />}</Field>
          <Field label="Match by">{(id) => <select id={id} className="input" value={matchBy} onChange={(e) => setMatchBy(e.target.value as 'application' | 'tag')}><option value="application">application</option><option value="tag">application tag</option></select>}</Field>
          {matchBy === 'application' ? (
            <Field label="Application" full>{(id) => <select id={id} className="input" required value={r.applicationId ?? ''} onChange={(e) => setR({ ...r, applicationId: e.target.value || null })}><option value="">— choose —</option>{apps.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select>}</Field>
          ) : (
            <Field label="Tag" full help="Any application carrying this tag.">{(id) => <input id={id} className="input" required value={r.tag ?? ''} onChange={(e) => setR({ ...r, tag: e.target.value })} placeholder="pilot" />}</Field>
          )}
          <Field label="Route to database" full>{(id) => <select id={id} className="input" required value={r.databaseId} onChange={(e) => setR({ ...r, databaseId: e.target.value })}><option value="">— choose —</option>{dbs.data?.map((d) => <option key={d.id} value={d.id}>{d.name} ({d.engine})</option>)}</select>}</Field>
          <label className="checkbox"><input type="checkbox" checked={r.readOnly} onChange={(e) => setR({ ...r, readOnly: e.target.checked })} /> Read only</label>
          <label className="checkbox"><input type="checkbox" checked={r.enabled} onChange={(e) => setR({ ...r, enabled: e.target.checked })} /> Enabled</label>
        </div>
        <div className="form-actions"><button type="button" className="btn" onClick={onClose}>Cancel</button><button type="submit" className="btn primary" disabled={busy}>{busy && <span className="spinner" />} Save</button></div>
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------ grants
function GrantsCard({ ds, grants, appName, className }: { ds: Datasource; grants: AccessGrant[]; appName: (id: string | null) => string; className?: string }) {
  const { create, update, remove } = useAccessGrantMutations();
  const toast = useToast();
  const [editing, setEditing] = useState<AccessGrant | 'new' | null>(null);
  const [confirmDel, setConfirmDel] = useState<AccessGrant | null>(null);
  return (
    <Card className={className} title="Access grants" hint="which applications may use this datasource" actions={<button className="btn sm" onClick={() => setEditing('new')}><Plus /> Grant access</button>} tight>
      {grants.length === 0 ? <EmptyState inline title="No grants" hint="Without a grant, the gateway refuses the application (403)." /> : (
        <DataTable compact rows={grants} rowKey={(g) => g.id} columns={[
          { key: 'app', header: 'Application', render: (g) => <Link to={links.application(g.applicationId)}>{appName(g.applicationId)}</Link> },
          { key: 'logical', header: 'Max logical', align: 'right', render: (g) => g.maxLogicalConnections },
          { key: 'proxy', header: 'Max proxy', align: 'right', render: (g) => g.maxProxyConnections },
          { key: 'mode', header: 'Pool mode', render: (g) => g.poolModeOverride ?? <span className="muted">inherit</span> },
          { key: 'ro', header: 'Read only', render: (g) => (g.readOnly ? <Badge tone="amber">read-only</Badge> : <span className="muted">no</span>) },
          { key: 'enabled', header: 'Enabled', render: (g) => <Toggle checked={g.enabled} onChange={(v) => update.mutate({ id: g.id, body: { enabled: v } }, { onError: (e) => toast.error(errorMessage(e)) })} label={<span className="sr-only">enabled</span>} /> },
          { key: 'note', header: 'Note', className: 'truncate', render: (g) => <span className="muted small">{g.note}</span> },
          { key: 'actions', header: '', render: (g) => <div className="row-actions"><button className="btn sm ghost icon" aria-label="Edit grant" onClick={() => setEditing(g)}><Pencil /></button><button className="btn sm ghost icon" aria-label="Delete grant" onClick={() => setConfirmDel(g)}><Trash2 /></button></div> },
        ]} />
      )}
      {editing && (
        <GrantDialog datasourceId={ds.id} grant={editing === 'new' ? null : editing} busy={create.isPending || update.isPending} onClose={() => setEditing(null)}
          onSave={(g) => editing === 'new'
            ? create.mutate(g, { onSuccess: () => { toast.success('Grant created'); setEditing(null); }, onError: (e) => toast.error(errorMessage(e)) })
            : update.mutate({ id: editing.id, body: g }, { onSuccess: () => { toast.success('Grant saved'); setEditing(null); }, onError: (e) => toast.error(errorMessage(e)) })} />
      )}
      <ConfirmDialog open={!!confirmDel} title="Revoke access" danger confirmLabel="Revoke" busy={remove.isPending} onClose={() => setConfirmDel(null)}
        message={<p>Revoke access of <strong>{confirmDel && appName(confirmDel.applicationId)}</strong> to <strong>{ds.name}</strong>? New connections will be refused.</p>}
        onConfirm={() => confirmDel && remove.mutate(confirmDel.id, { onSuccess: () => { toast.success('Grant revoked'); setConfirmDel(null); }, onError: (e) => toast.error(errorMessage(e)) })} />
    </Card>
  );
}

export function GrantDialog({ datasourceId, applicationId, grant, onSave, onClose, busy }: { datasourceId?: string; applicationId?: string; grant: AccessGrant | null; onSave: (g: AccessGrantInput) => void; onClose: () => void; busy: boolean }) {
  const apps = useApplications();
  const dss = useDatasources();
  const [g, setG] = useState<AccessGrantInput>(grant ?? { applicationId: applicationId ?? '', datasourceId: datasourceId ?? '', maxLogicalConnections: 20, maxProxyConnections: 0, poolModeOverride: null, readOnly: false, enabled: true, note: '' });
  return (
    <Modal open title={grant ? 'Edit access grant' : 'Grant access'} onClose={onClose}>
      <form onSubmit={(e) => { e.preventDefault(); onSave(g); }}>
        <div className="form-grid">
          {!applicationId && <Field label="Application" full={!!datasourceId}>{(id) => <select id={id} className="input" required disabled={!!grant} value={g.applicationId} onChange={(e) => setG({ ...g, applicationId: e.target.value })}><option value="">— choose —</option>{apps.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select>}</Field>}
          {!datasourceId && <Field label="Datasource" full={!!applicationId}>{(id) => <select id={id} className="input" required disabled={!!grant} value={g.datasourceId} onChange={(e) => setG({ ...g, datasourceId: e.target.value })}><option value="">— choose —</option>{dss.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select>}</Field>}
          <Field label="Max logical connections" help="Gateway sessions.">{(id) => <input id={id} className="input" type="number" min={0} value={g.maxLogicalConnections} onChange={(e) => setG({ ...g, maxLogicalConnections: Number(e.target.value) })} />}</Field>
          <Field label="Max proxy connections" help="Direct TCP connections through the proxy.">{(id) => <input id={id} className="input" type="number" min={0} value={g.maxProxyConnections} onChange={(e) => setG({ ...g, maxProxyConnections: Number(e.target.value) })} />}</Field>
          <Field label="Pool mode override">{(id) => <select id={id} className="input" value={g.poolModeOverride ?? ''} onChange={(e) => setG({ ...g, poolModeOverride: (e.target.value || null) as PoolMode | null })}><option value="">inherit</option><option>TRANSACTION</option><option>SESSION</option></select>}</Field>
          <Field label="Note">{(id) => <input id={id} className="input" value={g.note ?? ''} onChange={(e) => setG({ ...g, note: e.target.value })} />}</Field>
          <label className="checkbox"><input type="checkbox" checked={g.readOnly} onChange={(e) => setG({ ...g, readOnly: e.target.checked })} /> Read only</label>
          <label className="checkbox"><input type="checkbox" checked={g.enabled} onChange={(e) => setG({ ...g, enabled: e.target.checked })} /> Enabled</label>
        </div>
        <div className="form-actions"><button type="button" className="btn" onClick={onClose}>Cancel</button><button type="submit" className="btn primary" disabled={busy}>{busy && <span className="spinner" />} Save</button></div>
      </form>
    </Modal>
  );
}
