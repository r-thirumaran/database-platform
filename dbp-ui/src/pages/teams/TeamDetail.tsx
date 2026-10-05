import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Pencil, Trash2 } from 'lucide-react';
import { useTeamMutations, useTeamSummary } from '../../api/hooks';
import type { TableRef, TeamSummary } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card, KV } from '../../components/Card';
import { Loading, ErrorState, EmptyState } from '../../components/States';
import { AppKindBadge, Badge, StateBadge } from '../../components/Badge';
import { ConfirmDialog, Modal } from '../../components/Modal';
import { TeamForm } from './TeamsPage';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';
import { CopyId } from '../../components/CopyId';
import { StatTile } from '../../components/StatTile';
import { RelativeTime } from '../../components/RelativeTime';

export function TeamDetail() {
  const { id = '' } = useParams();
  const s = useTeamSummary(id);
  if (s.isPending) return <Loading />;
  if (s.isError) return <ErrorState error={s.error} retry={() => s.refetch()} />;
  return <TeamView s={s.data} />;
}

function TableList({ tables, empty }: { tables: TableRef[]; empty: string }) {
  if (tables.length === 0) return <EmptyState inline title={empty} />;
  return <div className="inline-list">{tables.map((t) => <Link key={t.id} to={links.table(t.id)} className="badge outline">{t.schema}.{t.name}</Link>)}</div>;
}

function TeamView({ s }: { s: TeamSummary }) {
  const t = s.team;
  const navigate = useNavigate();
  const toast = useToast();
  const { update, remove } = useTeamMutations();
  const [edit, setEdit] = useState(false);
  const [del, setDel] = useState(false);
  const ownedIds = new Set(s.ownedTables.map((x) => x.id));
  const foreignConsumed = s.consumedTables.filter((x) => !ownedIds.has(x.id));
  return (
    <>
      <PageHeader title={t.displayName} crumb={t.name} subtitle={<span className="row"><code>{t.name}</code>{t.description ? ` — ${t.description}` : ''}<CopyId value={t.id} /></span>}
        actions={<><Link className="btn" to={links.graph(`team:${t.id}`, 2)}>Open in graph</Link><button className="btn" onClick={() => setEdit(true)}><Pencil /> Edit</button><button className="btn danger" onClick={() => setDel(true)}><Trash2 /> Delete</button></>} />
      <div className="kpi-row" style={{ marginBottom: 16 }}>
        <StatTile label="Applications" value={s.applications.length} />
        <StatTile label="Owned tables" value={s.ownedTables.length} />
        <StatTile label="Produced tables" value={s.producedTables.length} />
        <StatTile label="Consumed (other teams')" value={foreignConsumed.length} sub="tables this team reads or writes but does not own" />
        <StatTile label="Datasources owned" value={s.datasourcesOwned.length} />
      </div>
      <div className="grid cols-3">
        <Card title="Details">
          <KV items={[
            ['Contacts', t.contacts.length ? t.contacts.join(', ') : null],
            ['Tags', t.tags.length ? <span className="badge-row">{t.tags.map((x) => <Badge key={x} outline>{x}</Badge>)}</span> : null],
            ['Datasources', s.datasourcesOwned.length ? <span className="badge-row">{s.datasourcesOwned.map((d) => <Link key={d.id} to={links.datasource(d.id)} className="row" style={{ gap: 4 }}>{d.name}{d.state && <StateBadge state={d.state} />}</Link>)}</span> : null],
            ['Created', <RelativeTime value={t.createdAt} />],
          ]} />
        </Card>
        <Card title="Applications" className="span-2">
          {s.applications.length === 0 ? <EmptyState inline title="No applications" /> : (
            <div className="stack" style={{ gap: 6 }}>
              {s.applications.map((a) => <div key={a.id} className="row between"><span className="row"><Link to={links.application(a.id)}><strong>{a.name}</strong></Link><span className="muted small">{a.displayName}</span></span><span className="row"><AppKindBadge kind={a.kind} /><Badge outline>{a.runtime}</Badge></span></div>)}
            </div>
          )}
        </Card>
        <Card title="Owned tables" hint={`${s.ownedTables.length}`}><TableList tables={s.ownedTables} empty="This team owns no tables" /></Card>
        <Card title="Produced tables" hint="written authoritatively by this team's applications"><TableList tables={s.producedTables} empty="No produced tables" /></Card>
        <Card title="Consumed tables" hint="read or written by this team's applications"><TableList tables={s.consumedTables} empty="No consumed tables" /></Card>
      </div>
      <Modal open={edit} title={`Edit ${t.displayName}`} onClose={() => setEdit(false)}>
        <TeamForm initial={t} busy={update.isPending} onCancel={() => setEdit(false)} onSubmit={(v) => update.mutate({ id: t.id, body: v }, { onSuccess: () => { toast.success('Saved'); setEdit(false); }, onError: (e) => toast.error(errorMessage(e)) })} />
      </Modal>
      <ConfirmDialog open={del} title="Delete team" danger confirmLabel="Delete" busy={remove.isPending} onClose={() => setDel(false)}
        message={<p>Delete <strong>{t.displayName}</strong>? Tables and datasources it owns become unowned; applications keep running.</p>}
        onConfirm={() => remove.mutate(t.id, { onSuccess: () => { toast.success('Team deleted'); navigate(links.teams()); }, onError: (e) => toast.error(errorMessage(e)) })} />
    </>
  );
}
