import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ChevronDown, ChevronRight, Folder, Pencil, Play, Plug, Table2, Trash2, Users, Workflow } from 'lucide-react';
import { useAllTables, useCollectorStatus, useDatabase, useDatabaseMutations, useDatasources, useRoutines, useSchemas, useTableMutations, useTeams } from '../../api/hooks';
import type { Database, SchemaInfo, TestConnectionResult } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card, KV } from '../../components/Card';
import { QueryBoundary, Loading, ErrorState } from '../../components/States';
import { Badge, EngineBadge, RoutineKindBadge, TableKindBadge, OwnerSourceBadge } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { ConfirmDialog, Modal } from '../../components/Modal';
import { DatabaseForm } from './DatabaseForm';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';
import { CopyId } from '../../components/CopyId';
import { formatNumber } from '../../lib/format';
import { Field } from '../../components/Field';

export function DatabaseDetail() {
  const { id = '' } = useParams();
  const db = useDatabase(id);
  if (db.isPending) return <Loading />;
  if (db.isError) return <ErrorState error={db.error} retry={() => db.refetch()} />;
  return <DatabaseView db={db.data} />;
}

function DatabaseView({ db }: { db: Database }) {
  const navigate = useNavigate();
  const toast = useToast();
  const status = useCollectorStatus(db.id);
  const schemas = useSchemas(db.id);
  const dss = useDatasources();
  const { update, remove, testConnection, collect } = useDatabaseMutations();
  const [edit, setEdit] = useState(false);
  const [del, setDel] = useState(false);
  const [test, setTest] = useState<TestConnectionResult | null>(null);

  const runTest = () => testConnection.mutate(db.id, { onSuccess: setTest, onError: (e) => setTest({ ok: false, message: errorMessage(e) }) });
  const runCollector = (what: 'DICTIONARY' | 'RUNTIME' | 'AUDIT') =>
    collect.mutate({ id: db.id, what }, { onSuccess: () => toast.success(`${what.toLowerCase()} collection started`), onError: (e) => toast.error(errorMessage(e)) });

  return (
    <>
      <PageHeader
        title={db.name}
        badges={<EngineBadge engine={db.engine} lg />}
        subtitle={<span className="row"><code>{db.host}:{db.port}/{db.serviceName}</code><CopyId value={db.id} /></span>}
        actions={
          <>
            <button className="btn" onClick={runTest} disabled={testConnection.isPending}>{testConnection.isPending ? <span className="spinner" /> : <Plug />} Test connection</button>
            <div className="btn-group">
              <button className="btn" onClick={() => runCollector('DICTIONARY')} disabled={collect.isPending}><Play /> Run dictionary</button>
              <button className="btn" onClick={() => runCollector('RUNTIME')} disabled={collect.isPending}><Play /> Run runtime</button>
              {db.collector.auditTrail && <button className="btn" onClick={() => runCollector('AUDIT')} disabled={collect.isPending}><Play /> Run audit</button>}
            </div>
            <button className="btn" onClick={() => setEdit(true)}><Pencil /> Edit</button>
            <button className="btn danger" onClick={() => setDel(true)}><Trash2 /> Delete</button>
          </>
        }
      />
      {test && (
        <div className={`notice ${test.ok ? 'success' : 'danger'}`} role="status" style={{ marginBottom: 16 }}>
          <Plug />
          <div>
            {test.ok ? <><strong>Connected.</strong> {test.productName} {test.productVersion} · {test.latencyMs} ms</> : <><strong>Connection failed.</strong> {test.message}</>}
          </div>
          <button className="btn sm ghost" style={{ marginLeft: 'auto' }} onClick={() => setTest(null)}>Dismiss</button>
        </div>
      )}
      <div className="grid cols-3">
        <Card title="Details">
          <KV items={[
            ['Description', db.description],
            ['Engine', <EngineBadge engine={db.engine} />],
            ['Credential', db.credentialId ? <Link to={links.admin()}>{db.credentialId}</Link> : null],
            ['Max physical connections', db.maxPhysicalConnections],
            ['JDBC properties', Object.keys(db.jdbcProperties).length ? <code className="small">{Object.entries(db.jdbcProperties).map(([k, v]) => `${k}=${v}`).join(' ')}</code> : null],
            ['Tags', db.tags.length ? <span className="badge-row">{db.tags.map((t) => <Badge key={t} outline>{t}</Badge>)}</span> : null],
            ['Datasources', (() => { const list = dss.data?.filter((s) => s.currentDatabaseId === db.id || s.targetDatabaseId === db.id) ?? []; return list.length ? <span className="badge-row">{list.map((s) => <Link key={s.id} to={links.datasource(s.id)} className="badge outline">{s.name}{s.targetDatabaseId === db.id ? ' (target)' : ''}</Link>)}</span> : null; })()],
            ['Created', <RelativeTime value={db.createdAt} />],
            ['Updated', <RelativeTime value={db.updatedAt} />],
          ]} />
        </Card>
        <Card title="Collector" hint={db.collector.enabled ? 'enabled' : 'disabled'}>
          <KV items={[
            ['Schemas', db.collector.schemas.length ? db.collector.schemas.join(', ') : 'all'],
            ['Dictionary interval', `${db.collector.dictionaryIntervalSeconds}s`],
            ['Runtime interval', `${db.collector.runtimeIntervalSeconds}s`],
            ['Audit trail', db.collector.auditTrail ? 'yes' : 'no'],
          ]} />
          <hr style={{ border: 0, borderTop: '1px solid var(--border)', margin: '12px 0' }} />
          <QueryBoundary query={status} isEmpty={() => false}>
            {(s) => (
              <KV items={[
                ['Last dictionary run', <RelativeTime value={s.lastDictionaryRun} />],
                ['Last runtime run', <RelativeTime value={s.lastRuntimeRun} />],
                ['Tables seen', formatNumber(s.tablesSeen)],
                ['Last error', s.lastError ? <span style={{ color: 'var(--danger)' }}>{s.lastError}</span> : <Badge tone="green">none</Badge>],
              ]} />
            )}
          </QueryBoundary>
        </Card>
        <Card title="Schemas" hint="tables & routines">
          <QueryBoundary query={schemas} empty={<p className="muted">No schemas crawled yet. Run the dictionary collector.</p>}>
            {(rows) => <SchemaTree db={db} schemas={rows} />}
          </QueryBoundary>
        </Card>
      </div>

      <Modal open={edit} title={`Edit ${db.name}`} onClose={() => setEdit(false)} wide>
        <DatabaseForm initial={db} busy={update.isPending} onCancel={() => setEdit(false)} onSubmit={(v) => update.mutate({ id: db.id, body: v }, { onSuccess: () => { toast.success('Saved'); setEdit(false); }, onError: (e) => toast.error(errorMessage(e)) })} />
      </Modal>
      <ConfirmDialog open={del} title="Delete database" danger confirmLabel="Delete" busy={remove.isPending} onClose={() => setDel(false)}
        message={<p>Delete <strong>{db.name}</strong>? Datasources routed to it will stop resolving. Catalogue metadata collected from it is kept until the next crawl.</p>}
        onConfirm={() => remove.mutate(db.id, { onSuccess: () => { toast.success('Database deleted'); navigate(links.databases()); }, onError: (e) => toast.error(errorMessage(e)) })} />
    </>
  );
}

