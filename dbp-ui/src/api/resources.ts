import { http } from './client';
import type {
  AccessGrant,
  AccessGrantInput,
  ApiKey,
  ApiKeyCreated,
  Application,
  ApplicationInput,
  ApplicationSummary,
  CollectWhat,
  CollectorStatus,
  Column,
  ComponentInfo,
  ConnectionsByKey,
  ConnectionsGroupBy,
  Credential,
  CredentialInput,
  Database,
  DatabaseInput,
  Datasource,
  DatasourceImpact,
  DatasourceInput,
  DatasourceSummary,
  Dependency,
  DependencyInput,
  ExportDocument,
  Graph,
  GraphQuery,
  HotTable,
  Impact,
  LiveConnection,
  MigrationEvent,
  Overview,
  Page,
  Policy,
  PoolStats,
  QueryStat,
  Relationship,
  RelationshipInput,
  Routine,
  RoutineSummary,
  RoutingRule,
  RoutingRuleInput,
  SchemaInfo,
  Table,
  TableQuery,
  TableRef,
  TableSummary,
  TableUpdate,
  Team,
  TeamInput,
  TeamSummary,
  TestConnectionResult,
  TopQueriesQuery,
  Violation,
  ViolationStatus,
} from './types';

export const teams = {
  list: () => http.get<Team[]>('/teams'),
  get: (id: string) => http.get<Team>(`/teams/${id}`),
  summary: (id: string) => http.get<TeamSummary>(`/teams/${id}/summary`),
  create: (body: TeamInput) => http.post<Team>('/teams', body),
  update: (id: string, body: Partial<TeamInput>) => http.put<Team>(`/teams/${id}`, body),
  remove: (id: string) => http.delete(`/teams/${id}`),
};

export const applications = {
  list: () => http.get<Application[]>('/applications'),
  get: (id: string) => http.get<Application>(`/applications/${id}`),
  summary: (id: string) => http.get<ApplicationSummary>(`/applications/${id}/summary`),
  create: (body: ApplicationInput) => http.post<Application>('/applications', body),
  update: (id: string, body: Partial<ApplicationInput>) => http.put<Application>(`/applications/${id}`, body),
  remove: (id: string) => http.delete(`/applications/${id}`),
  apiKeys: (id: string) => http.get<ApiKey[]>(`/applications/${id}/api-keys`),
  createApiKey: (id: string, label: string) => http.post<ApiKeyCreated>(`/applications/${id}/api-keys`, { label }),
  revokeApiKey: (id: string, keyId: string) => http.delete(`/applications/${id}/api-keys/${keyId}`),
};

export const databases = {
  list: () => http.get<Database[]>('/databases'),
  get: (id: string) => http.get<Database>(`/databases/${id}`),
  create: (body: DatabaseInput) => http.post<Database>('/databases', body),
  update: (id: string, body: Partial<DatabaseInput>) => http.put<Database>(`/databases/${id}`, body),
  remove: (id: string) => http.delete(`/databases/${id}`),
  testConnection: (id: string) => http.post<TestConnectionResult>(`/databases/${id}/test-connection`),
  collect: (id: string, what: CollectWhat) => http.post<{ started: boolean }>(`/databases/${id}/collect`, { what }),
  schemas: (id: string) => http.get<SchemaInfo[]>(`/databases/${id}/schemas`),
  collectorStatus: (id: string) => http.get<CollectorStatus>(`/databases/${id}/collector-status`),
};

export const credentials = {
  list: () => http.get<Credential[]>('/credentials'),
  get: (id: string) => http.get<Credential>(`/credentials/${id}`),
  create: (body: CredentialInput) => http.post<Credential>('/credentials', body),
  update: (id: string, body: Partial<CredentialInput>) => http.put<Credential>(`/credentials/${id}`, body),
  remove: (id: string) => http.delete(`/credentials/${id}`),
  rotate: (id: string, secret?: string) => http.post<Credential>(`/credentials/${id}/rotate`, secret ? { secret } : {}),
};

export const datasources = {
  list: () => http.get<Datasource[]>('/datasources'),
  get: (id: string) => http.get<Datasource>(`/datasources/${id}`),
  summary: (id: string) => http.get<DatasourceSummary>(`/datasources/${id}/summary`),
  create: (body: DatasourceInput) => http.post<Datasource>('/datasources', body),
  update: (id: string, body: Partial<DatasourceInput>) => http.put<Datasource>(`/datasources/${id}`, body),
  remove: (id: string) => http.delete(`/datasources/${id}`),
  replaceRoutingRules: (id: string, rules: RoutingRuleInput[]) => http.put<RoutingRule[]>(`/datasources/${id}/routing-rules`, rules),
  addRoutingRule: (id: string, rule: RoutingRuleInput) => http.post<RoutingRule>(`/datasources/${id}/routing-rules`, rule),
  deleteRoutingRule: (id: string, ruleId: string) => http.delete(`/datasources/${id}/routing-rules/${ruleId}`),
  switch: (id: string, databaseId: string, note?: string) => http.post<Datasource>(`/datasources/${id}/switch`, { databaseId, note }),
  impact: (id: string) => http.get<DatasourceImpact>(`/impact/datasource/${id}`),
};

