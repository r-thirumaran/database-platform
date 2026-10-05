import { useState } from 'react';
import { useSyncedState } from '../../lib/useSyncedState';
import { Link, useParams } from 'react-router-dom';
import { CheckCircle2, GitBranch, Pencil } from 'lucide-react';
import { useApplications, useColumns, useDatabases, useTableMutations, useTableSummary, useTeams } from '../../api/hooks';
import type { Classification, Column, MigrationState, Table, TableSummary } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card, KV } from '../../components/Card';
import { Loading, ErrorState, EmptyState, QueryBoundary } from '../../components/States';
import { Badge, ClassificationBadge, Confidence, EngineBadge, KindBadge, MigrationBadge, OwnerSourceBadge, SourceBadge, TableKindBadge } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';
import { CopyId } from '../../components/CopyId';
import { DataTable } from '../../components/DataTable';
import { Field } from '../../components/Field';
import { ChipInput } from '../../components/ChipInput';
import { StatTile } from '../../components/StatTile';
import { compact, formatMs, formatNumber } from '../../lib/format';

const CLASSES: Classification[] = ['PII', 'CONFIDENTIAL', 'INTERNAL', 'PUBLIC'];

export function TableDetail() {
  const { id = '' } = useParams();
  const s = useTableSummary(id);
  if (s.isPending) return <Loading />;
  if (s.isError) return <ErrorState error={s.error} retry={() => s.refetch()} />;
  return <TableView s={s.data} />;
}

