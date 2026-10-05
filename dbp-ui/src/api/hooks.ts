/**
 * React Query hooks, one group per resource. Query keys are namespaced by resource so that
 * mutations can invalidate precisely.
 */
import { useMutation, useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query';
import * as api from './resources';
import type {
  AccessGrantInput,
  ApplicationInput,
  CollectWhat,
  ConnectionsGroupBy,
  CredentialInput,
  DatabaseInput,
  DatasourceInput,
  DependencyInput,
  ExportDocument,
  GraphQuery,
  Policy,
  RelationshipInput,
  RoutingRuleInput,
  TableQuery,
  TableUpdate,
  TeamInput,
  TopQueriesQuery,
  ViolationStatus,
} from './types';

export const keys = {
  teams: ['teams'] as const,
  team: (id: string) => ['teams', id] as const,
  teamSummary: (id: string) => ['teams', id, 'summary'] as const,
  applications: ['applications'] as const,
  application: (id: string) => ['applications', id] as const,
  applicationSummary: (id: string) => ['applications', id, 'summary'] as const,
  apiKeys: (id: string) => ['applications', id, 'api-keys'] as const,
  databases: ['databases'] as const,
  database: (id: string) => ['databases', id] as const,
  schemas: (id: string) => ['databases', id, 'schemas'] as const,
  collectorStatus: (id: string) => ['databases', id, 'collector-status'] as const,
  credentials: ['credentials'] as const,
  datasources: ['datasources'] as const,
  datasource: (id: string) => ['datasources', id] as const,
  datasourceSummary: (id: string) => ['datasources', id, 'summary'] as const,
  datasourceImpact: (id: string) => ['impact', 'datasource', id] as const,
  migrationEvents: (id?: string) => ['migration-events', id ?? 'all'] as const,
  grants: (q?: { applicationId?: string; datasourceId?: string }) => ['access-grants', q ?? {}] as const,
  tables: (q: TableQuery) => ['tables', q] as const,
  tablesAll: (q: object) => ['tables', 'all', q] as const,
  table: (id: string) => ['tables', id] as const,
  tableSummary: (id: string) => ['tables', id, 'summary'] as const,
  columns: (id: string) => ['tables', id, 'columns'] as const,
  tableImpact: (id: string) => ['impact', 'table', id] as const,
  columnImpact: (id: string) => ['impact', 'column', id] as const,
  unusedTables: (days: number) => ['tables', 'unused', days] as const,
  routines: (q: object) => ['routines', q] as const,
  routine: (id: string) => ['routines', id] as const,
  routineSummary: (id: string) => ['routines', id, 'summary'] as const,
  dependencies: (q: object) => ['dependencies', q] as const,
  relationships: (q: object) => ['relationships', q] as const,
  graph: (q: GraphQuery) => ['graph', q] as const,
  overview: ['stats', 'overview'] as const,
  connections: (g: ConnectionsGroupBy) => ['stats', 'connections', g] as const,
  topQueries: (q: TopQueriesQuery) => ['stats', 'queries', 'top', q] as const,
  hotTables: (w: string, l: number) => ['stats', 'tables', 'hot', w, l] as const,
  pools: ['stats', 'pools'] as const,
  live: ['connections', 'live'] as const,
  policies: ['governance', 'policies'] as const,
  violations: (s?: ViolationStatus) => ['governance', 'violations', s ?? 'all'] as const,
  components: ['components'] as const,
  exportAll: ['export'] as const,
};

type Opts<T> = Omit<UseQueryOptions<T, Error, T, readonly unknown[]>, 'queryKey' | 'queryFn'>;

const LIVE = { refetchInterval: 15_000 } as const;

// ------------------------------------------------------------------ teams
export const useTeams = () => useQuery({ queryKey: keys.teams, queryFn: api.teams.list });
export const useTeam = (id: string) => useQuery({ queryKey: keys.team(id), queryFn: () => api.teams.get(id), enabled: !!id });
export const useTeamSummary = (id: string) =>
  useQuery({ queryKey: keys.teamSummary(id), queryFn: () => api.teams.summary(id), enabled: !!id });
export function useTeamMutations() {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: keys.teams });
  return {
    create: useMutation({ mutationFn: (b: TeamInput) => api.teams.create(b), onSuccess: invalidate }),
    update: useMutation({ mutationFn: (v: { id: string; body: Partial<TeamInput> }) => api.teams.update(v.id, v.body), onSuccess: invalidate }),
    remove: useMutation({ mutationFn: (id: string) => api.teams.remove(id), onSuccess: invalidate }),
  };
}

