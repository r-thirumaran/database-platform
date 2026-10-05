import { useRef, useState } from 'react';
import { Download, KeyRound, Plus, RefreshCw, RotateCw, Trash2, Upload, Database as DbIcon, AlertTriangle } from 'lucide-react';
import { useAdminMutations, useComponents, useCredentialMutations, useCredentials } from '../../api/hooks';
import type { Credential, CredentialInput, CredentialProvider, ExportDocument } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card, KV } from '../../components/Card';
import { QueryBoundary, EmptyState } from '../../components/States';
import { DataTable } from '../../components/DataTable';
import { Badge, HealthBadge } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { ConfirmDialog, Modal } from '../../components/Modal';
import { Field } from '../../components/Field';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { fileDownload, formatNumber } from '../../lib/format';
import { CopyId } from '../../components/CopyId';

const PROVIDERS: Array<{ id: CredentialProvider; label: string; refLabel: string | null; hint: string }> = [
  { id: 'INLINE', label: 'Inline (encrypted at rest)', refLabel: null, hint: 'Secret is stored encrypted with DBP_MASTER_KEY. Never returned by the API.' },
  { id: 'ENV', label: 'Environment variable', refLabel: 'Variable name', hint: 'Read from the control plane process environment.' },
  { id: 'FILE', label: 'File', refLabel: 'File path', hint: 'Read from a mounted file (Kubernetes secret, Docker secret).' },
  { id: 'VAULT', label: 'Vault', refLabel: 'Vault path', hint: 'Looked up through the configured Vault connection.' },
  { id: 'GCP_SECRET_MANAGER', label: 'GCP Secret Manager', refLabel: 'Secret resource name', hint: 'projects/…/secrets/…/versions/latest' },
  { id: 'AWS_SECRETS_MANAGER', label: 'AWS Secrets Manager', refLabel: 'Secret ARN or name', hint: '' },
];

export function AdminPage() {
  return (
    <>
      <PageHeader title="Admin" subtitle="Credentials used by the platform itself, running components, and configuration import/export." />
      <div className="grid cols-3">
        <CredentialsCard className="span-2" />
        <ComponentsCard />
        <ImportExportCard className="span-2" />
        <SeedCard />
      </div>
    </>
  );
}

function CredentialsCard({ className }: { className?: string }) {
  const creds = useCredentials();
  const { create, update, remove, rotate } = useCredentialMutations();
  const toast = useToast();
  const [editing, setEditing] = useState<Credential | 'new' | null>(null);
  const [rotating, setRotating] = useState<Credential | null>(null);
  const [deleting, setDeleting] = useState<Credential | null>(null);
  const [secret, setSecret] = useState('');
  return (
    <Card className={className} title="Credentials" hint="secret material is never shown" actions={<button className="btn sm" onClick={() => setEditing('new')}><Plus /> Add credential</button>} tight>
      <QueryBoundary query={creds} empty={<EmptyState inline title="No credentials" hint="Databases need a platform credential for pools and collectors." />}>
        {(rows) => (
          <DataTable compact rows={rows} rowKey={(c) => c.id} columns={[
            { key: 'name', header: 'Name', render: (c) => <><strong>{c.name}</strong><div className="muted small">{c.description}</div></> },
            { key: 'user', header: 'Username', className: 'mono', render: (c) => c.username },
            { key: 'prov', header: 'Provider', render: (c) => <><Badge outline>{c.provider}</Badge>{c.ref && <div className="muted small mono">{c.ref}</div>}</> },
            { key: 'ver', header: 'Version', align: 'right', render: (c) => c.version ?? 1 },
            { key: 'rot', header: 'Rotated', render: (c) => <RelativeTime value={c.rotatedAt} /> },
            { key: 'id', header: 'Id', render: (c) => <CopyId value={c.id} /> },
            { key: 'a', header: '', render: (c) => <div className="row-actions"><button className="btn sm" onClick={() => { setSecret(''); setRotating(c); }}><RotateCw /> Rotate</button><button className="btn sm ghost" onClick={() => setEditing(c)}>Edit</button><button className="btn sm ghost icon" aria-label="Delete credential" onClick={() => setDeleting(c)}><Trash2 /></button></div> },
          ]} />
        )}
      </QueryBoundary>
      {editing && <CredentialDialog cred={editing === 'new' ? null : editing} busy={create.isPending || update.isPending} onClose={() => setEditing(null)}
        onSave={(v) => editing === 'new' ? create.mutate(v, { onSuccess: () => { toast.success('Credential created'); setEditing(null); }, onError: (e) => toast.error(errorMessage(e)) }) : update.mutate({ id: editing.id, body: v }, { onSuccess: () => { toast.success('Credential saved'); setEditing(null); }, onError: (e) => toast.error(errorMessage(e)) })} />}
      <Modal open={!!rotating} title={`Rotate ${rotating?.name}`} onClose={() => setRotating(null)}
        footer={<><button className="btn" onClick={() => setRotating(null)}>Cancel</button><button className="btn primary" disabled={rotate.isPending || (rotating?.provider === 'INLINE' && !secret)} onClick={() => rotating && rotate.mutate({ id: rotating.id, secret: rotating.provider === 'INLINE' ? secret : undefined }, { onSuccess: () => { toast.success('Credential rotated; gateways will drain old connections'); setRotating(null); }, onError: (e) => toast.error(errorMessage(e)) })}>{rotate.isPending && <span className="spinner" />} Rotate</button></>}>
        {rotating?.provider === 'INLINE' ? (
          <Field label="New secret">{(id) => <input id={id} className="input" type="password" autoComplete="new-password" value={secret} onChange={(e) => setSecret(e.target.value)} />}</Field>
        ) : (
          <p>The control plane re-reads the secret from <Badge outline>{rotating?.provider}</Badge> <code>{rotating?.ref}</code>, bumps the version and tells gateways to drain physical connections opened with the old one.</p>
        )}
      </Modal>
      <ConfirmDialog open={!!deleting} title="Delete credential" danger confirmLabel="Delete" busy={remove.isPending} onClose={() => setDeleting(null)}
        message={<p>Delete <strong>{deleting?.name}</strong>? Databases referencing it will fail to open new pool connections.</p>}
        onConfirm={() => deleting && remove.mutate(deleting.id, { onSuccess: () => { toast.success('Credential deleted'); setDeleting(null); }, onError: (e) => toast.error(errorMessage(e)) })} />
    </Card>
  );
}