export const migrationEvents = {
  list: (datasourceId?: string) => http.get<MigrationEvent[]>('/migration-events', { datasourceId }),
};

export const accessGrants = {
  list: (q?: { applicationId?: string; datasourceId?: string }) => http.get<AccessGrant[]>('/access-grants', q),
  create: (body: AccessGrantInput) => http.post<AccessGrant>('/access-grants', body),
  update: (id: string, body: Partial<AccessGrantInput>) => http.put<AccessGrant>(`/access-grants/${id}`, body),
  remove: (id: string) => http.delete(`/access-grants/${id}`),
};

export const tables = {
  list: (q: TableQuery) => http.get<Page<Table>>('/tables', { ...q, page: q.page ?? 0, size: q.size ?? 50 }),
  listAll: (q: Omit<TableQuery, 'page' | 'size'> = {}) => http.get<Table[]>('/tables', { ...q }),
  get: (id: string) => http.get<Table>(`/tables/${id}`),
  update: (id: string, body: TableUpdate) => http.put<Table>(`/tables/${id}`, body),
  columns: (id: string) => http.get<Column[]>(`/tables/${id}/columns`),
  updateColumn: (id: string, columnId: string, body: Partial<Pick<Column, 'comment' | 'classification'>>) =>
    http.put<Column>(`/tables/${id}/columns/${columnId}`, body),
  summary: (id: string) => http.get<TableSummary>(`/tables/${id}/summary`),
  ownership: (id: string, teamId: string | null, confirmed: boolean) =>
    http.post<Table>(`/tables/${id}/ownership`, { teamId, confirmed }),
  bulkOwnership: (databaseId: string, schema: string, teamId: string) =>
    http.post<{ updated: number }>('/tables/bulk-ownership', { databaseId, schema, teamId }),
  impact: (id: string) => http.get<Impact>(`/impact/table/${id}`),
  columnImpact: (columnId: string) => http.get<Impact>(`/impact/column/${columnId}`),
  unused: (days = 30) => http.get<TableRef[]>('/stats/tables/unused', { days }),
};

export const routines = {
  list: (q: { databaseId?: string; schema?: string; kind?: string; q?: string } = {}) => http.get<Routine[]>('/routines', q),
  get: (id: string) => http.get<Routine>(`/routines/${id}`),
  summary: (id: string) => http.get<RoutineSummary>(`/routines/${id}/summary`),
};

export const dependencies = {
  list: (q: { fromId?: string; toId?: string; kind?: string } = {}) => http.get<Dependency[]>('/dependencies', q),
  create: (body: DependencyInput) => http.post<Dependency>('/dependencies', body),
  remove: (id: string) => http.delete(`/dependencies/${id}`),
};

export const relationships = {
  list: (q: { applicationId?: string; objectId?: string; kind?: string; source?: string } = {}) =>
    http.get<Relationship[]>('/relationships', q),
  create: (body: RelationshipInput) => http.post<Relationship>('/relationships', body),
  confirm: (id: string, confirmed: boolean) => http.put<Relationship>(`/relationships/${id}`, { confirmed }),
  remove: (id: string) => http.delete(`/relationships/${id}`),
};

export const graph = {
  get: (q: GraphQuery) =>
    http.get<Graph>('/graph', {
      root: q.root,
      depth: q.depth,
      include: q.include,
      edgeKinds: q.edgeKinds,
      limit: q.limit,
    }),
};

export const stats = {
  overview: () => http.get<Overview>('/stats/overview'),
  connections: (groupBy: ConnectionsGroupBy) => http.get<ConnectionsByKey[]>('/stats/connections', { groupBy }),
  topQueries: (q: TopQueriesQuery = {}) => http.get<QueryStat[]>('/stats/queries/top', { ...q }),
  hotTables: (window = '24h', limit = 20) => http.get<HotTable[]>('/stats/tables/hot', { window, limit }),
  pools: () => http.get<PoolStats[]>('/stats/pools'),
  liveConnections: () => http.get<LiveConnection[]>('/connections/live'),
};

export const governance = {
  policies: () => http.get<Policy[]>('/governance/policies'),
  updatePolicy: (id: string, body: Partial<Pick<Policy, 'enabled' | 'severity'>>) => http.put<Policy>(`/governance/policies/${id}`, body),
  violations: (status?: ViolationStatus) => http.get<Violation[]>('/governance/violations', { status }),
  updateViolation: (id: string, status: ViolationStatus) => http.put<Violation>(`/governance/violations/${id}`, { status }),
  evaluate: () => http.post<{ violations: number }>('/governance/evaluate'),
};

export const components = {
  list: () => http.get<ComponentInfo[]>('/components'),
};

export const admin = {
  exportAll: () => http.get<ExportDocument>('/export'),
  importAll: (doc: ExportDocument) => http.post<{ imported: Record<string, number> }>('/import', doc),
  seedDemo: () => http.post<{ seeded: boolean }>('/seed/demo'),
};
