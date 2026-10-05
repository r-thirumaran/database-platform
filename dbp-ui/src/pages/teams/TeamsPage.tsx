import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { useApplications, useTeamMutations, useTeams } from '../../api/hooks';
import type { Team, TeamInput } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card } from '../../components/Card';
import { QueryBoundary, EmptyState } from '../../components/States';
import { DataTable } from '../../components/DataTable';
import { Badge } from '../../components/Badge';
import { Modal } from '../../components/Modal';
import { Field } from '../../components/Field';
import { ChipInput } from '../../components/ChipInput';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';

export function TeamForm({ initial, onSubmit, busy, onCancel }: { initial?: Team; onSubmit: (v: TeamInput) => void; busy?: boolean; onCancel: () => void }) {
  const [v, setV] = useState<TeamInput>(initial ?? { name: '', displayName: '', description: '', contacts: [], tags: [] });
  return (
    <form onSubmit={(e) => { e.preventDefault(); onSubmit(v); }}>
      <div className="form-grid">
        <Field label="Name">{(id) => <input id={id} className="input" required pattern="[a-z0-9][a-z0-9-]*" value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} placeholder="sales-platform" />}</Field>
        <Field label="Display name">{(id) => <input id={id} className="input" required value={v.displayName} onChange={(e) => setV({ ...v, displayName: e.target.value })} />}</Field>
        <Field label="Contacts" full>{(id) => <ChipInput id={id} value={v.contacts} onChange={(c) => setV({ ...v, contacts: c })} placeholder="team@example.org, #channel" />}</Field>
        <Field label="Tags" full>{(id) => <ChipInput id={id} value={v.tags} onChange={(t) => setV({ ...v, tags: t })} placeholder="domain:sales" />}</Field>
        <Field label="Description" full>{(id) => <input id={id} className="input" value={v.description ?? ''} onChange={(e) => setV({ ...v, description: e.target.value })} />}</Field>
      </div>
      <div className="form-actions"><button type="button" className="btn" onClick={onCancel}>Cancel</button><button type="submit" className="btn primary" disabled={busy}>{busy && <span className="spinner" />} {initial ? 'Save' : 'Create team'}</button></div>
    </form>
  );
}

export function TeamsPage() {
  const teams = useTeams();
  const apps = useApplications();
  const { create } = useTeamMutations();
  const [open, setOpen] = useState(false);
  const toast = useToast();
  return (
    <>
      <PageHeader title="Teams" subtitle="Owners of datasources, tables and applications." actions={<button className="btn primary" onClick={() => setOpen(true)}><Plus /> Add team</button>} />
      <Card tight>
        <QueryBoundary query={teams} empty={<EmptyState title="No teams" action={<button className="btn" onClick={() => setOpen(true)}>Add team</button>} />}>
          {(rows) => (
            <DataTable rows={rows} rowKey={(t) => t.id} rowLink={(t) => links.team(t.id)} columns={[
              { key: 'name', header: 'Team', sort: (t) => t.name, render: (t) => <><strong><Link to={links.team(t.id)}>{t.displayName}</Link></strong><div className="muted small">{t.name}</div></> },
              { key: 'desc', header: 'Description', className: 'truncate', render: (t) => <span className="muted">{t.description}</span> },
              { key: 'apps', header: 'Applications', align: 'right', render: (t) => apps.data?.filter((a) => a.teamId === t.id).length ?? '…' },
              { key: 'contacts', header: 'Contacts', render: (t) => t.contacts.join(', ') },
              { key: 'tags', header: 'Tags', render: (t) => <span className="badge-row">{t.tags.map((x) => <Badge key={x} outline>{x}</Badge>)}</span> },
            ]} />
          )}
        </QueryBoundary>
      </Card>
      <Modal open={open} title="Add team" onClose={() => setOpen(false)}>
        <TeamForm busy={create.isPending} onCancel={() => setOpen(false)} onSubmit={(v) => create.mutate(v, { onSuccess: () => { toast.success('Team created'); setOpen(false); }, onError: (e) => toast.error(errorMessage(e)) })} />
      </Modal>
    </>
  );
}