function CredentialDialog({ cred, onSave, onClose, busy }: { cred: Credential | null; onSave: (v: CredentialInput) => void; onClose: () => void; busy: boolean }) {
  const [v, setV] = useState<CredentialInput>(cred ? { name: cred.name, username: cred.username, provider: cred.provider, ref: cred.ref, description: cred.description ?? '' } : { name: '', username: '', provider: 'ENV', ref: '', description: '' });
  const [secret, setSecret] = useState('');
  const p = PROVIDERS.find((x) => x.id === v.provider)!;
  return (
    <Modal open title={cred ? `Edit ${cred.name}` : 'Add credential'} onClose={onClose}>
      <form onSubmit={(e) => { e.preventDefault(); onSave({ ...v, ref: v.provider === 'INLINE' ? null : v.ref, ...(v.provider === 'INLINE' && secret ? { secret } : {}) }); }}>
        <div className="form-grid">
          <Field label="Name">{(id) => <input id={id} className="input" required value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} placeholder="sales-postgres-platform" />}</Field>
          <Field label="Username">{(id) => <input id={id} className="input" required value={v.username} onChange={(e) => setV({ ...v, username: e.target.value })} placeholder="dbp_platform" />}</Field>
          <Field label="Provider" full help={p.hint}>{(id) => <select id={id} className="input" value={v.provider} onChange={(e) => setV({ ...v, provider: e.target.value as CredentialProvider })}>{PROVIDERS.map((x) => <option key={x.id} value={x.id}>{x.label}</option>)}</select>}</Field>
          {p.refLabel ? (
            <Field label={p.refLabel} full>{(id) => <input id={id} className="input mono" required value={v.ref ?? ''} onChange={(e) => setV({ ...v, ref: e.target.value })} />}</Field>
          ) : (
            <Field label={cred ? 'New secret (leave empty to keep)' : 'Secret'} full>{(id) => <input id={id} className="input" type="password" autoComplete="new-password" required={!cred} value={secret} onChange={(e) => setSecret(e.target.value)} />}</Field>
          )}
          <Field label="Description" full>{(id) => <input id={id} className="input" value={v.description ?? ''} onChange={(e) => setV({ ...v, description: e.target.value })} />}</Field>
        </div>
        <div className="form-actions"><button type="button" className="btn" onClick={onClose}>Cancel</button><button type="submit" className="btn primary" disabled={busy}>{busy && <span className="spinner" />} <KeyRound /> Save</button></div>
      </form>
    </Modal>
  );
}

