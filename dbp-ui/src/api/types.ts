/**
 * TypeScript types for every resource of the control plane API (docs/control-plane-api.md).
 * Field names are exact; the mock server (mock/) and the real control plane both follow them.
 */

export type Iso = string; // ISO-8601 UTC timestamp
export type Id = string;

// ---------------------------------------------------------------- paging / errors
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface ApiErrorBody {
  status: number;
  error: string;
  message: string;
  path?: string;
}

// ---------------------------------------------------------------- refs
export interface TableRef {
  id: Id;
  databaseId?: Id;
  schema: string;
  name: string;
  kind?: TableKind;
}
export interface RoutineRef {
  id: Id;
  databaseId?: Id;
  schema: string;
  name: string;
  kind?: RoutineKind;
}
export interface DatasourceRef {
  id: Id;
  name: string;
  state?: DatasourceState;
}

// ---------------------------------------------------------------- 1. teams
export interface Team {
  id: Id;
  name: string;
  displayName: string;
  description?: string | null;
  contacts: string[];
  tags: string[];
  createdAt: Iso;
  updatedAt: Iso;
}
export type TeamInput = Omit<Team, 'id' | 'createdAt' | 'updatedAt'>;

export interface TeamSummary {
  team: Team;
  applications: Application[];
  ownedTables: TableRef[];
  consumedTables: TableRef[];
  producedTables: TableRef[];
  datasourcesOwned: DatasourceRef[];
}

// ---------------------------------------------------------------- 2. applications
export type ApplicationKind = 'SERVICE' | 'BATCH' | 'UI' | 'LEGACY' | 'TOOL';
export type Runtime = 'CLOUD_RUN' | 'KUBERNETES' | 'VM' | 'OTHER';

export interface IdentityRules {
  cidrs: string[];
  programNames: string[];
  machinePatterns: string[];
  serviceAliases: string[];
  pgApplicationNames: string[];
}

export interface Application {
  id: Id;
  name: string;
  displayName: string;
  teamId: Id | null;
  kind: ApplicationKind;
  description?: string | null;
  runtime: Runtime;
  identityRules: IdentityRules;
  tags: string[];
  createdAt: Iso;
  updatedAt: Iso;
}
export type ApplicationInput = Omit<Application, 'id' | 'createdAt' | 'updatedAt'>;

export interface ConnectionStats {
  proxy: number;
  gatewayLogical: number;
  gatewayPhysical: number;
}
export interface QueryStats {
  count24h: number;
  count7d: number;
  lastSeenAt: Iso | null;
  avgDurationMs?: number;
  errors24h?: number;
}

export interface ApplicationSummary {
  application: Application;
  team: Team | null;
  grants: AccessGrant[];
  reads: TableRef[];
  writes: TableRef[];
  calls: RoutineRef[];
  connections: ConnectionStats;
  queryStats: QueryStats;
}

export interface ApiKey {
  id: Id;
  prefix: string;
  label: string;
  createdAt: Iso;
  lastUsedAt: Iso | null;
  revokedAt: Iso | null;
}
export interface ApiKeyCreated {
  id: Id;
  prefix: string;
  apiKey: string; // plaintext, returned once
}

// ---------------------------------------------------------------- 3. databases
export type Engine = 'ORACLE' | 'POSTGRES' | 'MSSQL';

export interface CollectorConfig {
  enabled: boolean;
  dictionaryIntervalSeconds: number;
  runtimeIntervalSeconds: number;
  schemas: string[];
  auditTrail: boolean;
}

export interface Database {
  id: Id;
  name: string;
  engine: Engine;
  host: string;
  port: number;
  serviceName: string;
  credentialId: Id | null;
  maxPhysicalConnections: number;
  jdbcProperties: Record<string, string>;
  collector: CollectorConfig;
  description?: string | null;
  tags: string[];
  createdAt: Iso;
  updatedAt: Iso;
}
export type DatabaseInput = Omit<Database, 'id' | 'createdAt' | 'updatedAt'>;

