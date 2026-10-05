import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Play } from 'lucide-react';
import { useApplications, useGovernanceMutations, usePolicies, useTeams, useViolations } from '../../api/hooks';
import type { Severity, ViolationStatus } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { Card } from '../../components/Card';
import { QueryBoundary, EmptyState } from '../../components/States';
import { DataTable } from '../../components/DataTable';
import { Badge, POLICY_LABEL, PolicyBadge, SeverityBadge, ViolationStatusBadge } from '../../components/Badge';
import { RelativeTime } from '../../components/RelativeTime';
import { Toggle } from '../../components/Field';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';
import { Tabs } from '../../components/Tabs';

export function GovernancePage() {
  const policies = usePolicies();
  const [status, setStatus] = useState<ViolationStatus | 'ALL'>('OPEN');
  const violations = useViolations(status === 'ALL' ? undefined : status);
  const all = useViolations();
  const apps = useApplications();
  const teams = useTeams();
  const { updatePolicy, updateViolation, evaluate } = useGovernanceMutations();
  const toast = useToast();
  const count = (s: ViolationStatus) => all.data?.filter((v) => v.status === s).length;
  const appName = (id: string | null) => apps.data?.find((a) => a.id === id)?.name;
  const teamName = (id: string | null) => teams.data?.find((t) => t.id === id)?.name;
  return (
    <>
      <PageHeader title="Governance" subtitle="Policies evaluated against observed relationships, ownership and grants. Acknowledge what is known, resolve what is fixed."
        actions={<button className="btn" onClick={() => evaluate.mutate(undefined, { onSuccess: () => toast.success('Evaluation started'), onError: (e) => toast.error(errorMessage(e)) })} disabled={evaluate.isPending}><Play /> Evaluate now</button>} />
      <div className="grid cols-3">
        <Card title="Policies" tight>
          <QueryBoundary query={policies}>
            {(rows) => (
              <DataTable compact rows={rows} rowKey={(p) => p.id} columns={[
                { key: 'kind', header: 'Policy', render: (p) => <><strong>{POLICY_LABEL[p.kind] ?? p.kind}</strong>{p.description && <div className="muted small">{p.description}</div>}</> },
                { key: 'sev', header: 'Severity', render: (p) => <select className="input sm" aria-label={`Severity of ${POLICY_LABEL[p.kind]}`} value={p.severity} onChange={(e) => updatePolicy.mutate({ id: p.id, body: { severity: e.target.value as Severity } }, { onError: (err) => toast.error(errorMessage(err)) })}><option>LOW</option><option>MEDIUM</option><option>HIGH</option></select> },
                { key: 'on', header: 'Enabled', render: (p) => <Toggle checked={p.enabled} onChange={(v) => updatePolicy.mutate({ id: p.id, body: { enabled: v } }, { onError: (err) => toast.error(errorMessage(err)) })} label={<span className="sr-only">enabled</span>} /> },
              ]} />
            )}
          </QueryBoundary>
        </Card>
        <Card className="span-2" title="Violations" tight>
          <div style={{ padding: '8px 16px 0' }}>
            <Tabs active={status} onChange={(s) => setStatus(s as ViolationStatus | 'ALL')} tabs={[
              { id: 'OPEN', label: 'Open', count: count('OPEN') }, { id: 'ACKNOWLEDGED', label: 'Acknowledged', count: count('ACKNOWLEDGED') }, { id: 'RESOLVED', label: 'Resolved', count: count('RESOLVED') }, { id: 'ALL', label: 'All', count: all.data?.length },
            ]} />
          </div>
          <QueryBoundary query={violations} empty={<EmptyState inline title={status === 'OPEN' ? 'No open violations' : 'Nothing here'} hint={status === 'OPEN' ? 'Every observed access matches ownership and declared relationships.' : undefined} />}>
            {(rows) => (
              <DataTable compact rows={rows} rowKey={(v) => v.id} initialSort={{ key: 'sev', dir: 'desc' }} columns={[
                { key: 'sev', header: 'Severity', sort: (v) => ({ LOW: 0, MEDIUM: 1, HIGH: 2 })[v.severity], render: (v) => <SeverityBadge severity={v.severity} /> },
                { key: 'what', header: 'Violation', render: (v) => <><strong>{v.label}</strong><div className="muted small" style={{ maxWidth: 520 }}>{v.detail}</div><div style={{ marginTop: 4 }}><PolicyBadge kind={v.policyKind} /></div></> },
                { key: 'who', header: 'Application / team', render: (v) => <>{v.applicationId ? <Link to={links.application(v.applicationId)}>{appName(v.applicationId) ?? v.applicationId}</Link> : <span className="muted">—</span>}<div className="muted small">{teamName(v.teamId)}</div></> },
                { key: 'obj', header: 'Object', render: (v) => (v.objectId ? <Link to={v.objectType === 'ROUTINE' ? links.routine(v.objectId) : links.table(v.objectId)}><Badge outline>{v.objectType}</Badge></Link> : <span className="muted">—</span>) },
                { key: 'seen', header: 'Last seen', sort: (v) => v.lastSeenAt, render: (v) => <><RelativeTime value={v.lastSeenAt} /><div className="muted small">first <RelativeTime value={v.firstSeenAt} /></div></> },
                { key: 'status', header: 'Status', render: (v) => <ViolationStatusBadge status={v.status} /> },
                { key: 'a', header: '', render: (v) => (
                  <div className="row-actions">
                    {v.status === 'OPEN' && <button className="btn sm" onClick={() => updateViolation.mutate({ id: v.id, status: 'ACKNOWLEDGED' }, { onError: (e) => toast.error(errorMessage(e)) })}>Acknowledge</button>}
                    {v.status !== 'RESOLVED' && <button className="btn sm primary" onClick={() => updateViolation.mutate({ id: v.id, status: 'RESOLVED' }, { onError: (e) => toast.error(errorMessage(e)) })}>Resolve</button>}
                    {v.status === 'RESOLVED' && <button className="btn sm ghost" onClick={() => updateViolation.mutate({ id: v.id, status: 'OPEN' }, { onError: (e) => toast.error(errorMessage(e)) })}>Reopen</button>}
                  </div>
                ) },
              ]} />
            )}
          </QueryBoundary>
        </Card>
      </div>
    </>
  );
}
