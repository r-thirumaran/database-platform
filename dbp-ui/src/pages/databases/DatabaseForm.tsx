import { useState } from 'react';
import type { Database, DatabaseInput, Engine } from '../../api/types';
import { useCredentials } from '../../api/hooks';
import { Field } from '../../components/Field';
import { ChipInput } from '../../components/ChipInput';

const DEFAULT: DatabaseInput = {
  name: '', engine: 'POSTGRES', host: '', port: 5432, serviceName: '', credentialId: null, maxPhysicalConnections: 50, jdbcProperties: {},
  collector: { enabled: true, dictionaryIntervalSeconds: 3600, runtimeIntervalSeconds: 15, schemas: [], auditTrail: false }, description: '', tags: [],
};
const DEFAULT_PORT: Record<Engine, number> = { ORACLE: 1521, POSTGRES: 5432, MSSQL: 1433 };

export function DatabaseForm({ initial, onSubmit, busy, onCancel }: { initial?: Database; onSubmit: (v: DatabaseInput) => void; busy?: boolean; onCancel: () => void }) {
  const [v, setV] = useState<DatabaseInput>(initial ? { ...DEFAULT, ...initial } : DEFAULT);
  const [props, setProps] = useState(Object.entries(v.jdbcProperties).map(([k, val]) => `${k}=${val}`).join('\n'));
  const creds = useCredentials();
  const set = <K extends keyof DatabaseInput>(k: K, val: DatabaseInput[K]) => setV((s) => ({ ...s, [k]: val }));
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const jdbcProperties: Record<string, string> = {};
    props.split('\n').map((l) => l.trim()).filter(Boolean).forEach((l) => { const i = l.indexOf('='); if (i > 0) jdbcProperties[l.slice(0, i).trim()] = l.slice(i + 1).trim(); });
    onSubmit({ ...v, jdbcProperties });
  };
  return (
    <form onSubmit={submit}>
      <div className="form-grid">
        <Field label="Name">{(id) => <input id={id} className="input" required value={v.name} onChange={(e) => set('name', e.target.value)} placeholder="sales-postgres" />}</Field>
        <Field label="Engine">{(id) => (
          <select id={id} className="input" value={v.engine} onChange={(e) => { const eng = e.target.value as Engine; set('engine', eng); if (!initial) set('port', DEFAULT_PORT[eng]); }}>
            <option>ORACLE</option><option>POSTGRES</option><option>MSSQL</option>
          </select>
        )}</Field>
        <Field label="Host">{(id) => <input id={id} className="input" required value={v.host} onChange={(e) => set('host', e.target.value)} />}</Field>
        <Field label="Port">{(id) => <input id={id} className="input" type="number" min={1} max={65535} required value={v.port} onChange={(e) => set('port', Number(e.target.value))} />}</Field>
        <Field label={v.engine === 'ORACLE' ? 'Service name' : 'Database name'}>{(id) => <input id={id} className="input" required value={v.serviceName} onChange={(e) => set('serviceName', e.target.value)} />}</Field>
        <Field label="Platform credential" help="Used by gateway pools and collectors.">{(id) => (
          <select id={id} className="input" value={v.credentialId ?? ''} onChange={(e) => set('credentialId', e.target.value || null)}>
            <option value="">— none —</option>
            {creds.data?.map((c) => <option key={c.id} value={c.id}>{c.name} ({c.username})</option>)}
          </select>
        )}</Field>
        <Field label="Max physical connections">{(id) => <input id={id} className="input" type="number" min={1} value={v.maxPhysicalConnections} onChange={(e) => set('maxPhysicalConnections', Number(e.target.value))} />}</Field>
        <Field label="Tags">{(id) => <ChipInput id={id} value={v.tags} onChange={(t) => set('tags', t)} placeholder="prod, legacy…" />}</Field>
        <Field label="JDBC properties" help="One key=value per line." full>{(id) => <textarea id={id} className="input" value={props} onChange={(e) => setProps(e.target.value)} />}</Field>
        <Field label="Description" full>{(id) => <input id={id} className="input" value={v.description ?? ''} onChange={(e) => set('description', e.target.value)} />}</Field>
        <fieldset className="full" style={{ border: '1px solid var(--border)', borderRadius: 6, padding: '10px 14px' }}>
          <legend className="small muted">Collector</legend>
          <div className="form-grid">
            <label className="checkbox"><input type="checkbox" checked={v.collector.enabled} onChange={(e) => set('collector', { ...v.collector, enabled: e.target.checked })} /> Enabled</label>
            <label className="checkbox"><input type="checkbox" checked={v.collector.auditTrail} onChange={(e) => set('collector', { ...v.collector, auditTrail: e.target.checked })} /> Read unified audit trail</label>
            <Field label="Dictionary interval (s)">{(id) => <input id={id} className="input" type="number" min={60} value={v.collector.dictionaryIntervalSeconds} onChange={(e) => set('collector', { ...v.collector, dictionaryIntervalSeconds: Number(e.target.value) })} />}</Field>
            <Field label="Runtime interval (s)">{(id) => <input id={id} className="input" type="number" min={1} value={v.collector.runtimeIntervalSeconds} onChange={(e) => set('collector', { ...v.collector, runtimeIntervalSeconds: Number(e.target.value) })} />}</Field>
            <Field label="Schemas to crawl" full>{(id) => <ChipInput id={id} value={v.collector.schemas} onChange={(s) => set('collector', { ...v.collector, schemas: s })} placeholder="SALES, INVENTORY" />}</Field>
          </div>
        </fieldset>
      </div>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn primary" disabled={busy}>{busy && <span className="spinner" />} {initial ? 'Save' : 'Create database'}</button>
      </div>
    </form>
  );
}
