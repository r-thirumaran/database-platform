import { useState } from 'react';
import type { Datasource, DatasourceInput, DatasourceState } from '../../api/types';
import { useDatabases, useTeams } from '../../api/hooks';
import { Field } from '../../components/Field';
import { ChipInput } from '../../components/ChipInput';

const DEFAULT: DatasourceInput = {
  name: '', displayName: '', ownerTeamId: null, state: 'ACTIVE', currentDatabaseId: '', targetDatabaseId: null,
  poolPolicy: { mode: 'TRANSACTION', maxConnections: 20, minIdle: 1, connectionTimeoutMs: 10000, idleTimeoutMs: 600000, maxLifetimeMs: 1800000, statementTimeoutSeconds: 0, validationQuery: null },
  description: '', tags: [],
};

export function DatasourceForm({ initial, onSubmit, busy, onCancel }: { initial?: Datasource; onSubmit: (v: DatasourceInput) => void; busy?: boolean; onCancel: () => void }) {
  const [v, setV] = useState<DatasourceInput>(initial ? { ...DEFAULT, ...initial } : DEFAULT);
  const teams = useTeams();
  const dbs = useDatabases();
  const set = <K extends keyof DatasourceInput>(k: K, val: DatasourceInput[K]) => setV((s) => ({ ...s, [k]: val }));
  return (
    <form onSubmit={(e) => { e.preventDefault(); onSubmit(v); }}>
      <div className="form-grid">
        <Field label="Name" help="Logical name used in jdbc:dbp://…/<name> and as proxy service alias.">{(id) => <input id={id} className="input" required pattern="[a-z0-9][a-z0-9\-]*" value={v.name} onChange={(e) => set('name', e.target.value)} placeholder="sales" />}</Field>
        <Field label="Display name">{(id) => <input id={id} className="input" value={v.displayName} onChange={(e) => set('displayName', e.target.value)} />}</Field>
        <Field label="Owner team">{(id) => (
          <select id={id} className="input" value={v.ownerTeamId ?? ''} onChange={(e) => set('ownerTeamId', e.target.value || null)}>
            <option value="">— none —</option>
            {teams.data?.map((t) => <option key={t.id} value={t.id}>{t.displayName}</option>)}
          </select>
        )}</Field>
        <Field label="State">{(id) => (
          <select id={id} className="input" value={v.state} onChange={(e) => set('state', e.target.value as DatasourceState)}>
            <option>ACTIVE</option><option>MIGRATING</option><option>RETIRED</option>
          </select>
        )}</Field>
        <Field label="Current database">{(id) => (
          <select id={id} className="input" required value={v.currentDatabaseId} onChange={(e) => set('currentDatabaseId', e.target.value)}>
            <option value="">— choose —</option>
            {dbs.data?.map((d) => <option key={d.id} value={d.id}>{d.name} ({d.engine})</option>)}
          </select>
        )}</Field>
        <Field label="Target database" help="Set while migrating; routing rules can divert applications to it.">{(id) => (
          <select id={id} className="input" value={v.targetDatabaseId ?? ''} onChange={(e) => set('targetDatabaseId', e.target.value || null)}>
            <option value="">— none —</option>
            {dbs.data?.filter((d) => d.id !== v.currentDatabaseId).map((d) => <option key={d.id} value={d.id}>{d.name} ({d.engine})</option>)}
          </select>
        )}</Field>
        <Field label="Tags">{(id) => <ChipInput id={id} value={v.tags} onChange={(t) => set('tags', t)} placeholder="domain:sales" />}</Field>
        <Field label="Description" full>{(id) => <input id={id} className="input" value={v.description ?? ''} onChange={(e) => set('description', e.target.value)} />}</Field>
      </div>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn primary" disabled={busy}>{busy && <span className="spinner" />} {initial ? 'Save' : 'Create datasource'}</button>
      </div>
    </form>
  );
}
