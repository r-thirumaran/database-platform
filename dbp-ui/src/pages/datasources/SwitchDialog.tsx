import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, ArrowRight, ShieldAlert } from 'lucide-react';
import type { Database, Datasource, DatasourceImpact } from '../../api/types';
import { useDatabases, useDatasourceImpact, useDatasourceMutations } from '../../api/hooks';
import { Modal } from '../../components/Modal';
import { Loading, ErrorState } from '../../components/States';
import { Badge, EngineBadge, SourceBadge } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { Field } from '../../components/Field';
import { RiskRing } from '../impact/RiskRing';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';
import { compact } from '../../lib/format';
import { DataTable } from '../../components/DataTable';

/**
 * Migration safety: before `POST /datasources/{id}/switch` the dialog loads `/impact/datasource/{id}`
 * and makes the operator look at every consumer, team, trigger and table that will be affected.
 */
export function SwitchDialog({ ds, current, onClose }: { ds: Datasource; current: Database | null; onClose: () => void }) {
  const dbs = useDatabases();
  const [targetId, setTargetId] = useState(ds.targetDatabaseId ?? '');
  const [note, setNote] = useState('');
  const [typed, setTyped] = useState('');
  const impact = useDatasourceImpact(ds.id);
  const { switch: doSwitch } = useDatasourceMutations(ds.id);
  const toast = useToast();
  const target = dbs.data?.find((d) => d.id === targetId);
  const candidates = useMemo(() => (dbs.data ?? []).filter((d) => d.id !== ds.currentDatabaseId), [dbs.data, ds.currentDatabaseId]);
  const divertedApps = new Set(ds.routingRules.filter((r) => r.enabled && r.applicationId).map((r) => r.applicationId));
  const canConfirm = !!target && typed.trim() === ds.name && !doSwitch.isPending;

  return (
    <Modal
      open
      wide
      title={<span className="row"><ShieldAlert size={18} style={{ color: 'var(--warning)' }} /> Switch {ds.name} to another database</span>}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn danger solid" disabled={!canConfirm}
            onClick={() => doSwitch.mutate({ databaseId: targetId, note: note || undefined }, { onSuccess: () => { toast.success(`${ds.name} now routes to ${target?.name}`); onClose(); }, onError: (e) => toast.error(errorMessage(e)) })}>
            {doSwitch.isPending && <span className="spinner" />} Switch now
          </button>
        </>
      }
    >
      <div className="stack">
        <div className="row" style={{ gap: 10, alignItems: 'center' }}>
          <span className="row" style={{ gap: 6 }}><strong>{current?.name ?? 'unknown'}</strong><EngineBadge engine={current?.engine} /></span>
          <ArrowRight size={16} className="muted" />
          <Field label="Target database">{(id) => (
            <select id={id} className="input" value={targetId} onChange={(e) => setTargetId(e.target.value)} style={{ minWidth: 260 }}>
              <option value="">— choose a database —</option>
              {candidates.map((d) => <option key={d.id} value={d.id}>{d.name} ({d.engine}){d.id === ds.targetDatabaseId ? ' · planned target' : ''}</option>)}
            </select>
          )}</Field>
        </div>
        {target && current && target.engine !== current.engine && (
          <div className="notice warning"><AlertTriangle /><div><strong>Engine change ({current.engine} → {target.engine}).</strong> Every consumer below will start executing its SQL against a different engine the moment the switch happens. Routines and triggers do not migrate automatically.</div></div>
        )}

        <h3>What this switch touches</h3>
        {impact.isPending && <Loading inline />}
        {impact.isError && <ErrorState inline error={impact.error} retry={() => impact.refetch()} />}
        {impact.data && <ImpactSummary impact={impact.data} divertedApps={divertedApps} />}

        <Field label={<>Type <code>{ds.name}</code> to confirm</>}>{(id) => <input id={id} className="input" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />}</Field>
        <Field label="Note for the migration log (optional)">{(id) => <input id={id} className="input" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Change ticket, runbook step…" />}</Field>
        <p className="muted small">The switch sets <code>currentDatabaseId</code>, bumps the config version so gateways and proxies re-resolve within seconds, and records a migration event. Applications with an enabled routing rule keep following their rule.</p>
      </div>
    </Modal>
  );
}