function SchemaTree({ db, schemas }: { db: Database; schemas: SchemaInfo[] }) {
  const [open, setOpen] = useState<Record<string, boolean>>(() => (schemas.length === 1 ? { [schemas[0].name]: true } : {}));
  const [bulk, setBulk] = useState<string | null>(null);
  return (
    <>
      <ul className="tree">
        {schemas.map((s) => (
          <li key={s.name}>
            <button className="node" onClick={() => setOpen((o) => ({ ...o, [s.name]: !o[s.name] }))} aria-expanded={!!open[s.name]}>
              {open[s.name] ? <ChevronDown /> : <ChevronRight />}
              <Folder />
              <strong>{s.name}</strong>
              <span className="count">{s.tableCount} tables · {s.routineCount} routines</span>
            </button>
            {open[s.name] && <SchemaChildren db={db} schema={s.name} onBulk={() => setBulk(s.name)} />}
          </li>
        ))}
      </ul>
      {bulk && <BulkOwnershipDialog db={db} schema={bulk} onClose={() => setBulk(null)} />}
    </>
  );
}

function SchemaChildren({ db, schema, onBulk }: { db: Database; schema: string; onBulk: () => void }) {
  const tables = useAllTables({ databaseId: db.id, schema });
  const routines = useRoutines({ databaseId: db.id, schema });
  return (
    <ul>
      <li>
        <div className="row between" style={{ padding: '2px 6px' }}>
          <span className="muted small">Tables</span>
          <span className="row" style={{ gap: 6 }}>
            <Link to={links.tables({ databaseId: db.id, schema })} className="btn sm ghost">Open in catalogue</Link>
            <button className="btn sm" onClick={onBulk}><Users /> Bulk ownership</button>
          </span>
        </div>
      </li>
      {tables.isPending && <li><Loading inline rows={2} /></li>}
      {tables.isError && <li><ErrorState inline error={tables.error} /></li>}
      {tables.data?.map((t) => (
        <li key={t.id}>
          <Link to={links.table(t.id)} className="node" style={{ color: 'inherit' }}>
            <Table2 />
            {t.name}
            <TableKindBadge kind={t.kind} />
            <span className="count"><OwnerSourceBadge source={t.ownerSource} confirmed={t.ownerConfirmed} /></span>
          </Link>
        </li>
      ))}
      {tables.data?.length === 0 && <li className="muted small" style={{ padding: '2px 6px' }}>No tables</li>}
      <li><div className="muted small" style={{ padding: '6px 6px 2px' }}>Routines</div></li>
      {routines.data?.map((r) => (
        <li key={r.id}>
          <Link to={links.routine(r.id)} className="node" style={{ color: 'inherit' }}>
            <Workflow />
            {r.name}
            <span className="count"><RoutineKindBadge kind={r.kind} /></span>
          </Link>
        </li>
      ))}
      {routines.data?.length === 0 && <li className="muted small" style={{ padding: '2px 6px' }}>No routines</li>}
    </ul>
  );
}