function ComponentsCard() {
  const comps = useComponents();
  return (
    <Card title="Components" hint="gateways and proxies" actions={<button className="btn sm ghost" onClick={() => comps.refetch()} aria-label="Refresh components"><RefreshCw /></button>}>
      <QueryBoundary query={comps} empty={<EmptyState inline title="No component has sent a heartbeat" hint="Start a gateway or proxy pointed at this control plane." />}>
        {(rows) => (
          <div className="stack">
            {rows.map((c) => (
              <div key={`${c.componentType}-${c.componentId}`} style={{ borderBottom: '1px solid var(--border)', paddingBottom: 10 }}>
                <div className="row between">
                  <span className="row" style={{ gap: 8 }}><Badge outline>{c.componentType}</Badge><strong>{c.componentId}</strong><span className="muted small">{c.version}</span></span>
                  <HealthBadge healthy={c.healthy} />
                </div>
                <KV items={[
                  ['Host', c.host],
                  ['Started', <RelativeTime value={c.startedAt} />],
                  ['Last heartbeat', <RelativeTime value={c.lastHeartbeat} />],
                  ...Object.entries(c.stats ?? {}).filter(([k, v]) => k !== 'liveConnections' && (typeof v === 'number' || typeof v === 'string')).map(([k, v]) => [k, typeof v === 'number' ? formatNumber(v) : String(v)] as [string, string]),
                ]} />
              </div>
            ))}
          </div>
        )}
      </QueryBoundary>
    </Card>
  );
}

function ImportExportCard({ className }: { className?: string }) {
  const { exportAll, importAll } = useAdminMutations();
  const toast = useToast();
  const file = useRef<HTMLInputElement>(null);
  const [preview, setPreview] = useState<ExportDocument | null>(null);
  const [result, setResult] = useState<Record<string, number> | null>(null);
  const onFile = async (f: File) => {
    try { setPreview(JSON.parse(await f.text())); setResult(null); } catch (e) { toast.error(`Not a JSON document: ${errorMessage(e)}`); }
  };
  const counts = (d: ExportDocument) => (['teams', 'applications', 'databases', 'credentials', 'datasources', 'accessGrants', 'ownership', 'relationships', 'dependencies'] as const).map((k) => [k, (d[k] as unknown[] | undefined)?.length ?? 0] as const).filter(([, n]) => n > 0);
  return (
    <Card className={className} title="Import / export" hint="configuration as JSON (no secrets)">
      <div className="grid cols-2">
        <div className="stack">
          <p className="muted small">Exports teams, applications, databases, credentials (without secrets), datasources, grants, declared ownership and declared relationships. Import upserts by name.</p>
          <div className="btn-group">
            <button className="btn" disabled={exportAll.isPending} onClick={() => exportAll.mutate(undefined, { onSuccess: (doc) => { fileDownload(`dbp-export-${new Date().toISOString().slice(0, 10)}.json`, JSON.stringify(doc, null, 2)); toast.success('Export downloaded'); }, onError: (e) => toast.error(errorMessage(e)) })}><Download /> Export JSON</button>
            <button className="btn" onClick={() => file.current?.click()}><Upload /> Choose file to import…</button>
            <input ref={file} type="file" accept="application/json,.json" style={{ display: 'none' }} onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ''; }} />
          </div>
        </div>
        <div>
          {preview ? (
            <div className="stack" style={{ gap: 8 }}>
              <div className="notice info"><AlertTriangle /><div><strong>Ready to import.</strong> {counts(preview).map(([k, n]) => `${n} ${k}`).join(', ') || 'The document contains no recognised collections.'}</div></div>
              <div className="btn-group">
                <button className="btn primary" disabled={importAll.isPending} onClick={() => importAll.mutate(preview, { onSuccess: (r) => { setResult(r?.imported ?? {}); setPreview(null); toast.success('Import finished'); }, onError: (e) => toast.error(errorMessage(e)) })}>{importAll.isPending && <span className="spinner" />} Import now</button>
                <button className="btn ghost" onClick={() => setPreview(null)}>Discard</button>
              </div>
            </div>
          ) : result ? (
            <div className="notice success"><DbIcon /><div><strong>Imported.</strong> {Object.entries(result).map(([k, n]) => `${n} ${k}`).join(', ') || 'nothing to do'}</div></div>
          ) : (
            <p className="muted small">Drop a previously exported document here to apply it to this control plane.</p>
          )}
        </div>
      </div>
    </Card>
  );
}

function SeedCard() {
  const { seedDemo } = useAdminMutations();
  const toast = useToast();
  const [confirm, setConfirm] = useState(false);
  return (
    <Card title="Demo data">
      <p className="muted small">Loads the retail demo dataset (teams, applications, two databases, three datasources, catalogue and telemetry). Idempotent; requires <code>DBP_DEMO_SEED_ENABLED=true</code> on the control plane.</p>
      <button className="btn" onClick={() => setConfirm(true)} disabled={seedDemo.isPending}>{seedDemo.isPending ? <span className="spinner" /> : <DbIcon />} Seed demo dataset</button>
      <ConfirmDialog open={confirm} title="Seed demo dataset" confirmLabel="Seed" busy={seedDemo.isPending} onClose={() => setConfirm(false)}
        message={<p>Existing objects with the same names are updated in place. Nothing is deleted.</p>}
        onConfirm={() => seedDemo.mutate(undefined, { onSuccess: () => { toast.success('Demo dataset loaded'); setConfirm(false); }, onError: (e) => toast.error(errorMessage(e)) })} />
    </Card>
  );
}