function TableView({ s }: { s: TableSummary }) {
  const t = s.table;
  const toast = useToast();
  const [edit, setEdit] = useState(false);
  const { update } = useTableMutations();
  const fq = `${t.schema}.${t.name}`;
  return (
    <>
      <PageHeader title={fq} crumb={fq}
        badges={<><EngineBadge engine={s.database?.engine} /><TableKindBadge kind={t.kind} /><ClassificationBadge value={t.classification} />{t.discovered && <Badge tone="amber">discovered</Badge>}</>}
        subtitle={<span className="row">{t.description || <span className="muted">No description</span>}<CopyId value={t.id} /></span>}
        actions={<><Link className="btn primary" to={links.impactTable(t.id)}><GitBranch /> Impact analysis</Link><Link className="btn" to={links.graph(`table:${t.id}`, 2)}>Open in graph</Link><button className="btn" onClick={() => setEdit(true)}><Pencil /> Edit metadata</button></>} />
      <div className="kpi-row" style={{ marginBottom: 16 }}>
        <StatTile label="Rows (estimate)" value={t.rowCountEstimate} />
        <StatTile label="Queries 24h" value={s.queryStats.count24h} sub={`${compact(s.queryStats.count7d)} in 7d`} />
        <StatTile label="Consumers" value={s.consumers.length} sub={`${new Set(s.consumers.map((c) => c.application.id)).size} applications · ${new Set(s.consumers.map((c) => c.team?.id).filter(Boolean)).size} teams`} />
        <StatTile label="Last activity" value={<RelativeTime value={s.queryStats.lastSeenAt ?? t.lastSeenAt} />} />
        <StatTile label="Last DDL" value={<RelativeTime value={t.lastDdlAt} />} />
      </div>
      <div className="grid cols-3">
        <OwnershipCard t={t} s={s} />
        <Card title="Location">
          <KV items={[
            ['Database', s.database ? <span className="row" style={{ gap: 6 }}><Link to={links.database(s.database.id)}>{s.database.name}</Link><EngineBadge engine={s.database.engine} /></span> : null],
            ['Schema', <Link to={links.tables({ databaseId: t.databaseId, schema: t.schema })}>{t.schema}</Link>],
            ['Kind', t.kind],
            ['Tags', t.tags.length ? <span className="badge-row">{t.tags.map((x) => <Badge key={x} outline>{x}</Badge>)}</span> : null],
            ['First seen', <RelativeTime value={t.firstSeenAt} />],
            ['Last seen by collector', <RelativeTime value={t.lastSeenAt} />],
          ]} />
        </Card>
        <MigrationCard t={t} />
        <Card className="span-2" title="Consumers" hint="direct and via routines, with source and confidence" tight>
          {s.consumers.length === 0 ? <EmptyState inline title="No consumer observed" hint="Relationships are derived from gateway telemetry, proxy correlation, collector samples and the audit trail." /> : (
            <DataTable compact rows={s.consumers} rowKey={(c) => `${c.application.id}-${c.kind}-${c.source ?? ''}-${c.viaRoutine?.id ?? c.viaView?.id ?? 'direct'}`} initialSort={{ key: 'q', dir: 'desc' }} columns={[
              { key: 'app', header: 'Application', render: (c) => <Link to={links.application(c.application.id)}>{c.application.name}</Link> },
              { key: 'team', header: 'Team', render: (c) => (c.team ? <Link to={links.team(c.team.id)} style={c.team.id !== t.ownerTeamId ? { color: 'var(--warning)' } : undefined} title={c.team.id !== t.ownerTeamId ? 'Cross-team access' : undefined}>{c.team.name}</Link> : <span className="muted">—</span>) },
              { key: 'kind', header: 'Access', render: (c) => <KindBadge kind={c.kind} /> },
              { key: 'via', header: 'Path', render: (c) => (c.viaRoutine ? <span className="row" style={{ gap: 4 }}><span className="muted small">via</span><Link to={links.routine(c.viaRoutine.id)}>{c.viaRoutine.name}</Link></span> : <span className="muted">direct</span>) },
              { key: 'src', header: 'Source', render: (c) => <span className="row" style={{ gap: 6 }}><SourceBadge source={c.source} /><Confidence value={c.confidence} />{c.confirmed && <Badge tone="green">confirmed</Badge>}</span> },
              { key: 'q', header: 'Queries', align: 'right', sort: (c) => c.queryCount, render: (c) => compact(c.queryCount) },
              { key: 'last', header: 'Last seen', sort: (c) => c.lastSeenAt, render: (c) => <RelativeTime value={c.lastSeenAt} staleDays={30} /> },
            ]} />
          )}
        </Card>
        <Card title="Database-side dependencies">
          <KV items={[
            ['Routines', s.routines.length ? <span className="inline-list">{s.routines.map((r) => <Link key={r.id} to={links.routine(r.id)} className="badge pink">{r.name}</Link>)}</span> : <span className="muted">none</span>],
            ['Triggers', s.triggers.length ? <span className="inline-list">{s.triggers.map((r) => <Link key={r.id} to={links.routine(r.id)} className="badge red">{r.name}</Link>)}</span> : <span className="muted">none</span>],
            ['Views', s.views.length ? <span className="inline-list">{s.views.map((v) => <Link key={v.id} to={links.table(v.id)} className="badge teal">{v.schema}.{v.name}</Link>)}</span> : <span className="muted">none</span>],
            ['FK → (references)', s.foreignKeysOut.length ? <span className="inline-list">{s.foreignKeysOut.map((x) => <Link key={x.id} to={links.table(x.id)} className="badge outline">{x.schema}.{x.name}</Link>)}</span> : <span className="muted">none</span>],
            ['FK ← (dependents)', s.foreignKeysIn.length ? <span className="inline-list">{s.foreignKeysIn.map((x) => <Link key={x.id} to={links.table(x.id)} className="badge outline">{x.schema}.{x.name}</Link>)}</span> : <span className="muted">none</span>],
          ]} />
        </Card>
        <ColumnsCard t={t} className="span-2" />
        <Card title="Top queries" hint="touching this table">
          {s.topQueries.length === 0 ? <EmptyState inline title="No queries recorded" /> : (
            <div className="stack" style={{ gap: 10 }}>
              {s.topQueries.slice(0, 6).map((q) => (
                <div key={`${q.sqlHash}-${q.applicationId}`}>
                  <div className="row between small"><span className="row" style={{ gap: 6 }}><Badge outline>{q.operation}</Badge>{q.applicationId ? <Link to={links.application(q.applicationId)}>{q.applicationName}</Link> : <span className="muted">unknown</span>}</span><span className="muted">{compact(q.count)} × {formatMs(q.avgDurationMs)} · p95 {formatMs(q.p95DurationMs)}{q.errors ? ` · ${q.errors} errors` : ''}</span></div>
                  <div className="sql clamp" title={q.sqlNormalized}>{q.sqlNormalized}</div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
      <Modal open={edit} title={`Edit ${fq}`} onClose={() => setEdit(false)}>
        <MetadataForm t={t} busy={update.isPending} onCancel={() => setEdit(false)} onSave={(body) => update.mutate({ id: t.id, body }, { onSuccess: () => { toast.success('Saved'); setEdit(false); }, onError: (e) => toast.error(errorMessage(e)) })} />
      </Modal>
    </>
  );
}

function OwnershipCard({ t, s }: { t: Table; s: TableSummary }) {
  const teams = useTeams();
  const apps = useApplications();
  const { ownership, update } = useTableMutations();
  const toast = useToast();
  const [teamId, setTeamId] = useSyncedState(t.ownerTeamId ?? '');
  const [producerId, setProducerId] = useSyncedState(t.producerApplicationId ?? '');
  const soleWriter = (() => { const writers = new Set(s.consumers.filter((c) => c.kind === 'WRITES').map((c) => c.application)); return writers.size === 1 ? [...writers][0] : null; })();
  return (
    <Card title="Ownership" hint={<OwnerSourceBadge source={t.ownerSource} confirmed={t.ownerConfirmed} />}>
      <div className="stack">
        <div>
          <div className="row between" style={{ marginBottom: 4 }}>
            <span className="small muted">Owner team</span>
            {t.ownerTeamId && !t.ownerConfirmed && <button className="btn sm" onClick={() => ownership.mutate({ id: t.id, teamId: t.ownerTeamId, confirmed: true }, { onSuccess: () => toast.success('Ownership confirmed'), onError: (e) => toast.error(errorMessage(e)) })}><CheckCircle2 /> Confirm</button>}
          </div>
          <div className="row">
            <select className="input sm" aria-label="Owner team" value={teamId} onChange={(e) => setTeamId(e.target.value)} style={{ flex: 1 }}>
              <option value="">— unowned —</option>
              {teams.data?.map((x) => <option key={x.id} value={x.id}>{x.displayName}</option>)}
            </select>
            <button className="btn sm primary" disabled={teamId === (t.ownerTeamId ?? '') || ownership.isPending} onClick={() => ownership.mutate({ id: t.id, teamId: teamId || null, confirmed: !!teamId }, { onSuccess: () => toast.success('Owner updated'), onError: (e) => toast.error(errorMessage(e)) })}>Assign</button>
          </div>
          {s.ownerTeam && <div className="small" style={{ marginTop: 4 }}><Link to={links.team(s.ownerTeam.id)}>{s.ownerTeam.displayName}</Link> · {s.ownerTeam.contacts.join(', ')}</div>}
        </div>
        <div>
          <div className="small muted" style={{ marginBottom: 4 }}>Producer application <span title="The application that authoritatively writes this table">(authoritative writer)</span></div>
          <div className="row">
            <select className="input sm" aria-label="Producer application" value={producerId} onChange={(e) => setProducerId(e.target.value)} style={{ flex: 1 }}>
              <option value="">— none —</option>
              {apps.data?.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
            <button className="btn sm primary" disabled={producerId === (t.producerApplicationId ?? '') || update.isPending} onClick={() => update.mutate({ id: t.id, body: { producerApplicationId: producerId || null } }, { onSuccess: () => toast.success('Producer updated'), onError: (e) => toast.error(errorMessage(e)) })}>Set</button>
          </div>
          {s.producer && <div className="small" style={{ marginTop: 4 }}><Link to={links.application(s.producer.id)}>{s.producer.name}</Link></div>}
          {!t.producerApplicationId && soleWriter && <div className="small muted" style={{ marginTop: 4 }}>Suggestion: <strong>{soleWriter.name}</strong> is the only writer observed.</div>}
        </div>
      </div>
    </Card>
  );
}

function MigrationCard({ t }: { t: Table }) {
  const dbs = useDatabases();
  const { update } = useTableMutations();
  const toast = useToast();
  const [m, setM] = useSyncedState(t.migration);
  const dirty = JSON.stringify(m) !== JSON.stringify(t.migration);
  return (
    <Card title="Migration" hint={<MigrationBadge state={t.migration.state} />}>
      <form onSubmit={(e) => { e.preventDefault(); update.mutate({ id: t.id, body: { migration: m } }, { onSuccess: () => toast.success('Migration plan saved'), onError: (err) => toast.error(errorMessage(err)) }); }}>
        <div className="form-grid">
          <Field label="State" full>{(id) => <select id={id} className="input sm" value={m.state} onChange={(e) => setM({ ...m, state: e.target.value as MigrationState })}>{['NOT_PLANNED', 'PLANNED', 'IN_PROGRESS', 'DONE'].map((s) => <option key={s}>{s}</option>)}</select>}</Field>
          <Field label="Target database" full>{(id) => <select id={id} className="input sm" value={m.targetDatabaseId ?? ''} onChange={(e) => setM({ ...m, targetDatabaseId: e.target.value || null })}><option value="">— none —</option>{dbs.data?.filter((d) => d.id !== t.databaseId).map((d) => <option key={d.id} value={d.id}>{d.name} ({d.engine})</option>)}</select>}</Field>
          <Field label="Target schema">{(id) => <input id={id} className="input sm" value={m.targetSchema ?? ''} onChange={(e) => setM({ ...m, targetSchema: e.target.value || null })} placeholder={t.schema.toLowerCase()} />}</Field>
          <Field label="Target name">{(id) => <input id={id} className="input sm" value={m.targetName ?? ''} onChange={(e) => setM({ ...m, targetName: e.target.value || null })} placeholder={t.name.toLowerCase()} />}</Field>
        </div>
        <div className="form-actions" style={{ marginTop: 10 }}><button type="submit" className="btn sm primary" disabled={!dirty || update.isPending}>Save plan</button></div>
      </form>
    </Card>
  );
}

function ColumnsCard({ t, className }: { t: Table; className?: string }) {
  const cols = useColumns(t.id);
  const { updateColumn } = useTableMutations();
  const toast = useToast();
  const [editing, setEditing] = useState<Column | null>(null);
  const [comment, setComment] = useState('');
  const [cls, setCls] = useState('');
  const open = (c: Column) => { setEditing(c); setComment(c.comment ?? ''); setCls(c.classification ?? ''); };
  const typeOf = (c: Column) => `${c.dataType}${c.length ? `(${c.length})` : c.precision ? `(${c.precision}${c.scale ? `,${c.scale}` : ''})` : ''}`;
  return (
    <Card className={className} title="Columns" hint={cols.data ? `${cols.data.length}` : undefined} tight>
      <QueryBoundary query={cols} empty={<EmptyState inline title="No columns collected" />}>
        {(rows) => (
          <DataTable compact rows={rows} rowKey={(c) => c.id} columns={[
            { key: 'pos', header: '#', align: 'right', render: (c) => c.position },
            { key: 'name', header: 'Column', render: (c) => <strong className="mono">{c.name}</strong> },
            { key: 'type', header: 'Type', className: 'mono', render: (c) => typeOf(c) },
            { key: 'null', header: 'Nullable', render: (c) => (c.nullable ? <span className="muted">yes</span> : <Badge outline>NOT NULL</Badge>) },
            { key: 'def', header: 'Default', className: 'mono', render: (c) => c.defaultValue ?? <span className="muted">—</span> },
            { key: 'class', header: 'Classification', render: (c) => <ClassificationBadge value={c.classification} /> },
            { key: 'comment', header: 'Comment', className: 'truncate', render: (c) => <span className="muted">{c.comment}</span> },
            { key: 'a', header: '', render: (c) => <div className="row-actions"><Link className="btn sm ghost" to={links.impactColumn(c.id)} title="Column impact"><GitBranch /> Impact</Link><button className="btn sm ghost icon" aria-label={`Edit column ${c.name}`} onClick={() => open(c)}><Pencil /></button></div> },
          ]} />
        )}
      </QueryBoundary>
      <Modal open={!!editing} title={`Column ${editing?.name}`} onClose={() => setEditing(null)}
        footer={<><button className="btn" onClick={() => setEditing(null)}>Cancel</button><button className="btn primary" disabled={updateColumn.isPending} onClick={() => editing && updateColumn.mutate({ tableId: t.id, columnId: editing.id, body: { comment: comment || null, classification: cls || null } }, { onSuccess: () => { toast.success('Column saved'); setEditing(null); }, onError: (e) => toast.error(errorMessage(e)) })}>Save</button></>}>
        <div className="form-grid">
          <Field label="Classification">{(id) => <select id={id} className="input" value={cls} onChange={(e) => setCls(e.target.value)}><option value="">— none —</option>{CLASSES.map((c) => <option key={c}>{c}</option>)}</select>}</Field>
          <Field label="Comment" full>{(id) => <input id={id} className="input" value={comment} onChange={(e) => setComment(e.target.value)} />}</Field>
        </div>
      </Modal>
    </Card>
  );
}

function MetadataForm({ t, onSave, onCancel, busy }: { t: Table; onSave: (b: { description: string; tags: string[]; classification: Classification | null }) => void; onCancel: () => void; busy: boolean }) {
  const [description, setDescription] = useState(t.description ?? '');
  const [tags, setTags] = useState(t.tags);
  const [cls, setCls] = useState(t.classification ?? '');
  return (
    <form onSubmit={(e) => { e.preventDefault(); onSave({ description, tags, classification: (cls || null) as Classification | null }); }}>
      <div className="form-grid">
        <Field label="Classification" full>{(id) => <select id={id} className="input" value={cls} onChange={(e) => setCls(e.target.value)}><option value="">— none —</option>{CLASSES.map((c) => <option key={c}>{c}</option>)}</select>}</Field>
        <Field label="Tags" full>{(id) => <ChipInput id={id} value={tags} onChange={setTags} placeholder="hot, gdpr…" />}</Field>
        <Field label="Description" full>{(id) => <textarea id={id} className="input" style={{ fontFamily: 'inherit' }} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
      </div>
      <div className="form-actions"><button type="button" className="btn" onClick={onCancel}>Cancel</button><button type="submit" className="btn primary" disabled={busy}>Save</button></div>
      <p className="muted small" style={{ marginTop: 8 }}>{formatNumber(t.rowCountEstimate)} rows estimated.</p>
    </form>
  );
}
