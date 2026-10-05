import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Search, Users } from 'lucide-react';
import { useDatabases, useSchemas, useTables, useTeams } from '../../api/hooks';
import type { Classification } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card } from '../../components/Card';
import { QueryBoundary, EmptyState } from '../../components/States';
import { DataTable } from '../../components/DataTable';
import { Badge, ClassificationBadge, EngineBadge, MigrationBadge, OwnerSourceBadge, TableKindBadge } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { links } from '../../lib/links';
import { compact } from '../../lib/format';
import { useDebounce } from '../../lib/useDebounce';
import { BulkOwnershipDialog } from '../databases/DatabaseDetail';

const SIZE = 25;
const CLASSES: Classification[] = ['PII', 'CONFIDENTIAL', 'INTERNAL', 'PUBLIC'];

export function TablesPage() {
  const [sp, setSp] = useSearchParams();
  const get = (k: string) => sp.get(k) ?? '';
  const [q, setQ] = useState(get('q'));
  const dq = useDebounce(q, 300);
  const page = Number(get('page') || 0);
  const filter = useMemo(() => ({ databaseId: get('databaseId') || undefined, schema: get('schema') || undefined, ownerTeamId: get('ownerTeamId') || undefined, unowned: get('unowned') === 'true' || undefined, classification: get('classification') || undefined, q: dq || undefined, page, size: SIZE }), [sp, dq, page]); // eslint-disable-line react-hooks/exhaustive-deps
  const set = (patch: Record<string, string | undefined>) => {
    const next = new URLSearchParams(sp);
    for (const [k, v] of Object.entries(patch)) { if (v) next.set(k, v); else next.delete(k); }
    if (!('page' in patch)) next.delete('page');
    setSp(next, { replace: true });
  };
  useEffect(() => { if (dq !== get('q')) set({ q: dq || undefined }); }, [dq]); // eslint-disable-line react-hooks/exhaustive-deps

  const tables = useTables(filter);
  const dbs = useDatabases();
  const teams = useTeams();
  const schemas = useSchemas(filter.databaseId ?? '');
  const [bulk, setBulk] = useState(false);
  const db = dbs.data?.find((d) => d.id === filter.databaseId);
  const teamName = (id: string | null) => teams.data?.find((t) => t.id === id)?.name;
  const dbName = (id: string) => dbs.data?.find((d) => d.id === id);

  return (
    <>
      <PageHeader title="Tables" subtitle="Catalogue of every table and view the collectors have seen, with ownership, classification and migration state."
        actions={db && filter.schema ? <button className="btn" onClick={() => setBulk(true)}><Users /> Bulk ownership for {filter.schema}</button> : undefined} />
      <div className="filters">
        <div className="field grow"><label htmlFor="tbl-q">Search</label><div className="search"><Search /><input id="tbl-q" className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="schema.table, description…" /></div></div>
        <div className="field"><label htmlFor="tbl-db">Database</label><select id="tbl-db" className="input" value={filter.databaseId ?? ''} onChange={(e) => set({ databaseId: e.target.value || undefined, schema: undefined })}><option value="">all</option>{dbs.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></div>
        <div className="field"><label htmlFor="tbl-schema">Schema</label>
          {filter.databaseId ? <select id="tbl-schema" className="input" value={filter.schema ?? ''} onChange={(e) => set({ schema: e.target.value || undefined })}><option value="">all</option>{schemas.data?.map((s) => <option key={s.name} value={s.name}>{s.name} ({s.tableCount})</option>)}</select>
            : <input id="tbl-schema" className="input" value={filter.schema ?? ''} onChange={(e) => set({ schema: e.target.value || undefined })} placeholder="SALES" />}
        </div>
        <div className="field"><label htmlFor="tbl-owner">Owner</label><select id="tbl-owner" className="input" value={filter.ownerTeamId ?? ''} onChange={(e) => set({ ownerTeamId: e.target.value || undefined, unowned: undefined })}><option value="">any</option>{teams.data?.map((t) => <option key={t.id} value={t.id}>{t.displayName}</option>)}</select></div>
        <div className="field"><label htmlFor="tbl-class">Classification</label><select id="tbl-class" className="input" value={filter.classification ?? ''} onChange={(e) => set({ classification: e.target.value || undefined })}><option value="">any</option>{CLASSES.map((c) => <option key={c}>{c}</option>)}</select></div>
        <label className="checkbox" style={{ paddingBottom: 8 }}><input type="checkbox" checked={!!filter.unowned} onChange={(e) => set({ unowned: e.target.checked ? 'true' : undefined, ownerTeamId: undefined })} /> Unowned only</label>
      </div>
      <Card tight footer={tables.data ? (
        <div className="row" style={{ width: '100%' }}>
          <span>{tables.data.total} tables · page {tables.data.page + 1} of {Math.max(1, Math.ceil(tables.data.total / SIZE))}</span>
          <span className="spacer" style={{ flex: 1 }} />
          <button className="btn sm" disabled={page <= 0} onClick={() => set({ page: String(page - 1) })}><ChevronLeft /> Previous</button>
          <button className="btn sm" disabled={(page + 1) * SIZE >= tables.data.total} onClick={() => set({ page: String(page + 1) })}>Next <ChevronRight /></button>
        </div>
      ) : undefined}>
        <QueryBoundary query={tables} isEmpty={(d) => d.items.length === 0} empty={<EmptyState title="No tables match" hint="Loosen the filters or run the dictionary collector on a database." />}>
          {(pageData) => (
            <div className={tables.isFetching ? 'chart-wrap refetching' : ''}>
              <DataTable rows={pageData.items} rowKey={(t) => t.id} rowLink={(t) => links.table(t.id)} columns={[
                { key: 'name', header: 'Table', sort: (t) => `${t.schema}.${t.name}`, render: (t) => <span className="row" style={{ gap: 6 }}><strong><Link to={links.table(t.id)}>{t.schema}.{t.name}</Link></strong><TableKindBadge kind={t.kind} />{t.discovered && <Badge tone="amber" title="Seen in telemetry but not yet in the dictionary">discovered</Badge>}</span> },
                { key: 'db', header: 'Database', render: (t) => { const d = dbName(t.databaseId); return d ? <span className="row" style={{ gap: 6 }}><Link to={links.database(d.id)}>{d.name}</Link><EngineBadge engine={d.engine} /></span> : t.databaseId; } },
                { key: 'owner', header: 'Owner', sort: (t) => teamName(t.ownerTeamId) ?? '', render: (t) => <span className="row" style={{ gap: 6 }}>{t.ownerTeamId ? <Link to={links.team(t.ownerTeamId)}>{teamName(t.ownerTeamId) ?? t.ownerTeamId}</Link> : null}<OwnerSourceBadge source={t.ownerSource} confirmed={t.ownerConfirmed} /></span> },
                { key: 'class', header: 'Classification', render: (t) => <ClassificationBadge value={t.classification} /> },
                { key: 'rows', header: 'Rows', align: 'right', sort: (t) => t.rowCountEstimate ?? -1, render: (t) => compact(t.rowCountEstimate) },
                { key: 'mig', header: 'Migration', render: (t) => (t.migration.state !== 'NOT_PLANNED' ? <MigrationBadge state={t.migration.state} /> : <span className="muted">—</span>) },
                { key: 'seen', header: 'Last seen', sort: (t) => t.lastSeenAt, render: (t) => <RelativeTime value={t.lastSeenAt} staleDays={30} /> },
              ]} />
            </div>
          )}
        </QueryBoundary>
      </Card>
      {bulk && db && filter.schema && <BulkOwnershipDialog db={db} schema={filter.schema} onClose={() => setBulk(false)} />}
    </>
  );
}