export interface TestConnectionResult {
  ok: boolean;
  productName?: string;
  productVersion?: string;
  latencyMs?: number;
  message?: string;
}
export type CollectWhat = 'DICTIONARY' | 'RUNTIME' | 'AUDIT';
export interface SchemaInfo {
  name: string;
  tableCount: number;
  routineCount: number;
}
export interface CollectorStatus {
  lastDictionaryRun: Iso | null;
  lastRuntimeRun: Iso | null;
  lastError: string | null;
  tablesSeen: number;
  // additive extras returned by the control plane
  lastAuditRun?: Iso | null;
  routinesSeen?: number;
  sessionsSeen?: number;
  enabled?: boolean;
  running?: string[];
}

// ---------------------------------------------------------------- 4. credentials
export type CredentialProvider = 'INLINE' | 'ENV' | 'FILE' | 'VAULT' | 'GCP_SECRET_MANAGER' | 'AWS_SECRETS_MANAGER';

export interface Credential {
  id: Id;
  name: string;
  username: string;
  provider: CredentialProvider;
  ref: string | null;
  rotatedAt: Iso | null;
  version?: number;
  description?: string | null;
  createdAt: Iso;
  updatedAt: Iso;
}
export interface CredentialInput {
  name: string;
  username: string;
  provider: CredentialProvider;
  ref: string | null;
  description?: string | null;
  secret?: string; // INLINE only, never returned
}

// ---------------------------------------------------------------- 5. datasources
export type DatasourceState = 'ACTIVE' | 'MIGRATING' | 'RETIRED';
export type PoolMode = 'TRANSACTION' | 'SESSION';

export interface PoolPolicy {
  mode: PoolMode;
  maxConnections: number;
  minIdle: number;
  connectionTimeoutMs: number;
  idleTimeoutMs: number;
  maxLifetimeMs: number;
  statementTimeoutSeconds: number;
  validationQuery: string | null;
}

export interface RoutingRule {
  id: Id;
  priority: number;
  applicationId: Id | null;
  tag: string | null;
  databaseId: Id;
  readOnly: boolean;
  enabled: boolean;
}
export type RoutingRuleInput = Omit<RoutingRule, 'id'> & { id?: Id };

export interface Datasource {
  id: Id;
  name: string;
  displayName: string;
  ownerTeamId: Id | null;
  state: DatasourceState;
  currentDatabaseId: Id;
  targetDatabaseId: Id | null;
  poolPolicy: PoolPolicy;
  routingRules: RoutingRule[];
  description?: string | null;
  tags: string[];
  createdAt: Iso;
  updatedAt: Iso;
}
export type DatasourceInput = Omit<Datasource, 'id' | 'createdAt' | 'updatedAt' | 'routingRules'> & {
  routingRules?: RoutingRule[];
};

export interface PoolStats {
  timestamp: Iso;
  gatewayId: string;
  datasource: string;
  datasourceId: Id;
  databaseId: Id;
  engine: Engine;
  active: number;
  idle: number;
  waiting: number;
  total: number;
  max: number;
  logicalSessions: number;
  pinnedSessions: number;
  credentialVersion: number;
}

export interface DatasourceSummary {
  datasource: Datasource;
  ownerTeam: Team | null;
  currentDatabase: Database | null;
  targetDatabase: Database | null;
  grants: AccessGrant[];
  consumers: Application[];
  pools: PoolStats[];
  tables: TableRef[];
  warnings?: string[]; // additive: e.g. pool budget exceeds Database.maxPhysicalConnections
}

export interface MigrationEvent {
  id: Id;
  datasourceId: Id;
  fromDatabaseId: Id | null;
  toDatabaseId: Id;
  at: Iso;
  by: string;
  note: string | null;
}

// ---------------------------------------------------------------- 6. access grants
export interface AccessGrant {
  id: Id;
  applicationId: Id;
  datasourceId: Id;
  maxLogicalConnections: number;
  maxProxyConnections: number;
  poolModeOverride: PoolMode | null;
  readOnly: boolean;
  enabled: boolean;
  note?: string | null;
  createdAt: Iso;
  updatedAt: Iso;
}
export type AccessGrantInput = Omit<AccessGrant, 'id' | 'createdAt' | 'updatedAt'>;

