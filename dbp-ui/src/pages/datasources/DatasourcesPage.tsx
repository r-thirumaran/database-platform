import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Plus } from 'lucide-react';
import { useDatabases, useDatasourceMutations, useDatasources, useTeams } from '../../api/hooks';
import { PageHeader } from '../../components/PageHeader';
import { Card } from '../../components/Card';
import { QueryBoundary, EmptyState } from '../../components/States';
import { DataTable } from '../../components/DataTable';
import { Badge, EngineBadge, StateBadge } from '../../components/Badge';
import { Modal } from '../../components/Modal';
import { DatasourceForm } from './DatasourceForm';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';

export function DatasourcesPage() {
  const dss = useDatasources();
  const dbs = useDatabases();
  const teams = useTeams();
  const { create } = useDatasourceMutations();
  const [open, setOpen] = useState(false);
  const toast = useToast();
  const db = (id: string | null) => dbs.data?.find((d) => d.id === id);
  return (
    <>
      <PageHeader title="Datasources" subtitle="Logical names applications connect to. Each one routes to a physical database and can migrate application by application." actions={<button className="btn primary" onClick={() => setOpen(true)}><Plus /> Add datasource</button>} />
      <Card tight>
        <QueryBoundary query={dss} empty={<EmptyState title="No datasources" hint="Create one to give applications a stable logical name." action={<button className="btn" onClick={() => setOpen(true)}>Add datasource</button>} />}>
          {(rows) => (
            <DataTable
              rows={rows}
              rowKey={(d) => d.id}
              rowLink={(d) => links.datasource(d.id)}
              columns={[
                { key: 'name', header: 'Name', sort: (d) => d.name, render: (d) => <><strong><Link to={links.datasource(d.id)}>{d.name}</Link></strong><div className="muted small">{d.displayName}</div></> },
                { key: 'state', header: 'State', sort: (d) => d.state, render: (d) => <StateBadge state={d.state} /> },
                { key: 'route', header: 'Routes to', render: (d) => {
                  const cur = db(d.currentDatabaseId); const tgt = db(d.targetDatabaseId);
                  return <span className="row" style={{ gap: 6 }}>{cur ? <><Link to={links.database(cur.id)}>{cur.name}</Link><EngineBadge engine={cur.engine} /></> : <span className="muted">unknown</span>}{tgt && <><ArrowRight size={14} className="muted" /><Link to={links.database(tgt.id)}>{tgt.name}</Link><EngineBadge engine={tgt.engine} /></>}</span>;
                } },
                { key: 'owner', header: 'Owner', render: (d) => { const t = teams.data?.find((x) => x.id === d.ownerTeamId); return t ? <Link to={links.team(t.id)}>{t.name}</Link> : <span className="muted">—</span>; } },
                { key: 'pool', header: 'Pool', render: (d) => <span className="small">{d.poolPolicy.mode} · max {d.poolPolicy.maxConnections}</span> },
                { key: 'rules', header: 'Rules', align: 'right', render: (d) => d.routingRules.length ? <Badge tone={d.routingRules.some((r) => r.enabled) ? 'amber' : ''}>{d.routingRules.filter((r) => r.enabled).length}/{d.routingRules.length}</Badge> : <span className="muted">0</span> },
              ]}
            />
          )}
        </QueryBoundary>
      </Card>
      <Modal open={open} title="Add datasource" onClose={() => setOpen(false)} wide>
        <DatasourceForm busy={create.isPending} onCancel={() => setOpen(false)} onSubmit={(v) => create.mutate(v, { onSuccess: () => { toast.success('Datasource created'); setOpen(false); }, onError: (e) => toast.error(errorMessage(e)) })} />
      </Modal>
    </>
  );
}
