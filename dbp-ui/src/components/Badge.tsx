import type { ReactNode } from 'react';
import { AlertOctagon, AlertTriangle, CheckCircle2, Info, type LucideIcon } from 'lucide-react';
import type {
  ApplicationKind, Classification, DatasourceState, DependencySource, EdgeKind, Engine, MigrationState, OwnerSource, PolicyKind,
  RelationshipKind, RelationshipSource, RoutineKind, Severity, TableKind, ViolationStatus,
} from '../api/types';

type Tone = 'blue' | 'green' | 'amber' | 'red' | 'violet' | 'pink' | 'teal' | 'orange' | 'muted' | '';

export function Badge({ tone = '', children, outline = false, lg = false, title, icon: Icon }: { tone?: Tone; children: ReactNode; outline?: boolean; lg?: boolean; title?: string; icon?: LucideIcon }) {
  return (
    <span className={`badge ${tone} ${outline ? 'outline' : ''} ${lg ? 'lg' : ''}`} title={title}>
      {Icon && <Icon aria-hidden="true" />}
      {children}
    </span>
  );
}

const ENGINE_TONE: Record<Engine, Tone> = { ORACLE: 'red', POSTGRES: 'blue', MSSQL: 'violet' };
export const EngineBadge = ({ engine, lg }: { engine: Engine | string | null | undefined; lg?: boolean }) =>
  engine ? <Badge tone={ENGINE_TONE[engine as Engine] ?? ''} lg={lg}>{engine}</Badge> : null;

const STATE_TONE: Record<DatasourceState, Tone> = { ACTIVE: 'green', MIGRATING: 'amber', RETIRED: 'muted' };
export const StateBadge = ({ state, lg }: { state: DatasourceState; lg?: boolean }) => <Badge tone={STATE_TONE[state]} lg={lg}>{state}</Badge>;

const SEVERITY: Record<Severity, { tone: Tone; icon: LucideIcon }> = { LOW: { tone: 'blue', icon: Info }, MEDIUM: { tone: 'amber', icon: AlertTriangle }, HIGH: { tone: 'red', icon: AlertOctagon } };
export const SeverityBadge = ({ severity }: { severity: Severity }) => <Badge tone={SEVERITY[severity].tone} icon={SEVERITY[severity].icon}>{severity}</Badge>;

const VSTATUS: Record<ViolationStatus, Tone> = { OPEN: 'red', ACKNOWLEDGED: 'amber', RESOLVED: 'green' };
export const ViolationStatusBadge = ({ status }: { status: ViolationStatus }) => <Badge tone={VSTATUS[status]} outline>{status}</Badge>;

const SOURCE_TONE: Record<RelationshipSource | DependencySource, Tone> = {
  GATEWAY: 'blue', COLLECTOR_AUDIT: 'teal', PROXY_CORRELATION: 'violet', COLLECTOR_SESSION: 'amber', DECLARED: 'green', DICTIONARY: 'teal', RUNTIME: 'blue',
};
const SOURCE_LABEL: Record<RelationshipSource | DependencySource, string> = {
  GATEWAY: 'gateway', COLLECTOR_AUDIT: 'audit trail', PROXY_CORRELATION: 'proxy', COLLECTOR_SESSION: 'session sample', DECLARED: 'declared', DICTIONARY: 'dictionary', RUNTIME: 'runtime',
};
export const SourceBadge = ({ source }: { source: RelationshipSource | DependencySource | string | undefined }) =>
  source ? <Badge tone={SOURCE_TONE[source as RelationshipSource] ?? ''} title={source}>{SOURCE_LABEL[source as RelationshipSource] ?? source.toLowerCase()}</Badge> : null;

const KIND_TONE: Record<RelationshipKind | EdgeKind, Tone> = {
  READS: 'blue', WRITES: 'orange', CALLS: 'pink', REFERENCES: 'muted', FOREIGN_KEY: 'teal', TRIGGERS: 'red', OWNS: 'violet', HOSTS: 'muted', MIGRATES_TO: 'green', BELONGS_TO: 'muted',
};
export const KindBadge = ({ kind }: { kind: RelationshipKind | EdgeKind | string }) => <Badge tone={KIND_TONE[kind as EdgeKind] ?? ''}>{kind.replace('_', ' ')}</Badge>;

const CLASS_TONE: Record<Classification, Tone> = { PII: 'red', CONFIDENTIAL: 'amber', INTERNAL: 'blue', PUBLIC: 'green' };
export const ClassificationBadge = ({ value }: { value: Classification | null | undefined }) => (value ? <Badge tone={CLASS_TONE[value]} outline>{value}</Badge> : null);

const OWNER_TONE: Record<OwnerSource, Tone> = { DECLARED: 'green', INFERRED: 'amber', NONE: 'muted' };
export const OwnerSourceBadge = ({ source, confirmed }: { source: OwnerSource; confirmed: boolean }) => (
  <Badge tone={OWNER_TONE[source]} outline icon={confirmed ? CheckCircle2 : undefined} title={confirmed ? 'Owner confirmed' : 'Not confirmed'}>
    {source === 'NONE' ? 'unowned' : source.toLowerCase()}
  </Badge>
);

const MIG_TONE: Record<MigrationState, Tone> = { NOT_PLANNED: 'muted', PLANNED: 'blue', IN_PROGRESS: 'amber', DONE: 'green' };
export const MigrationBadge = ({ state }: { state: MigrationState }) => <Badge tone={MIG_TONE[state]}>{state.replace('_', ' ')}</Badge>;

const APPKIND_TONE: Record<ApplicationKind, Tone> = { SERVICE: 'blue', BATCH: 'violet', UI: 'teal', LEGACY: 'amber', TOOL: 'muted' };
export const AppKindBadge = ({ kind }: { kind: ApplicationKind }) => <Badge tone={APPKIND_TONE[kind]} outline>{kind}</Badge>;

const RKIND_TONE: Record<RoutineKind, Tone> = { PROCEDURE: 'pink', FUNCTION: 'pink', PACKAGE: 'violet', PACKAGE_BODY: 'violet', TRIGGER: 'red', VIEW: 'teal' };
export const RoutineKindBadge = ({ kind }: { kind: RoutineKind | string | undefined }) => (kind ? <Badge tone={RKIND_TONE[kind as RoutineKind] ?? ''} outline>{kind.replace('_', ' ')}</Badge> : null);
export const TableKindBadge = ({ kind }: { kind: TableKind | string | undefined }) => (kind && kind !== 'TABLE' ? <Badge outline>{kind.replace('_', ' ')}</Badge> : null);

import { POLICY_LABEL } from '../lib/labels';
export const PolicyBadge = ({ kind }: { kind: PolicyKind }) => <Badge outline title={kind}>{POLICY_LABEL[kind] ?? kind}</Badge>;

export function Confidence({ value }: { value: number | undefined }) {
  if (value === undefined || value === null) return null;
  const n = Math.round(value * 4);
  return (
    <span className="confidence" title={`confidence ${value}`} aria-label={`confidence ${Math.round(value * 100)} percent`}>
      <span className="bars" aria-hidden="true">
        {[1, 2, 3, 4].map((i) => (
          <i key={i} className={i <= n ? 'on' : ''} style={{ height: 3 + i * 2 }} />
        ))}
      </span>
      {Math.round(value * 100)}%
    </span>
  );
}

export const HealthBadge = ({ healthy }: { healthy: boolean }) => (
  <Badge tone={healthy ? 'green' : 'red'} icon={healthy ? CheckCircle2 : AlertOctagon}>{healthy ? 'healthy' : 'unhealthy'}</Badge>
);