// ---------------------------------------------------------------- 7. catalogue
export type TableKind = 'TABLE' | 'VIEW' | 'MATERIALIZED_VIEW';
export type OwnerSource = 'DECLARED' | 'INFERRED' | 'NONE';
export type Classification = 'PII' | 'CONFIDENTIAL' | 'INTERNAL' | 'PUBLIC';
export type MigrationState = 'NOT_PLANNED' | 'PLANNED' | 'IN_PROGRESS' | 'DONE';

export interface TableMigration {
  targetDatabaseId: Id | null;
  targetSchema: string | null;
  targetName: string | null;
  state: MigrationState;
}

export interface Table {
  id: Id;
  databaseId: Id;
  schema: string;
  name: string;
  kind: TableKind;
  ownerTeamId: Id | null;
  ownerConfirmed: boolean;
  ownerSource: OwnerSource;
  producerApplicationId: Id | null;
  producerSource?: OwnerSource | null; // additive: how the producer was determined
  rowCountEstimate: number | null;
  lastDdlAt: Iso | null;
  lastSeenAt: Iso | null;
  firstSeenAt: Iso | null;
  migration: TableMigration;
  description?: string | null;
  tags: string[];
  classification: Classification | null;
  discovered?: boolean;
}
export type TableUpdate = Partial<
  Pick<Table, 'ownerTeamId' | 'producerApplicationId' | 'description' | 'tags' | 'classification' | 'migration'>
>;

export interface Column {
  id: Id;
  tableId: Id;
  name: string;
  position: number;
  dataType: string;
  length: number | null;
  precision: number | null;
  scale: number | null;
  nullable: boolean;
  defaultValue: string | null;
  comment: string | null;
  classification: Classification | null;
}

// Views are catalogued as Table.kind = VIEW, not as routines.
export type RoutineKind = 'PROCEDURE' | 'FUNCTION' | 'PACKAGE' | 'PACKAGE_BODY' | 'TRIGGER';

export interface Routine {
  id: Id;
  databaseId: Id;
  schema: string;
  name: string;
  kind: RoutineKind;
  triggerTableId: Id | null;
  triggerEvent: string | null;
  ownerTeamId: Id | null;
  status: 'VALID' | 'INVALID';
  lastDdlAt: Iso | null;
  lastSeenAt: Iso | null;
}

export type DependencyKind = 'REFERENCES' | 'READS' | 'WRITES' | 'FOREIGN_KEY' | 'TRIGGERS' | 'CALLS';
export type DependencySource = 'DICTIONARY' | 'RUNTIME' | 'DECLARED';
export type ObjectType = 'TABLE' | 'ROUTINE';

export interface Dependency {
  id: Id;
  fromType: ObjectType;
  fromId: Id;
  toType: ObjectType;
  toId: Id;
  kind: DependencyKind;
  source: DependencySource;
  confidence: number;
  firstSeenAt: Iso | null;
  lastSeenAt: Iso | null;
  // resolved names, present on /routines/{id}/summary
  fromName?: string;
  toName?: string;
}
export type DependencyInput = Pick<Dependency, 'fromType' | 'fromId' | 'toType' | 'toId' | 'kind'>;

export type RelationshipKind = 'READS' | 'WRITES' | 'CALLS';
export type RelationshipSource = 'GATEWAY' | 'PROXY_CORRELATION' | 'COLLECTOR_SESSION' | 'COLLECTOR_AUDIT' | 'DECLARED';

export interface Relationship {
  id: Id;
  applicationId: Id;
  objectType: ObjectType;
  objectId: Id;
  kind: RelationshipKind;
  source: RelationshipSource;
  confidence?: number;
  queryCount: number;
  lastSeenAt: Iso | null;
  firstSeenAt: Iso | null;
  confirmed: boolean;
  viaRoutineId: Id | null;
}
export type RelationshipInput = Pick<Relationship, 'applicationId' | 'objectType' | 'objectId' | 'kind'>;

export interface Consumer {
  application: Application;
  team: Team | null;
  kind: RelationshipKind;
  queryCount: number;
  lastSeenAt: Iso | null;
  viaRoutine: RoutineRef | null;
  source?: RelationshipSource;
  confidence?: number;
  confirmed?: boolean;
  stale?: boolean; // additive: no activity for DBP_RELATIONSHIP_STALE_DAYS
  viaView?: TableRef | null; // additive: access through a view
}