export function BulkOwnershipDialog({ db, schema, onClose }: { db: Database; schema: string; onClose: () => void }) {
  const teams = useTeams();
  const { bulkOwnership } = useTableMutations();
  const [teamId, setTeamId] = useState('');
  const toast = useToast();
  return (
    <Modal
      open
      title={`Assign owner for every table in ${schema}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={!teamId || bulkOwnership.isPending}
            onClick={() => bulkOwnership.mutate({ databaseId: db.id, schema, teamId }, { onSuccess: (r) => { toast.success(`${r?.updated ?? 'All'} tables now owned by the selected team`); onClose(); }, onError: (e) => toast.error(errorMessage(e)) })}>
            {bulkOwnership.isPending && <span className="spinner" />} Assign
          </button>
        </>
      }
    >
      <p className="muted">Writes a DECLARED, confirmed owner on every table of <strong>{db.name}</strong> / <strong>{schema}</strong>. Existing owners are overwritten.</p>
      <Field label="Owner team">{(id) => (
        <select id={id} className="input" value={teamId} onChange={(e) => setTeamId(e.target.value)}>
          <option value="">— choose a team —</option>
          {teams.data?.map((t) => <option key={t.id} value={t.id}>{t.displayName} ({t.name})</option>)}
        </select>
      )}</Field>
    </Modal>
  );
}
