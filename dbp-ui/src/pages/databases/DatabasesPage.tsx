import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { useDatabaseMutations, useDatabases, useDatasources } from '../../api/hooks';
import { PageHeader } from '../../components/PageHeader';
import { Card } from '../../components/Card';
import { QueryBoundary, EmptyState } from '../../components/States';
import { DataTable } from '../../components/DataTable';
import { Badge, EngineBadge } from '../../components/Badge';
import { Modal } from '../../components/Modal';
import { DatabaseForm } from './DatabaseForm';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';
import { CopyId } from '../../components/CopyId';

export function DatabasesPage() {
  const dbs = useDatabases();
  const dss = useDatasources();
  const { create } = useDatabaseMutations();
  const [open, setOpen] = useState(false);
  const toast = useToast();
  return (
    <>
      <PageHeader title="Databases" subtitle="Physical databases the platform routes to and crawls." actions={<button className="btn primary" onClick={() => setOpen(true)}><Plus /> Add database</button>} />
      <Card tight>
        <QueryBoundary query={dbs} empty={<EmptyState title="No databases registered" hint="Add one to start routing datasources and crawling the dictionary." action={<button className="btn" onClick={() => setOpen(true)}>Add database</button>} />}>
          {(rows) => (
            <DataTable
              rows={rows}
              rowKey={(d) => d.id}
              rowLink={(d) => links.database(d.id)}
              columns={[
                { key: 'name', header: 'Name', sort: (d) => d.name, render: (d) => <strong><Link to={links.database(d.id)}>{d.name}</Link></strong> },
                { key: 'engine', header: 'Engine', sort: (d) => d.engine, render: (d) => <EngineBadge engine={d.engine} /> },
                { key: 'endpoint', header: 'Endpoint', className: 'mono', render: (d) => `${d.host}:${d.port}/${d.serviceName}` },
                { key: 'ds', header: 'Datasources', render: (d) => { const list = dss.data?.filter((s) => s.currentDatabaseId === d.id || s.targetDatabaseId === d.id) ?? []; return list.length ? <span className="badge-row">{list.map((s) => <Link key={s.id} to={links.datasource(s.id)} className="badge outline">{s.name}{s.targetDatabaseId === d.id ? ' (target)' : ''}</Link>)}</span> : <span className="muted">—</span>; } },
                { key: 'collector', header: 'Collector', render: (d) => (d.collector.enabled ? <Badge tone="green">on · {d.collector.schemas.join(', ') || 'all schemas'}</Badge> : <Badge>off</Badge>) },
                { key: 'max', header: 'Max phys.', align: 'right', sort: (d) => d.maxPhysicalConnections, render: (d) => d.maxPhysicalConnections },
                { key: 'id', header: 'Id', render: (d) => <CopyId value={d.id} /> },
              ]}
            />
          )}
        </QueryBoundary>
      </Card>
      <Modal open={open} title="Add database" onClose={() => setOpen(false)} wide>
        <DatabaseForm
          busy={create.isPending}
          onCancel={() => setOpen(false)}
          onSubmit={(v) => create.mutate(v, { onSuccess: () => { toast.success('Database created'); setOpen(false); }, onError: (e) => toast.error(errorMessage(e)) })}
        />
      </Modal>
    </>
  );
}