// ------------------------------------------------------------------ applications
export const useApplications = () => useQuery({ queryKey: keys.applications, queryFn: api.applications.list });
export const useApplication = (id: string) =>
  useQuery({ queryKey: keys.application(id), queryFn: () => api.applications.get(id), enabled: !!id });
export const useApplicationSummary = (id: string) =>
  useQuery({ queryKey: keys.applicationSummary(id), queryFn: () => api.applications.summary(id), enabled: !!id });
export const useApiKeys = (id: string) =>
  useQuery({ queryKey: keys.apiKeys(id), queryFn: () => api.applications.apiKeys(id), enabled: !!id });
export function useApplicationMutations(id?: string) {
  const qc = useQueryClient();
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: keys.applications });
    qc.invalidateQueries({ queryKey: ['relationships'] });
  };
  return {
    create: useMutation({ mutationFn: (b: ApplicationInput) => api.applications.create(b), onSuccess: invalidate }),
    update: useMutation({
      mutationFn: (v: { id: string; body: Partial<ApplicationInput> }) => api.applications.update(v.id, v.body),
      onSuccess: invalidate,
    }),
    remove: useMutation({ mutationFn: (appId: string) => api.applications.remove(appId), onSuccess: invalidate }),
    createApiKey: useMutation({
      mutationFn: (label: string) => api.applications.createApiKey(id!, label),
      onSuccess: () => qc.invalidateQueries({ queryKey: keys.apiKeys(id!) }),
    }),
    revokeApiKey: useMutation({
      mutationFn: (keyId: string) => api.applications.revokeApiKey(id!, keyId),
      onSuccess: () => qc.invalidateQueries({ queryKey: keys.apiKeys(id!) }),
    }),
  };
}

// ------------------------------------------------------------------ databases
export const useDatabases = () => useQuery({ queryKey: keys.databases, queryFn: api.databases.list });
export const useDatabase = (id: string) =>
  useQuery({ queryKey: keys.database(id), queryFn: () => api.databases.get(id), enabled: !!id });
export const useSchemas = (id: string) =>
  useQuery({ queryKey: keys.schemas(id), queryFn: () => api.databases.schemas(id), enabled: !!id });
export const useCollectorStatus = (id: string) =>
  useQuery({ queryKey: keys.collectorStatus(id), queryFn: () => api.databases.collectorStatus(id), enabled: !!id, ...LIVE });
export function useDatabaseMutations() {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: keys.databases });
  return {
    create: useMutation({ mutationFn: (b: DatabaseInput) => api.databases.create(b), onSuccess: invalidate }),
    update: useMutation({ mutationFn: (v: { id: string; body: Partial<DatabaseInput> }) => api.databases.update(v.id, v.body), onSuccess: invalidate }),
    remove: useMutation({ mutationFn: (id: string) => api.databases.remove(id), onSuccess: invalidate }),
    testConnection: useMutation({ mutationFn: (id: string) => api.databases.testConnection(id) }),
    collect: useMutation({
      mutationFn: (v: { id: string; what: CollectWhat }) => api.databases.collect(v.id, v.what),
      onSuccess: (_r, v) => qc.invalidateQueries({ queryKey: keys.collectorStatus(v.id) }),
    }),
  };
}