export interface TableSummary {
  table: Table;
  database: Database;
  ownerTeam: Team | null;
  producer: Application | null;
  consumers: Consumer[];
  routines: RoutineRef[];
  triggers: RoutineRef[];
  foreignKeysOut: TableRef[];
  foreignKeysIn: TableRef[];
  views: TableRef[]; // views are tables (kind VIEW)
  queryStats: QueryStats;
  topQueries: QueryStat[];
}

export interface RoutineSummary {
  routine: Routine;
  dependencies: Dependency[]; // outgoing, with fromName/toName
  referencedBy: Dependency[]; // incoming, with fromName/toName
  callers: Consumer[]; // ConsumerEntry, like table consumers
  tables: TableRef[];
  // additive extras
  triggerTable?: Table | null;
  ownerTeam?: Team | null;
  database?: Database | null;
}

export interface TableQuery {
  databaseId?: string;
  schema?: string;
  q?: string;
  ownerTeamId?: string;
  unowned?: boolean;
  classification?: string;
  page?: number;
  size?: number;
}

// ---------------------------------------------------------------- 8. graph & impact
export type GraphNodeType = 'TEAM' | 'APPLICATION' | 'DATASOURCE' | 'DATABASE' | 'TABLE' | 'ROUTINE';
export type GraphRootType = 'team' | 'application' | 'datasource' | 'database' | 'table' | 'routine';
export type EdgeKind =
  | 'OWNS'
  | 'READS'
  | 'WRITES'
  | 'CALLS'
  | 'REFERENCES'
  | 'FOREIGN_KEY'
  | 'TRIGGERS'
  | 'HOSTS'
  | 'MIGRATES_TO'
  | 'BELONGS_TO'
  // emitted by the control plane in addition to the kinds listed in the contract
  | 'ROUTES_TO' // datasource → database
  | 'PRODUCES' // application → table (declared producer)
  | 'GRANTED'; // application → datasource (access grant)

export interface GraphNode {
  id: string; // "<type>:<refId>"
  type: GraphNodeType;
  label: string;
  refId: Id;
  attrs: Record<string, unknown>;
}
export interface GraphEdge {
  id: string;
  from: string;
  to: string;
  kind: EdgeKind;
  attrs: Record<string, unknown>;
}
export interface Graph {
  nodes: GraphNode[];
  edges: GraphEdge[];
  truncated: boolean;
}
export interface GraphQuery {
  root?: string; // "<type>:<id>"
  depth?: number;
  include?: string[]; // teams,applications,tables,routines,databases,datasources
  edgeKinds?: EdgeKind[];
  limit?: number;
}

export interface ImpactConsumer {
  application: Application;
  team: Team | null;
  kind: RelationshipKind;
  queryCount?: number;
  lastSeenAt?: Iso | null;
  viaRoutine?: RoutineRef | null;
  // not in the written contract yet, but useful to show how an access was observed
  source?: RelationshipSource;
  confidence?: number;
  confirmed?: boolean;
}

export interface Impact {
  target: { type: 'TABLE' | 'COLUMN' | 'DATASOURCE'; id: Id; label: string };
  owner: Team | null;
  producer: Application | null;
  directConsumers: ImpactConsumer[];
  indirectConsumers: ImpactConsumer[];
  routines: RoutineRef[];
  triggers: RoutineRef[];
  dependentViews: TableRef[];
  foreignKeyDependents: TableRef[];
  teamsAffected: Team[];
  queryStats: QueryStats;
  riskScore: number;
  riskFactors: string[];
  // column impact only
  column?: Column | null;
  queriesReferencingColumn?: QueryStat[];
}

/** `GET /impact/datasource/{id}` — what a migration switch touches. */
export interface DatasourceImpactApplication {
  application: Application;
  team: Team | null;
  hasRoutingRule: boolean;
  queryCount: number;
  lastSeenAt: Iso | null;
}
export interface DatasourceImpactTable {
  table: TableRef;
  consumers: ImpactConsumer[];
  queryCount: number;
  owner?: Team | null;
  riskScore?: number;
  riskFactors?: string[];
}
export interface DatasourceImpact {
  target: { type: 'DATASOURCE'; id: Id; label: string };
  datasource: Datasource;
  currentDatabase: Database | null;
  targetDatabase: Database | null;
  applications: DatasourceImpactApplication[];
  teamsAffected: Team[];
  tables: DatasourceImpactTable[];
  routines: RoutineRef[];
  triggers: RoutineRef[];
  riskScore: number;
  riskFactors: string[];
}