export function ImpactSummary({ impact, divertedApps }: { impact: DatasourceImpact; divertedApps: Set<string | null> }) {
  const apps = impact.applications.map((a) => ({ ...a, diverted: a.hasRoutingRule || divertedApps.has(a.application.id) }));
  return (
    <div className="stack">
      <div className="risk">
        <RiskRing score={impact.riskScore} />
        <div>
          <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, minmax(110px, 1fr))', gap: 8 }}>
            <Mini label="Applications" value={apps.length} />
            <Mini label="Teams" value={impact.teamsAffected.length} />
            <Mini label="Tables" value={impact.tables.length} />
            <Mini label="Triggers / routines" value={`${impact.triggers.length} / ${impact.routines.length}`} />
          </div>
          <div className="factor-list" style={{ marginTop: 8 }}>{impact.riskFactors.map((f) => <Badge key={f} outline>{f}</Badge>)}</div>
        </div>
      </div>
      <div className="grid cols-2">
        <div>
          <h3 style={{ marginBottom: 6 }}>Applications affected</h3>
          <DataTable compact rows={apps} rowKey={(a) => a.application.id} initialSort={{ key: 'q', dir: 'desc' }} columns={[
            { key: 'app', header: 'Application', render: (a) => <Link to={links.application(a.application.id)}>{a.application.name}</Link> },
            { key: 'team', header: 'Team', render: (a) => (a.team ? <Link to={links.team(a.team.id)}>{a.team.name}</Link> : <span className="muted">—</span>) },
            { key: 'q', header: 'Queries', align: 'right', sort: (a) => a.queryCount, render: (a) => compact(a.queryCount) },
            { key: 'last', header: 'Last seen', render: (a) => <RelativeTime value={a.lastSeenAt} staleDays={30} /> },
            { key: 'rule', header: '', render: (a) => (a.diverted ? <Badge tone="blue" title="Has an enabled routing rule; unaffected by the default route">routing rule</Badge> : null) },
          ]} />
        </div>
        <div>
          <h3 style={{ marginBottom: 6 }}>Teams to notify</h3>
          <div className="stack" style={{ gap: 6 }}>
            {impact.teamsAffected.map((t) => <div key={t.id} className="row between"><Link to={links.team(t.id)}>{t.displayName}</Link><span className="muted small">{t.contacts.join(', ')}</span></div>)}
            {impact.teamsAffected.length === 0 && <span className="muted">No consuming teams observed.</span>}
          </div>
          {(impact.triggers.length > 0 || impact.routines.length > 0) && (
            <>
              <h3 style={{ margin: '12px 0 6px' }}>Database-side logic in the path</h3>
              <div className="inline-list">
                {impact.triggers.map((r) => <Link key={r.id} to={links.routine(r.id)} className="badge red">TRIGGER {r.name}</Link>)}
                {impact.routines.map((r) => <Link key={r.id} to={links.routine(r.id)} className="badge pink">{r.kind ?? 'ROUTINE'} {r.name}</Link>)}
              </div>
            </>
          )}
        </div>
      </div>
      <div>
        <h3 style={{ marginBottom: 6 }}>Tables routed through this datasource</h3>
        <DataTable compact rows={impact.tables} rowKey={(t) => t.table.id} maxHeight={220} columns={[
          { key: 't', header: 'Table', render: (t) => <Link to={links.table(t.table.id)}>{t.table.schema}.{t.table.name}</Link> },
          { key: 'q', header: 'Queries (7d)', align: 'right', render: (t) => compact(t.queryCount), sort: (t) => t.queryCount },
          { key: 'c', header: 'Consumers', render: (t) => <span className="badge-row">{t.consumers.slice(0, 5).map((c, i) => <span key={i} className="row" style={{ gap: 4 }}><Badge outline>{c.application.name}</Badge><SourceBadge source={c.source} /></span>)}{t.consumers.length > 5 && <span className="muted small">+{t.consumers.length - 5}</span>}</span> },
        ]} />
      </div>
    </div>
  );
}

const Mini = ({ label, value }: { label: string; value: number | string }) => (
  <div className="stat-tile" style={{ padding: '8px 10px' }}>
    <div className="label">{label}</div>
    <div className="value" style={{ fontSize: 20 }}>{value}</div>
  </div>
);