// ------------------------------------------------------------------ credentials
export const useCredentials = () => useQuery({ queryKey: keys.credentials, queryFn: api.credentials.list });
export function useCredentialMutations() {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: keys.credentials });
  return {
    create: useMutation({ mutationFn: (b: CredentialInput) => api.credentials.create(b), onSuccess: invalidate }),
    update: useMutation({ mutationFn: (v: { id: string; body: Partial<CredentialInput> }) => api.credentials.update(v.id, v.body), onSuccess: invalidate }),
    remove: useMutation({ mutationFn: (id: string) => api.credentials.remove(id), onSuccess: invalidate }),
    rotate: useMutation({ mutationFn: (v: { id: string; secret?: string }) => api.credentials.rotate(v.id, v.secret), onSuccess: invalidate }),
  };
}

// ------------------------------------------------------------------ datasources
export const useDatasources = () => useQuery({ queryKey: keys.datasources, queryFn: api.datasources.list });
export const useDatasource = (id: string) =>
  useQuery({ queryKey: keys.datasource(id), queryFn: () => api.datasources.get(id), enabled: !!id });
export const useDatasourceSummary = (id: string) =>
  useQuery({ queryKey: keys.datasourceSummary(id), queryFn: () => api.datasources.summary(id), enabled: !!id });
export const useDatasourceImpact = (id: string, enabled = true) =>
  useQuery({ queryKey: keys.datasourceImpact(id), queryFn: () => api.datasources.impact(id), enabled: !!id && enabled });
export const useMigrationEvents = (datasourceId?: string) =>
  useQuery({ queryKey: keys.migrationEvents(datasourceId), queryFn: () => api.migrationEvents.list(datasourceId) });
export function useDatasourceMutations(id?: string) {
  const qc = useQueryClient();
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: keys.datasources });
    qc.invalidateQueries({ queryKey: ['migration-events'] });
    qc.invalidateQueries({ queryKey: ['impact'] });
  };
  return {
    create: useMutation({ mutationFn: (b: DatasourceInput) => api.datasources.create(b), onSuccess: invalidate }),
    update: useMutation({ mutationFn: (v: { id: string; body: Partial<DatasourceInput> }) => api.datasources.update(v.id, v.body), onSuccess: invalidate }),
    remove: useMutation({ mutationFn: (dsId: string) => api.datasources.remove(dsId), onSuccess: invalidate }),
    replaceRules: useMutation({ mutationFn: (rules: RoutingRuleInput[]) => api.datasources.replaceRoutingRules(id!, rules), onSuccess: invalidate }),
    addRule: useMutation({ mutationFn: (rule: RoutingRuleInput) => api.datasources.addRoutingRule(id!, rule), onSuccess: invalidate }),
    deleteRule: useMutation({ mutationFn: (ruleId: string) => api.datasources.deleteRoutingRule(id!, ruleId), onSuccess: invalidate }),
    switch: useMutation({
      mutationFn: (v: { databaseId: string; note?: string }) => api.datasources.switch(id!, v.databaseId, v.note),
      onSuccess: invalidate,
    }),
  };
}

// ------------------------------------------------------------------ access grants
export const useAccessGrants = (q?: { applicationId?: string; datasourceId?: string }) =>
  useQuery({ queryKey: keys.grants(q), queryFn: () => api.accessGrants.list(q) });
export function useAccessGrantMutations() {
  const qc = useQueryClient();
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['access-grants'] });
    qc.invalidateQueries({ queryKey: keys.datasources });
    qc.invalidateQueries({ queryKey: keys.applications });
  };
  return {
    create: useMutation({ mutationFn: (b: AccessGrantInput) => api.accessGrants.create(b), onSuccess: invalidate }),
    update: useMutation({ mutationFn: (v: { id: string; body: Partial<AccessGrantInput> }) => api.accessGrants.update(v.id, v.body), onSuccess: invalidate }),
    remove: useMutation({ mutationFn: (id: string) => api.accessGrants.remove(id), onSuccess: invalidate }),
  };
}

