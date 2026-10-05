import { useState } from 'react';
import type { Application, ApplicationInput, ApplicationKind, Runtime } from '../../api/types';
import { useTeams } from '../../api/hooks';
import { Field } from '../../components/Field';
import { ChipInput } from '../../components/ChipInput';

const DEFAULT: ApplicationInput = {
  name: '', displayName: '', teamId: null, kind: 'SERVICE', runtime: 'KUBERNETES', description: '', tags: [],
  identityRules: { cidrs: [], programNames: [], machinePatterns: [], serviceAliases: [], pgApplicationNames: [] },
};

export function ApplicationForm({ initial, onSubmit, busy, onCancel }: { initial?: Application; onSubmit: (v: ApplicationInput) => void; busy?: boolean; onCancel: () => void }) {
  const [v, setV] = useState<ApplicationInput>(initial ? { ...DEFAULT, ...initial } : DEFAULT);
  const teams = useTeams();
  const set = <K extends keyof ApplicationInput>(k: K, val: ApplicationInput[K]) => setV((s) => ({ ...s, [k]: val }));
  return (
    <form onSubmit={(e) => { e.preventDefault(); onSubmit({ ...v, identityRules: { ...v.identityRules, serviceAliases: v.identityRules.serviceAliases.length || !v.name ? v.identityRules.serviceAliases : [v.name] } }); }}>
      <div className="form-grid">
        <Field label="Name" help="Stable identifier; also the default service alias.">{(id) => <input id={id} className="input" required pattern="[a-z0-9][a-z0-9\-]*" value={v.name} onChange={(e) => set('name', e.target.value)} placeholder="orders-service" />}</Field>
        <Field label="Display name">{(id) => <input id={id} className="input" value={v.displayName} onChange={(e) => set('displayName', e.target.value)} />}</Field>
        <Field label="Team">{(id) => <select id={id} className="input" value={v.teamId ?? ''} onChange={(e) => set('teamId', e.target.value || null)}><option value="">— none —</option>{teams.data?.map((t) => <option key={t.id} value={t.id}>{t.displayName}</option>)}</select>}</Field>
        <Field label="Kind">{(id) => <select id={id} className="input" value={v.kind} onChange={(e) => set('kind', e.target.value as ApplicationKind)}>{['SERVICE', 'BATCH', 'UI', 'LEGACY', 'TOOL'].map((k) => <option key={k}>{k}</option>)}</select>}</Field>
        <Field label="Runtime">{(id) => <select id={id} className="input" value={v.runtime} onChange={(e) => set('runtime', e.target.value as Runtime)}>{['KUBERNETES', 'CLOUD_RUN', 'VM', 'OTHER'].map((k) => <option key={k}>{k}</option>)}</select>}</Field>
        <Field label="Tags" help="Routing rules can match on tags.">{(id) => <ChipInput id={id} value={v.tags} onChange={(t) => set('tags', t)} placeholder="tier:1, pilot" />}</Field>
        <Field label="Description" full>{(id) => <input id={id} className="input" value={v.description ?? ''} onChange={(e) => set('description', e.target.value)} />}</Field>
      </div>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn primary" disabled={busy}>{busy && <span className="spinner" />} {initial ? 'Save' : 'Create application'}</button>
      </div>
    </form>
  );
}
