import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Search } from 'lucide-react';
import { useAccessGrants, useApplicationMutations, useApplications, useTeams } from '../../api/hooks';
import { PageHeader } from '../../components/PageHeader';
import { Card } from '../../components/Card';
import { QueryBoundary, EmptyState } from '../../components/States';
import { DataTable } from '../../components/DataTable';
import { AppKindBadge, Badge } from '../../components/Badge';
import { Modal } from '../../components/Modal';
import { ApplicationForm } from './ApplicationForm';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';

export function ApplicationsPage() {
  const apps = useApplications();
  const teams = useTeams();
  const grants = useAccessGrants();
  const { create } = useApplicationMutations();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [teamId, setTeamId] = useState('');
  const toast = useToast();
  const rows = useMemo(() => (apps.data ?? []).filter((a) => (!teamId || a.teamId === teamId) && (!q || `${a.name} ${a.displayName} ${a.tags.join(' ')}`.toLowerCase().includes(q.toLowerCase()))), [apps.data, q, teamId]);
  return (
    <>
      <PageHeader title="Applications" subtitle="Everything that connects: services through the gateway, legacy systems through the proxy." actions={<button className="btn primary" onClick={() => setOpen(true)}><Plus /> Register application</button>} />
      <div className="filters">
        <div className="field grow"><label htmlFor="app-q">Search</label><div className="search"><Search /><input id="app-q" className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="name, tag…" /></div></div>
        <div className="field"><label htmlFor="app-team">Team</label><select id="app-team" className="input" value={teamId} onChange={(e) => setTeamId(e.target.value)}><option value="">all teams</option>{teams.data?.map((t) => <option key={t.id} value={t.id}>{t.displayName}</option>)}</select></div>
      </div>
      <Card tight>
        <QueryBoundary query={apps} empty={<EmptyState title="No applications" action={<button className="btn" onClick={() => setOpen(true)}>Register application</button>} />}>
          {() => (
            <DataTable
              rows={rows}
              rowKey={(a) => a.id}
              rowLink={(a) => links.application(a.id)}
              empty={<EmptyState inline title="No application matches" />}
              columns={[
                { key: 'name', header: 'Name', sort: (a) => a.name, render: (a) => <><strong><Link to={links.application(a.id)}>{a.name}</Link></strong><div className="muted small">{a.displayName}</div></> },
                { key: 'kind', header: 'Kind', sort: (a) => a.kind, render: (a) => <AppKindBadge kind={a.kind} /> },
                { key: 'team', header: 'Team', sort: (a) => teams.data?.find((t) => t.id === a.teamId)?.name, render: (a) => { const t = teams.data?.find((x) => x.id === a.teamId); return t ? <Link to={links.team(t.id)}>{t.name}</Link> : <span className="muted">—</span>; } },
                { key: 'runtime', header: 'Runtime', render: (a) => <span className="small">{a.runtime}</span> },
                { key: 'ds', header: 'Datasources', align: 'right', render: (a) => grants.data?.filter((g) => g.applicationId === a.id && g.enabled).length ?? '…' },
                { key: 'tags', header: 'Tags', render: (a) => <span className="badge-row">{a.tags.map((t) => <Badge key={t} outline>{t}</Badge>)}</span> },
              ]}
            />
          )}
        </QueryBoundary>
      </Card>
      <Modal open={open} title="Register application" onClose={() => setOpen(false)} wide>
        <ApplicationForm busy={create.isPending} onCancel={() => setOpen(false)} onSubmit={(v) => create.mutate(v, { onSuccess: () => { toast.success('Application registered'); setOpen(false); }, onError: (e) => toast.error(errorMessage(e)) })} />
      </Modal>
    </>
  );
}