// ------------------------------------------------------------------ tables
export const useTables = (q: TableQuery, opts?: Opts<Awaited<ReturnType<typeof api.tables.list>>>) =>
  useQuery({ queryKey: keys.tables(q), queryFn: () => api.tables.list(q), placeholderData: (prev) => prev, ...opts });
export const useAllTables = (q: Omit<TableQuery, 'page' | 'size'> = {}) =>
  useQuery({ queryKey: keys.tablesAll(q), queryFn: () => api.tables.listAll(q) });
export const useTable = (id: string) => useQuery({ queryKey: keys.table(id), queryFn: () => api.tables.get(id), enabled: !!id });
export const useTableSummary = (id: string) =>
  useQuery({ queryKey: keys.tableSummary(id), queryFn: () => api.tables.summary(id), enabled: !!id });
export const useColumns = (id: string) =>
  useQuery({ queryKey: keys.columns(id), queryFn: () => api.tables.columns(id), enabled: !!id });
export const useTableImpact = (id: string) =>
  useQuery({ queryKey: keys.tableImpact(id), queryFn: () => api.tables.impact(id), enabled: !!id });
export const useColumnImpact = (id: string) =>
  useQuery({ queryKey: keys.columnImpact(id), queryFn: () => api.tables.columnImpact(id), enabled: !!id });
export const useUnusedTables = (days = 30) => useQuery({ queryKey: keys.unusedTables(days), queryFn: () => api.tables.unused(days) });
export function useTableMutations() {
  const qc = useQueryClient();
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['tables'] });
    qc.invalidateQueries({ queryKey: ['impact'] });
    qc.invalidateQueries({ queryKey: keys.teams });
    qc.invalidateQueries({ queryKey: keys.overview });
  };
  return {
    update: useMutation({ mutationFn: (v: { id: string; body: TableUpdate }) => api.tables.update(v.id, v.body), onSuccess: invalidate }),
    ownership: useMutation({
      mutationFn: (v: { id: string; teamId: string | null; confirmed: boolean }) => api.tables.ownership(v.id, v.teamId, v.confirmed),
      onSuccess: invalidate,
    }),
    bulkOwnership: useMutation({
      mutationFn: (v: { databaseId: string; schema: string; teamId: string }) => api.tables.bulkOwnership(v.databaseId, v.schema, v.teamId),
      onSuccess: invalidate,
    }),
    updateColumn: useMutation({
      mutationFn: (v: { tableId: string; columnId: string; body: { comment?: string | null; classification?: string | null } }) =>
        api.tables.updateColumn(v.tableId, v.columnId, v.body as never),
      onSuccess: (_r, v) => qc.invalidateQueries({ queryKey: keys.columns(v.tableId) }),
    }),
  };
}

// ------------------------------------------------------------------ routines / dependencies / relationships
export const useRoutines = (q: { databaseId?: string; schema?: string; kind?: string; q?: string } = {}) =>
  useQuery({ queryKey: keys.routines(q), queryFn: () => api.routines.list(q) });
export const useRoutine = (id: string) => useQuery({ queryKey: keys.routine(id), queryFn: () => api.routines.get(id), enabled: !!id });
export const useRoutineSummary = (id: string) =>
  useQuery({ queryKey: keys.routineSummary(id), queryFn: () => api.routines.summary(id), enabled: !!id });
export const useDependencies = (q: { fromId?: string; toId?: string; kind?: string } = {}, enabled = true) =>
  useQuery({ queryKey: keys.dependencies(q), queryFn: () => api.dependencies.list(q), enabled });
export const useRelationships = (q: { applicationId?: string; objectId?: string; kind?: string; source?: string } = {}, enabled = true) =>
  useQuery({ queryKey: keys.relationships(q), queryFn: () => api.relationships.list(q), enabled });