// ---------------------------------------------------------------- 11. observability
export interface ComponentInfo {
  componentType: 'GATEWAY' | 'PROXY';
  componentId: string;
  version?: string;
  host?: string;
  startedAt?: Iso;
  lastHeartbeat: Iso;
  healthy: boolean;
  stats?: Record<string, unknown>;
}

export interface Overview {
  databases: number;
  datasources: number;
  applications: number;
  teams: number;
  tables: number;
  routines: number;
  connections: { proxyActive: number; gatewayLogical: number; gatewayPhysical: number };
  queriesLastHour: number;
  unownedTables: number;
  crossTeamAccesses: number;
  violations: number;
  componentsOnline: ComponentInfo[];
}

export type ConnectionsGroupBy = 'application' | 'database' | 'datasource' | 'team';
export interface ConnectionsByKey {
  key: string;
  proxy: number;
  gatewayLogical: number;
  gatewayPhysical: number;
}

export type Operation = 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE' | 'MERGE' | 'CALL' | 'DDL' | 'TXN' | 'OTHER';
export interface QueryStat {
  sqlHash: string;
  sqlNormalized: string;
  operation: Operation;
  applicationId: Id | null;
  applicationName: string | null;
  databaseId: Id | null;
  tables: TableRef[];
  count: number;
  avgDurationMs: number;
  p95DurationMs: number;
  maxDurationMs: number;
  rows: number;
  errors: number;
  lastSeenAt: Iso;
}
export type TopQueriesBy = 'count' | 'duration' | 'rows';
export type Window = '1h' | '24h' | '7d';
export interface TopQueriesQuery {
  by?: TopQueriesBy;
  window?: Window;
  databaseId?: string;
  applicationId?: string;
  limit?: number;
}

export interface HotTable {
  table: TableRef;
  reads: number;
  writes: number;
  applications: number;
  teams: number;
}

export interface LiveConnection {
  source: 'PROXY' | 'COLLECTOR';
  application: string | null;
  team: string | null;
  datasource: string | null;
  database: string | null;
  engine: Engine | 'TCP' | null;
  clientAddr: string | null;
  program: string | null;
  machine: string | null;
  osUser: string | null;
  dbUser: string | null;
  status: string | null;
  openedAt: Iso | null;
  durationSeconds: number | null;
  sqlId: string | null;
  currentSql: string | null;
}

export type PolicyKind =
  | 'CROSS_TEAM_DIRECT_ACCESS'
  | 'UNOWNED_TABLE'
  | 'UNDECLARED_CONSUMER'
  | 'WRITE_BY_NON_PRODUCER'
  | 'DIRECT_DB_ACCESS_BYPASSING_PLATFORM';
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH';
export interface Policy {
  id: Id;
  kind: PolicyKind;
  enabled: boolean;
  severity: Severity;
  description?: string;
}
export type ViolationStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';
export interface Violation {
  id: Id;
  policyKind: PolicyKind;
  severity: Severity;
  applicationId: Id | null;
  teamId: Id | null;
  objectType: ObjectType | null;
  objectId: Id | null;
  label: string;
  detail: string;
  firstSeenAt: Iso;
  lastSeenAt: Iso;
  status: ViolationStatus;
}

// ---------------------------------------------------------------- 13. import/export
export interface ExportDocument {
  version?: number;
  exportedAt?: Iso;
  teams: Team[];
  applications: Application[];
  databases: Database[];
  credentials: Credential[];
  datasources: Datasource[];
  accessGrants: AccessGrant[];
  ownership?: Array<Record<string, unknown>>;
  producers?: Array<Record<string, unknown>>;
  tables?: Array<Record<string, unknown>>;
  relationships?: Array<Record<string, unknown>>;
  dependencies?: Array<Record<string, unknown>>;
}