export function useRelationshipMutations() {
  const qc = useQueryClient();
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['relationships'] });
    qc.invalidateQueries({ queryKey: ['tables'] });
    qc.invalidateQueries({ queryKey: ['impact'] });
    qc.invalidateQueries({ queryKey: keys.applications });
  };
  return {
    create: useMutation({ mutationFn: (b: RelationshipInput) => api.relationships.create(b), onSuccess: invalidate }),
    confirm: useMutation({ mutationFn: (v: { id: string; confirmed: boolean }) => api.relationships.confirm(v.id, v.confirmed), onSuccess: invalidate }),
    remove: useMutation({ mutationFn: (id: string) => api.relationships.remove(id), onSuccess: invalidate }),
  };
}
export function useDependencyMutations() {
  const qc = useQueryClient();
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['dependencies'] });
    qc.invalidateQueries({ queryKey: ['routines'] });
    qc.invalidateQueries({ queryKey: ['tables'] });
  };
  return {
    create: useMutation({ mutationFn: (b: DependencyInput) => api.dependencies.create(b), onSuccess: invalidate }),
    remove: useMutation({ mutationFn: (id: string) => api.dependencies.remove(id), onSuccess: invalidate }),
  };
}

// ------------------------------------------------------------------ graph
export const useGraph = (q: GraphQuery, enabled = true) =>
  useQuery({ queryKey: keys.graph(q), queryFn: () => api.graph.get(q), enabled, placeholderData: (prev) => prev });

// ------------------------------------------------------------------ stats
export const useOverview = () => useQuery({ queryKey: keys.overview, queryFn: api.stats.overview, ...LIVE });
export const useConnectionsBy = (g: ConnectionsGroupBy) =>
  useQuery({ queryKey: keys.connections(g), queryFn: () => api.stats.connections(g), ...LIVE });
export const useTopQueries = (q: TopQueriesQuery = {}) =>
  useQuery({ queryKey: keys.topQueries(q), queryFn: () => api.stats.topQueries(q), placeholderData: (prev) => prev });
export const useHotTables = (window = '24h', limit = 20) =>
  useQuery({ queryKey: keys.hotTables(window, limit), queryFn: () => api.stats.hotTables(window, limit) });
export const usePools = () => useQuery({ queryKey: keys.pools, queryFn: api.stats.pools, ...LIVE });
export const useLiveConnections = () => useQuery({ queryKey: keys.live, queryFn: api.stats.liveConnections, refetchInterval: 10_000 });

// ------------------------------------------------------------------ governance
export const usePolicies = () => useQuery({ queryKey: keys.policies, queryFn: api.governance.policies });
export const useViolations = (status?: ViolationStatus) =>
  useQuery({ queryKey: keys.violations(status), queryFn: () => api.governance.violations(status) });
export function useGovernanceMutations() {
  const qc = useQueryClient();
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['governance'] });
    qc.invalidateQueries({ queryKey: keys.overview });
  };
  return {
    updatePolicy: useMutation({
      mutationFn: (v: { id: string; body: Partial<Pick<Policy, 'enabled' | 'severity'>> }) => api.governance.updatePolicy(v.id, v.body),
      onSuccess: invalidate,
    }),
    updateViolation: useMutation({
      mutationFn: (v: { id: string; status: ViolationStatus }) => api.governance.updateViolation(v.id, v.status),
      onSuccess: invalidate,
    }),
    evaluate: useMutation({ mutationFn: () => api.governance.evaluate(), onSuccess: invalidate }),
  };
}

// ------------------------------------------------------------------ components / admin
export const useComponents = () => useQuery({ queryKey: keys.components, queryFn: api.components.list, ...LIVE });
export function useAdminMutations() {
  const qc = useQueryClient();
  return {
    exportAll: useMutation({ mutationFn: () => api.admin.exportAll() }),
    importAll: useMutation({ mutationFn: (doc: ExportDocument) => api.admin.importAll(doc), onSuccess: () => qc.invalidateQueries() }),
    seedDemo: useMutation({ mutationFn: () => api.admin.seedDemo(), onSuccess: () => qc.invalidateQueries() }),
  };
}
