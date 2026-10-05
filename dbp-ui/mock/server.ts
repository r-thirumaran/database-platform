/**
 * `npm run mock` — a dependency-free Node HTTP server that implements docs/control-plane-api.md
 * against the in-memory retail dataset in ./data.ts. Mutations work against the store; `POST /seed/demo`
 * resets it. If a `dist/` build exists it is served too, with an SPA fallback, so the mock can stand in
 * for the control plane end to end.
 */
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildDemoStore, type DemoStore } from './data';
import type {
  AccessGrant, ApiKey, Application, Column, Credential, Database, Datasource, Dependency, EdgeKind, Graph, GraphEdge, GraphNode,
  GraphNodeType, Impact, ImpactConsumer, Relationship, Routine, RoutineRef, RoutingRule, Table, TableRef, Team, QueryStat,
} from '../src/api/types';

const PORT = Number(process.env.PORT ?? 8080);
const API = '/api/v1';
const LATENCY_MS = Number(process.env.MOCK_LATENCY_MS ?? 120);
const here = fileURLToPath(new URL('.', import.meta.url));
const DIST = join(here, '..', 'dist');

let store: DemoStore = buildDemoStore();

// ------------------------------------------------------------------ helpers
class HttpError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}
const notFound = (what: string, id: string) => new HttpError(404, 'NOT_FOUND', `${what} ${id} not found`);
const bad = (message: string) => new HttpError(400, 'BAD_REQUEST', message);
const now = () => new Date().toISOString();
const byId = <T extends { id: string }>(list: T[], id: string, what: string): T => {
  const x = list.find((i) => i.id === id);
  if (!x) throw notFound(what, id);
  return x;
};
const tableRef = (t: Table): TableRef => ({ id: t.id, databaseId: t.databaseId, schema: t.schema, name: t.name, kind: t.kind });
const routineRef = (r: Routine): RoutineRef => ({ id: r.id, databaseId: r.databaseId, schema: r.schema, name: r.name, kind: r.kind });
const uniqueBy = <T>(list: T[], key: (t: T) => string): T[] => {
  const seen = new Set<string>();
  return list.filter((x) => (seen.has(key(x)) ? false : (seen.add(key(x)), true)));
};
const paged = <T>(items: T[], q: URLSearchParams) => {
  if (!q.has('page')) return items;
  const page = Number(q.get('page') ?? 0);
  const size = Math.min(500, Number(q.get('size') ?? 50));
  return { items: items.slice(page * size, page * size + size), page, size, total: items.length };
};
const team = (id: string | null) => (id ? store.teams.find((t) => t.id === id) ?? null : null);
const app = (id: string | null) => (id ? store.applications.find((a) => a.id === id) ?? null : null);
const tbl = (id: string) => store.tables.find((t) => t.id === id);
const rtn = (id: string) => store.routines.find((r) => r.id === id);
const db = (id: string | null) => (id ? store.databases.find((d) => d.id === id) ?? null : null);
const bump = () => (store.configVersion += 1);

function upsert<T extends { id: string; createdAt?: string; updatedAt?: string }>(list: T[], body: Partial<T>, defaults: Partial<T> = {}): T {
  const item = { ...defaults, ...body, id: randomUUID(), createdAt: now(), updatedAt: now() } as T;
  list.push(item);
  bump();
  return item;
}
function patch<T extends { id: string; updatedAt?: string }>(list: T[], id: string, body: Partial<T>, what: string): T {
  const idx = list.findIndex((i) => i.id === id);
  if (idx < 0) throw notFound(what, id);
  const next = { ...list[idx], ...body, id, updatedAt: now() } as T;
  list[idx] = next;
  bump();
  return next;
}
function remove<T extends { id: string }>(list: T[], id: string, what: string) {
  const idx = list.findIndex((i) => i.id === id);
  if (idx < 0) throw notFound(what, id);
  list.splice(idx, 1);
  bump();
}

// ------------------------------------------------------------------ derived views
const queryStatsForTable = (tableId: string) => store.queryStats.filter((q) => q.tables.some((t) => t.id === tableId));
function queryStatsSummary(stats: QueryStat[]) {
  const count7d = stats.reduce((s, q) => s + q.count, 0);
  const last = stats.map((q) => q.lastSeenAt).sort().at(-1) ?? null;
  const avg = stats.length ? stats.reduce((s, q) => s + q.avgDurationMs * q.count, 0) / Math.max(1, count7d) : 0;
  return { count24h: Math.round(count7d / 7), count7d, lastSeenAt: last, avgDurationMs: Math.round(avg * 10) / 10, errors24h: Math.round(stats.reduce((s, q) => s + q.errors, 0) / 7) };
}
const consumerOf = (r: Relationship): ImpactConsumer & { source: Relationship['source']; confidence: number; confirmed: boolean } => {
  const a = app(r.applicationId)!;
  const via = r.viaRoutineId ? rtn(r.viaRoutineId) : undefined;
  return { application: a, team: team(a.teamId), kind: r.kind, queryCount: r.queryCount, lastSeenAt: r.lastSeenAt, viaRoutine: via ? routineRef(via) : null, source: r.source, confidence: r.confidence ?? 1, confirmed: r.confirmed };
};

function tableSummary(t: Table) {
  const rels = store.relationships.filter((r) => r.objectType === 'TABLE' && r.objectId === t.id);
  const deps = store.dependencies;
  const routines = deps.filter((d) => d.toType === 'TABLE' && d.toId === t.id && d.fromType === 'ROUTINE').map((d) => rtn(d.fromId)).filter((r): r is Routine => !!r);
  const stats = queryStatsForTable(t.id);
  return {
    table: t,
    database: db(t.databaseId),
    ownerTeam: team(t.ownerTeamId),
    producer: app(t.producerApplicationId),
    consumers: rels.map(consumerOf),
    routines: routines.filter((r) => r.kind !== 'TRIGGER' && r.kind !== 'VIEW').map(routineRef),
    triggers: deps.filter((d) => d.fromType === 'TABLE' && d.fromId === t.id && d.kind === 'TRIGGERS').map((d) => rtn(d.toId)).filter((r): r is Routine => !!r).map(routineRef),
    views: routines.filter((r) => r.kind === 'VIEW').map(routineRef),
    foreignKeysOut: deps.filter((d) => d.fromType === 'TABLE' && d.fromId === t.id && d.kind === 'FOREIGN_KEY').map((d) => tbl(d.toId)).filter((x): x is Table => !!x).map(tableRef),
    foreignKeysIn: deps.filter((d) => d.toType === 'TABLE' && d.toId === t.id && d.kind === 'FOREIGN_KEY').map((d) => tbl(d.fromId)).filter((x): x is Table => !!x).map(tableRef),
    queryStats: queryStatsSummary(stats),
    topQueries: [...stats].sort((a, b) => b.count - a.count).slice(0, 10),
  };
}

function impactForTable(t: Table): Impact {
  const s = tableSummary(t);
  const direct = s.consumers.filter((c) => !c.viaRoutine);
  const indirect = s.consumers.filter((c) => !!c.viaRoutine);
  // applications calling routines that reference the table (even without an expanded relationship row)
  for (const r of [...s.routines, ...s.triggers, ...s.views]) {
    for (const rel of store.relationships.filter((x) => x.objectType === 'ROUTINE' && x.objectId === r.id)) {
      if (!indirect.some((c) => c.application.id === rel.applicationId && c.viaRoutine?.id === r.id)) {
        indirect.push({ ...consumerOf(rel), viaRoutine: r, kind: rel.kind === 'CALLS' ? 'WRITES' : rel.kind });
      }
    }
  }
  const teamsAffected = uniqueBy([...direct, ...indirect].map((c) => c.team).filter((x): x is Team => !!x), (x) => x.id);
  const factors: string[] = [];
  let score = 0;
  if (teamsAffected.length) { factors.push(`${teamsAffected.length} consuming team${teamsAffected.length > 1 ? 's' : ''}`); score += Math.min(0.35, teamsAffected.length * 0.1); }
  if (s.triggers.length) { factors.push(`written by ${s.triggers.length} trigger${s.triggers.length > 1 ? 's' : ''}`); score += 0.12; }
  if (s.routines.length) { factors.push(`${s.routines.length} routine${s.routines.length > 1 ? 's' : ''} reference it`); score += Math.min(0.15, s.routines.length * 0.05); }
  if (s.views.length) { factors.push(`${s.views.length} dependent view${s.views.length > 1 ? 's' : ''}`); score += 0.05; }
  if (s.foreignKeysIn.length) { factors.push(`${s.foreignKeysIn.length} foreign key dependent${s.foreignKeysIn.length > 1 ? 's' : ''}`); score += Math.min(0.1, s.foreignKeysIn.length * 0.04); }
  if (s.queryStats.count7d > 100_000) { factors.push('high query volume'); score += 0.12; } else if (s.queryStats.count7d > 10_000) { factors.push('moderate query volume'); score += 0.06; }
  if (t.classification === 'PII') { factors.push('PII'); score += 0.12; } else if (t.classification === 'CONFIDENTIAL') { factors.push('confidential data'); score += 0.06; }
  if (t.migration.state === 'IN_PROGRESS') { factors.push('migration in progress'); score += 0.1; }
  if (!t.ownerTeamId) { factors.push('no owner'); score += 0.08; }
  if (direct.some((c) => c.kind === 'WRITES' && c.application.id !== t.producerApplicationId)) { factors.push('written by a non-producer'); score += 0.08; }
  return {
    target: { type: 'TABLE', id: t.id, label: `${t.schema}.${t.name}` },
    owner: s.ownerTeam, producer: s.producer,
    directConsumers: direct, indirectConsumers: indirect,
    routines: s.routines, triggers: s.triggers, dependentViews: s.views, foreignKeyDependents: s.foreignKeysIn,
    teamsAffected, queryStats: s.queryStats,
    riskScore: Math.round(Math.min(1, score) * 100) / 100, riskFactors: factors,
  };
}

function impactForDatasource(ds: Datasource) {
  const dbId = ds.currentDatabaseId;
  const grantedApps = new Set(store.accessGrants.filter((g) => g.datasourceId === ds.id && g.enabled).map((g) => g.applicationId));
  const tables = store.tables.filter((t) => t.databaseId === dbId && store.relationships.some((r) => r.objectType === 'TABLE' && r.objectId === t.id && grantedApps.has(r.applicationId)));
  const perTable = tables.map((t) => {
    const rels = store.relationships.filter((r) => r.objectType === 'TABLE' && r.objectId === t.id && grantedApps.has(r.applicationId));
    return { table: tableRef(t), consumers: rels.map(consumerOf), queryCount: rels.reduce((s, r) => s + r.queryCount, 0) };
  });
  const all = perTable.flatMap((p) => p.consumers);
  const direct = uniqueBy(all.filter((c) => !c.viaRoutine), (c) => `${c.application.id}:${c.kind}`);
  const indirect = uniqueBy(all.filter((c) => !!c.viaRoutine), (c) => `${c.application.id}:${c.kind}:${c.viaRoutine?.id}`);
  const teamsAffected = uniqueBy(all.map((c) => c.team).filter((x): x is Team => !!x), (x) => x.id);
  const routines = uniqueBy(tables.flatMap((t) => tableSummary(t).routines), (r) => r.id);
  const triggers = uniqueBy(tables.flatMap((t) => tableSummary(t).triggers), (r) => r.id);
  const views = uniqueBy(tables.flatMap((t) => tableSummary(t).views), (r) => r.id);
  const stats = queryStatsSummary(store.queryStats.filter((q) => q.tables.some((x) => tables.some((t) => t.id === x.id))));
  const factors: string[] = [`${tables.length} tables routed`, `${teamsAffected.length} teams affected`];
  let score = Math.min(0.4, teamsAffected.length * 0.1) + Math.min(0.2, tables.length * 0.03);
  if (triggers.length) { factors.push(`${triggers.length} triggers in the write path`); score += 0.12; }
  if (all.some((c) => c.kind === 'WRITES')) { factors.push('writers present'); score += 0.1; }
  if (tables.some((t) => t.classification === 'PII')) { factors.push('PII tables'); score += 0.1; }
  if (ds.routingRules.some((r) => r.enabled)) { factors.push(`${ds.routingRules.filter((r) => r.enabled).length} routing rule(s) already divert traffic`); }
  return {
    target: { type: 'DATASOURCE' as const, id: ds.id, label: ds.name },
    owner: team(ds.ownerTeamId), producer: null,
    directConsumers: direct, indirectConsumers: indirect,
    routines, triggers, dependentViews: views, foreignKeyDependents: [],
    teamsAffected, queryStats: stats, riskScore: Math.round(Math.min(1, score) * 100) / 100, riskFactors: factors,
    tables: perTable.sort((a, b) => b.queryCount - a.queryCount),
  };
}

function buildGraph(q: URLSearchParams): Graph {
  const include = new Set((q.get('include') ?? 'teams,applications,datasources,databases,tables,routines').split(',').map((s) => s.trim().toLowerCase()));
  const edgeKinds = new Set<EdgeKind>((q.get('edgeKinds') ?? 'OWNS,READS,WRITES,CALLS,REFERENCES,FOREIGN_KEY,TRIGGERS,HOSTS,MIGRATES_TO,BELONGS_TO').split(',').map((s) => s.trim().toUpperCase() as EdgeKind));
  const limit = Number(q.get('limit') ?? 500);
  const depth = Number(q.get('depth') ?? 2);
  const root = q.get('root');

  const nodes = new Map<string, GraphNode>();
  const edges: GraphEdge[] = [];
  const nid = (type: GraphNodeType, id: string) => `${type.toLowerCase()}:${id}`;
  const add = (type: GraphNodeType, refId: string, label: string, attrs: Record<string, unknown>) => nodes.set(nid(type, refId), { id: nid(type, refId), type, label, refId, attrs });
  const edge = (from: string, to: string, kind: EdgeKind, attrs: Record<string, unknown> = {}) => edges.push({ id: `${from}-${kind}-${to}`, from, to, kind, attrs });

  const relCount = (type: 'TABLE' | 'ROUTINE', id: string) => store.relationships.filter((r) => r.objectType === type && r.objectId === id).reduce((s, r) => s + r.queryCount, 0);
  for (const t of store.teams) add('TEAM', t.id, t.name, { displayName: t.displayName, applications: store.applications.filter((a) => a.teamId === t.id).length });
  for (const a of store.applications) add('APPLICATION', a.id, a.name, { kind: a.kind, runtime: a.runtime, team: team(a.teamId)?.name ?? null, tags: a.tags });
  for (const d of store.datasources) add('DATASOURCE', d.id, d.name, { state: d.state, ownerTeam: team(d.ownerTeamId)?.name ?? null, currentDatabase: db(d.currentDatabaseId)?.name ?? null, targetDatabase: db(d.targetDatabaseId)?.name ?? null });
  for (const d of store.databases) add('DATABASE', d.id, d.name, { engine: d.engine, host: d.host, port: d.port });
  for (const t of store.tables) add('TABLE', t.id, `${t.schema}.${t.name}`, { engine: db(t.databaseId)?.engine, kind: t.kind, ownerTeam: team(t.ownerTeamId)?.name ?? null, classification: t.classification, queryCount: relCount('TABLE', t.id), migrationState: t.migration.state, discovered: !!t.discovered });
  for (const r of store.routines) add('ROUTINE', r.id, `${r.schema}.${r.name}`, { engine: db(r.databaseId)?.engine, kind: r.kind, status: r.status, ownerTeam: team(r.ownerTeamId)?.name ?? null, queryCount: relCount('ROUTINE', r.id) });

  for (const a of store.applications) if (a.teamId) edge(nid('APPLICATION', a.id), nid('TEAM', a.teamId), 'BELONGS_TO');
  for (const d of store.datasources) {
    if (d.ownerTeamId) edge(nid('TEAM', d.ownerTeamId), nid('DATASOURCE', d.id), 'OWNS');
    edge(nid('DATABASE', d.currentDatabaseId), nid('DATASOURCE', d.id), 'HOSTS', { current: true });
    if (d.targetDatabaseId) edge(nid('DATASOURCE', d.id), nid('DATABASE', d.targetDatabaseId), 'MIGRATES_TO', { state: d.state });
  }
  for (const t of store.tables) {
    edge(nid('DATABASE', t.databaseId), nid('TABLE', t.id), 'HOSTS');
    if (t.ownerTeamId) edge(nid('TEAM', t.ownerTeamId), nid('TABLE', t.id), 'OWNS', { confirmed: t.ownerConfirmed, source: t.ownerSource });
    if (t.migration.targetDatabaseId && t.migration.targetName) {
      const target = store.tables.find((x) => x.databaseId === t.migration.targetDatabaseId && x.name === t.migration.targetName && x.schema === t.migration.targetSchema);
      if (target) edge(nid('TABLE', t.id), nid('TABLE', target.id), 'MIGRATES_TO', { state: t.migration.state });
    }
  }
  for (const r of store.routines) {
    edge(nid('DATABASE', r.databaseId), nid('ROUTINE', r.id), 'HOSTS');
    if (r.ownerTeamId) edge(nid('TEAM', r.ownerTeamId), nid('ROUTINE', r.id), 'OWNS');
  }
  for (const d of store.dependencies) edge(nid(d.fromType, d.fromId), nid(d.toType, d.toId), d.kind, { source: d.source, confidence: d.confidence, lastSeenAt: d.lastSeenAt });
  for (const r of store.relationships) {
    if (r.viaRoutineId) continue; // indirect access is visible through the routine's own edges
    edge(nid('APPLICATION', r.applicationId), nid(r.objectType, r.objectId), r.kind, { queryCount: r.queryCount, lastSeenAt: r.lastSeenAt, source: r.source, confirmed: r.confirmed, confidence: r.confidence });
  }

  const typeIncluded = (t: GraphNodeType) => include.has(`${t.toLowerCase()}s`);
  let keptNodes = [...nodes.values()].filter((n) => typeIncluded(n.type));
  let keptEdges = edges.filter((e) => edgeKinds.has(e.kind));

  if (root) {
    if (!nodes.has(root)) throw notFound('graph root', root);
    const adj = new Map<string, Set<string>>();
    for (const e of keptEdges) {
      if (!adj.has(e.from)) adj.set(e.from, new Set());
      if (!adj.has(e.to)) adj.set(e.to, new Set());
      adj.get(e.from)!.add(e.to);
      adj.get(e.to)!.add(e.from);
    }
    const allowed = new Set(keptNodes.map((n) => n.id));
    allowed.add(root);
    const dist = new Map<string, number>([[root, 0]]);
    const queue = [root];
    while (queue.length) {
      const cur = queue.shift()!;
      const d = dist.get(cur)!;
      if (d >= depth) continue;
      for (const nxt of adj.get(cur) ?? []) {
        if (!allowed.has(nxt) || dist.has(nxt)) continue;
        dist.set(nxt, d + 1);
        queue.push(nxt);
      }
    }
    keptNodes = [...nodes.values()].filter((n) => dist.has(n.id)).map((n) => ({ ...n, attrs: { ...n.attrs, depth: dist.get(n.id) } }));
    const ids = new Set(keptNodes.map((n) => n.id));
    keptEdges = keptEdges.filter((e) => ids.has(e.from) && ids.has(e.to));
  } else {
    const ids = new Set(keptNodes.map((n) => n.id));
    keptEdges = keptEdges.filter((e) => ids.has(e.from) && ids.has(e.to));
  }
  let truncated = false;
  if (keptNodes.length > limit) {
    truncated = true;
    keptNodes = keptNodes.slice(0, limit);
    const ids = new Set(keptNodes.map((n) => n.id));
    keptEdges = keptEdges.filter((e) => ids.has(e.from) && ids.has(e.to));
  }
  return { nodes: keptNodes, edges: keptEdges, truncated };
}

function overview() {
  const pools = store.poolStats;
  const owned = new Set(store.tables.filter((t) => t.ownerTeamId).map((t) => t.id));
  const crossTeam = store.relationships.filter((r) => {
    if (r.objectType !== 'TABLE') return false;
    const t = tbl(r.objectId);
    const a = app(r.applicationId);
    return t?.ownerTeamId && a?.teamId && t.ownerTeamId !== a.teamId;
  }).length;
  return {
    databases: store.databases.length, datasources: store.datasources.length, applications: store.applications.length, teams: store.teams.length,
    tables: store.tables.length, routines: store.routines.length,
    connections: {
      proxyActive: store.liveConnections.filter((c) => c.source === 'PROXY').length,
      gatewayLogical: pools.reduce((s, p) => s + p.logicalSessions, 0),
      gatewayPhysical: pools.reduce((s, p) => s + p.total, 0),
    },
    queriesLastHour: Math.round(store.queryStats.reduce((s, q) => s + q.count, 0) / (7 * 24)) + 48_211 - 7_000,
    unownedTables: store.tables.filter((t) => !owned.has(t.id)).length,
    crossTeamAccesses: crossTeam,
    violations: store.violations.filter((v) => v.status === 'OPEN').length,
    componentsOnline: store.components.map(({ componentType, componentId, lastHeartbeat, healthy }) => ({ componentType, componentId, lastHeartbeat, healthy })),
  };
}

function connectionsBy(groupBy: string) {
  const out = new Map<string, { key: string; proxy: number; gatewayLogical: number; gatewayPhysical: number }>();
  const row = (key: string) => {
    if (!out.has(key)) out.set(key, { key, proxy: 0, gatewayLogical: 0, gatewayPhysical: 0 });
    return out.get(key)!;
  };
  // gateway pools are per datasource; attribute logical sessions to applications by grant share
  for (const p of store.poolStats) {
    const ds = store.datasources.find((d) => d.id === p.datasourceId);
    const database = db(p.databaseId);
    if (groupBy === 'datasource') { const r = row(p.datasource); r.gatewayLogical += p.logicalSessions; r.gatewayPhysical += p.total; }
    else if (groupBy === 'database') { const r = row(database?.name ?? p.databaseId); r.gatewayLogical += p.logicalSessions; r.gatewayPhysical += p.total; }
    else {
      const grants = store.accessGrants.filter((g) => g.datasourceId === p.datasourceId && g.enabled && g.maxLogicalConnections > 0);
      const weight = grants.reduce((s, g) => s + g.maxLogicalConnections, 0) || 1;
      for (const g of grants) {
        const a = app(g.applicationId);
        if (!a) continue;
        const key = groupBy === 'team' ? team(a.teamId)?.name ?? 'unknown' : a.name;
        const r = row(key);
        r.gatewayLogical += Math.round((p.logicalSessions * g.maxLogicalConnections) / weight);
        r.gatewayPhysical += Math.round((p.total * g.maxLogicalConnections) / weight);
      }
      void ds;
    }
  }
  for (const c of store.liveConnections.filter((c) => c.source === 'PROXY')) {
    const key = groupBy === 'team' ? c.team ?? 'unknown' : groupBy === 'datasource' ? c.datasource ?? 'unknown' : groupBy === 'database' ? c.database ?? 'unknown' : c.application ?? 'unknown';
    row(key).proxy += 1;
  }
  return [...out.values()].sort((a, b) => b.proxy + b.gatewayLogical - (a.proxy + a.gatewayLogical));
}

function hotTables(limit: number) {
  return store.tables
    .map((t) => {
      const rels = store.relationships.filter((r) => r.objectType === 'TABLE' && r.objectId === t.id);
      const apps = new Set(rels.map((r) => r.applicationId));
      const teams = new Set(rels.map((r) => app(r.applicationId)?.teamId).filter(Boolean));
      return { table: tableRef(t), reads: rels.filter((r) => r.kind === 'READS').reduce((s, r) => s + r.queryCount, 0), writes: rels.filter((r) => r.kind === 'WRITES').reduce((s, r) => s + r.queryCount, 0), applications: apps.size, teams: teams.size };
    })
    .filter((h) => h.reads + h.writes > 0)
    .sort((a, b) => b.reads + b.writes - (a.reads + a.writes))
    .slice(0, limit);
}

function exportDocument() {
  return {
    version: 1, exportedAt: now(),
    teams: store.teams, applications: store.applications,
    databases: store.databases, credentials: store.credentials, datasources: store.datasources, accessGrants: store.accessGrants,
    ownership: store.tables.filter((t) => t.ownerTeamId).map((t) => ({ databaseName: db(t.databaseId)?.name ?? '', schema: t.schema, table: t.name, teamName: team(t.ownerTeamId)?.name ?? '', confirmed: t.ownerConfirmed })),
    relationships: store.relationships.filter((r) => r.source === 'DECLARED'),
    dependencies: store.dependencies.filter((d) => d.source === 'DECLARED'),
  };
}
function importDocument(doc: Record<string, unknown>) {
  const counts: Record<string, number> = {};
  const merge = <T extends { id: string; name: string }>(list: T[], incoming: T[] | undefined, key: string) => {
    let n = 0;
    for (const item of incoming ?? []) {
      const idx = list.findIndex((x) => x.name === item.name);
      if (idx >= 0) list[idx] = { ...list[idx], ...item, id: list[idx].id, updatedAt: now() };
      else list.push({ ...item, id: item.id || randomUUID(), createdAt: now(), updatedAt: now() } as T);
      n++;
    }
    counts[key] = n;
  };
  merge(store.teams, doc.teams as Team[] | undefined, 'teams');
  merge(store.applications, doc.applications as Application[] | undefined, 'applications');
  merge(store.databases, doc.databases as Database[] | undefined, 'databases');
  merge(store.credentials, doc.credentials as Credential[] | undefined, 'credentials');
  merge(store.datasources, doc.datasources as Datasource[] | undefined, 'datasources');
  for (const g of (doc.accessGrants as AccessGrant[] | undefined) ?? []) {
    const idx = store.accessGrants.findIndex((x) => x.applicationId === g.applicationId && x.datasourceId === g.datasourceId);
    if (idx >= 0) store.accessGrants[idx] = { ...store.accessGrants[idx], ...g, id: store.accessGrants[idx].id };
    else store.accessGrants.push({ ...g, id: g.id || randomUUID() });
    counts.accessGrants = (counts.accessGrants ?? 0) + 1;
  }
  for (const o of (doc.ownership as Array<{ databaseName: string; schema: string; table: string; teamName: string; confirmed: boolean }> | undefined) ?? []) {
    const d = store.databases.find((x) => x.name === o.databaseName);
    const t = store.tables.find((x) => x.databaseId === d?.id && x.schema === o.schema && x.name === o.table);
    const tm = store.teams.find((x) => x.name === o.teamName);
    if (t && tm) { t.ownerTeamId = tm.id; t.ownerConfirmed = o.confirmed; t.ownerSource = 'DECLARED'; counts.ownership = (counts.ownership ?? 0) + 1; }
  }
  bump();
  return { imported: counts };
}

// ------------------------------------------------------------------ router
type Handler = (ctx: { params: Record<string, string>; query: URLSearchParams; body: Record<string, unknown> }) => unknown;
interface Route { method: string; pattern: RegExp; keys: string[]; handler: Handler; status?: number }
const routes: Route[] = [];
function on(method: string, path: string, handler: Handler, status?: number) {
  const keys: string[] = [];
  const pattern = new RegExp('^' + path.replace(/\//g, '\\/').replace(/:(\w+)/g, (_m, k: string) => { keys.push(k); return '([^/]+)'; }) + '$');
  routes.push({ method, pattern, keys, handler, status });
}

// teams
on('GET', '/teams', () => store.teams);
on('POST', '/teams', ({ body }) => upsert(store.teams, body as Partial<Team>, { contacts: [], tags: [] }), 201);
on('GET', '/teams/:id', ({ params }) => byId(store.teams, params.id, 'team'));
on('PUT', '/teams/:id', ({ params, body }) => patch(store.teams, params.id, body as Partial<Team>, 'team'));
on('DELETE', '/teams/:id', ({ params }) => { remove(store.teams, params.id, 'team'); return undefined; }, 204);
on('GET', '/teams/:id/summary', ({ params }) => {
  const t = byId(store.teams, params.id, 'team');
  const apps = store.applications.filter((a) => a.teamId === t.id);
  const appIds = new Set(apps.map((a) => a.id));
  const consumed = store.relationships.filter((r) => r.objectType === 'TABLE' && appIds.has(r.applicationId)).map((r) => tbl(r.objectId)).filter((x): x is Table => !!x);
  return {
    team: t, applications: apps,
    ownedTables: store.tables.filter((x) => x.ownerTeamId === t.id).map(tableRef),
    consumedTables: uniqueBy(consumed, (x) => x.id).map(tableRef),
    producedTables: store.tables.filter((x) => x.producerApplicationId && appIds.has(x.producerApplicationId)).map(tableRef),
    datasourcesOwned: store.datasources.filter((d) => d.ownerTeamId === t.id).map((d) => ({ id: d.id, name: d.name, state: d.state })),
  };
});

// applications
on('GET', '/applications', () => store.applications);
on('POST', '/applications', ({ body }) => upsert(store.applications, body as Partial<Application>, { tags: [], kind: 'SERVICE', runtime: 'OTHER', identityRules: { cidrs: [], programNames: [], machinePatterns: [], serviceAliases: [], pgApplicationNames: [] } }), 201);
on('GET', '/applications/:id', ({ params }) => byId(store.applications, params.id, 'application'));
on('PUT', '/applications/:id', ({ params, body }) => patch(store.applications, params.id, body as Partial<Application>, 'application'));
on('DELETE', '/applications/:id', ({ params }) => { remove(store.applications, params.id, 'application'); return undefined; }, 204);
on('GET', '/applications/:id/summary', ({ params }) => {
  const a = byId(store.applications, params.id, 'application');
  const rels = store.relationships.filter((r) => r.applicationId === a.id);
  const refs = (kind: Relationship['kind']) => uniqueBy(rels.filter((r) => r.kind === kind && r.objectType === 'TABLE').map((r) => tbl(r.objectId)).filter((x): x is Table => !!x), (x) => x.id).map(tableRef);
  const conns = connectionsBy('application').find((c) => c.key === a.name) ?? { proxy: 0, gatewayLogical: 0, gatewayPhysical: 0 };
  return {
    application: a, team: team(a.teamId),
    grants: store.accessGrants.filter((g) => g.applicationId === a.id),
    reads: refs('READS'), writes: refs('WRITES'),
    calls: uniqueBy(rels.filter((r) => r.objectType === 'ROUTINE').map((r) => rtn(r.objectId)).filter((x): x is Routine => !!x), (x) => x.id).map(routineRef),
    connections: { proxy: conns.proxy, gatewayLogical: conns.gatewayLogical, gatewayPhysical: conns.gatewayPhysical },
    queryStats: queryStatsSummary(store.queryStats.filter((q) => q.applicationId === a.id)),
  };
});
on('GET', '/applications/:id/api-keys', ({ params }) => { byId(store.applications, params.id, 'application'); return store.apiKeys[params.id] ?? []; });
on('POST', '/applications/:id/api-keys', ({ params, body }) => {
  byId(store.applications, params.id, 'application');
  const prefix = randomUUID().replace(/-/g, '').slice(0, 6);
  const secret = randomUUID().replace(/-/g, '') + randomUUID().replace(/-/g, '').slice(0, 8);
  const key: ApiKey = { id: randomUUID(), prefix, label: String(body.label ?? 'default'), createdAt: now(), lastUsedAt: null, revokedAt: null };
  (store.apiKeys[params.id] ??= []).push(key);
  return { id: key.id, prefix, apiKey: `dbp_${prefix}_${secret}` };
}, 201);
on('DELETE', '/applications/:id/api-keys/:keyId', ({ params }) => {
  const k = (store.apiKeys[params.id] ?? []).find((x) => x.id === params.keyId);
  if (!k) throw notFound('api key', params.keyId);
  k.revokedAt = now();
  return undefined;
}, 204);

// databases
on('GET', '/databases', () => store.databases);
on('POST', '/databases', ({ body }) => upsert(store.databases, body as Partial<Database>, { tags: [], jdbcProperties: {}, maxPhysicalConnections: 50, collector: { enabled: false, dictionaryIntervalSeconds: 3600, runtimeIntervalSeconds: 15, schemas: [], auditTrail: false } }), 201);
on('GET', '/databases/:id', ({ params }) => byId(store.databases, params.id, 'database'));
on('PUT', '/databases/:id', ({ params, body }) => patch(store.databases, params.id, body as Partial<Database>, 'database'));
on('DELETE', '/databases/:id', ({ params }) => { remove(store.databases, params.id, 'database'); return undefined; }, 204);
on('POST', '/databases/:id/test-connection', ({ params }) => {
  const d = byId(store.databases, params.id, 'database');
  const ok = d.host !== 'unreachable.example.org';
  return ok
    ? { ok: true, productName: d.engine === 'ORACLE' ? 'Oracle Database 23ai Free' : d.engine === 'POSTGRES' ? 'PostgreSQL' : 'Microsoft SQL Server', productVersion: d.engine === 'ORACLE' ? '23.6.0.24.10' : d.engine === 'POSTGRES' ? '16.4' : '2022', latencyMs: 8 + Math.round(Math.random() * 20) }
    : { ok: false, message: `Connection refused: ${d.host}:${d.port}` };
});
on('POST', '/databases/:id/collect', ({ params, body }) => {
  byId(store.databases, params.id, 'database');
  const st = (store.collectorStatus[params.id] ??= { lastDictionaryRun: null, lastRuntimeRun: null, lastError: null, tablesSeen: 0 });
  if (body.what === 'DICTIONARY') st.lastDictionaryRun = now();
  else st.lastRuntimeRun = now();
  st.tablesSeen = store.tables.filter((t) => t.databaseId === params.id).length;
  return { started: true };
}, 202);
on('GET', '/databases/:id/schemas', ({ params }) => {
  byId(store.databases, params.id, 'database');
  const names = new Set([...store.tables.filter((t) => t.databaseId === params.id).map((t) => t.schema), ...store.routines.filter((r) => r.databaseId === params.id).map((r) => r.schema)]);
  return [...names].sort().map((name) => ({ name, tableCount: store.tables.filter((t) => t.databaseId === params.id && t.schema === name).length, routineCount: store.routines.filter((r) => r.databaseId === params.id && r.schema === name).length }));
});
on('GET', '/databases/:id/collector-status', ({ params }) => { byId(store.databases, params.id, 'database'); return store.collectorStatus[params.id] ?? { lastDictionaryRun: null, lastRuntimeRun: null, lastError: null, tablesSeen: 0 }; });

// credentials (secrets never returned)
const scrub = (c: Credential & { secret?: string }) => { const { secret: _s, ...rest } = c; return rest; };
on('GET', '/credentials', () => store.credentials.map(scrub));
on('POST', '/credentials', ({ body }) => {
  if (body.provider === 'INLINE' && !body.secret) throw bad('INLINE credentials need a secret');
  const { secret: _s, ...rest } = body;
  return scrub(upsert(store.credentials, rest as Partial<Credential>, { ref: null, rotatedAt: null, version: 1 }));
}, 201);
on('GET', '/credentials/:id', ({ params }) => scrub(byId(store.credentials, params.id, 'credential')));
on('PUT', '/credentials/:id', ({ params, body }) => { const { secret: _s, ...rest } = body; return scrub(patch(store.credentials, params.id, rest as Partial<Credential>, 'credential')); });
on('DELETE', '/credentials/:id', ({ params }) => { remove(store.credentials, params.id, 'credential'); return undefined; }, 204);
on('POST', '/credentials/:id/rotate', ({ params }) => { const c = byId(store.credentials, params.id, 'credential'); return scrub(patch(store.credentials, params.id, { rotatedAt: now(), version: (c.version ?? 1) + 1 }, 'credential')); });

// datasources
on('GET', '/datasources', () => store.datasources);
on('POST', '/datasources', ({ body }) => upsert(store.datasources, body as Partial<Datasource>, { tags: [], state: 'ACTIVE', routingRules: [], targetDatabaseId: null, poolPolicy: { mode: 'TRANSACTION', maxConnections: 20, minIdle: 1, connectionTimeoutMs: 10000, idleTimeoutMs: 600000, maxLifetimeMs: 1800000, statementTimeoutSeconds: 0, validationQuery: null } }), 201);
on('GET', '/datasources/:id', ({ params }) => byId(store.datasources, params.id, 'datasource'));
on('PUT', '/datasources/:id', ({ params, body }) => patch(store.datasources, params.id, body as Partial<Datasource>, 'datasource'));
on('DELETE', '/datasources/:id', ({ params }) => { remove(store.datasources, params.id, 'datasource'); return undefined; }, 204);
const sortRules = (rules: RoutingRule[]) => rules.sort((a, b) => a.priority - b.priority);
on('PUT', '/datasources/:id/routing-rules', ({ params, body }) => {
  const d = byId(store.datasources, params.id, 'datasource');
  const list = (Array.isArray(body) ? body : []) as Partial<RoutingRule>[];
  d.routingRules = sortRules(list.map((r) => ({ priority: 100, applicationId: null, tag: null, readOnly: false, enabled: true, ...r, id: r.id ?? randomUUID(), databaseId: String(r.databaseId) })));
  d.updatedAt = now(); bump();
  return d.routingRules;
});
on('POST', '/datasources/:id/routing-rules', ({ params, body }) => {
  const d = byId(store.datasources, params.id, 'datasource');
  if (!body.databaseId) throw bad('databaseId is required');
  const rule: RoutingRule = { id: randomUUID(), priority: Number(body.priority ?? 100), applicationId: (body.applicationId as string) || null, tag: (body.tag as string) || null, databaseId: String(body.databaseId), readOnly: !!body.readOnly, enabled: body.enabled !== false };
  d.routingRules = sortRules([...d.routingRules, rule]);
  d.updatedAt = now(); bump();
  return rule;
}, 201);
on('DELETE', '/datasources/:id/routing-rules/:ruleId', ({ params }) => {
  const d = byId(store.datasources, params.id, 'datasource');
  if (!d.routingRules.some((r) => r.id === params.ruleId)) throw notFound('routing rule', params.ruleId);
  d.routingRules = d.routingRules.filter((r) => r.id !== params.ruleId);
  d.updatedAt = now(); bump();
  return undefined;
}, 204);
on('POST', '/datasources/:id/switch', ({ params, body }) => {
  const d = byId(store.datasources, params.id, 'datasource');
  const target = byId(store.databases, String(body.databaseId), 'database');
  if (target.id === d.currentDatabaseId) throw bad('datasource already routes to this database');
  store.migrationEvents.push({ id: randomUUID(), datasourceId: d.id, fromDatabaseId: d.currentDatabaseId, toDatabaseId: target.id, at: now(), by: 'ui', note: (body.note as string) || null });
  d.currentDatabaseId = target.id;
  if (d.targetDatabaseId === target.id) { d.targetDatabaseId = null; d.state = 'ACTIVE'; }
  d.updatedAt = now(); bump();
  return d;
});
on('GET', '/datasources/:id/summary', ({ params }) => {
  const d = byId(store.datasources, params.id, 'datasource');
  const grants = store.accessGrants.filter((g) => g.datasourceId === d.id);
  return {
    datasource: d, ownerTeam: team(d.ownerTeamId), currentDatabase: db(d.currentDatabaseId), targetDatabase: db(d.targetDatabaseId), grants,
    consumers: grants.map((g) => app(g.applicationId)).filter((x): x is Application => !!x),
    pools: store.poolStats.filter((p) => p.datasourceId === d.id),
    tables: store.tables.filter((t) => t.databaseId === d.currentDatabaseId && store.relationships.some((r) => r.objectType === 'TABLE' && r.objectId === t.id && grants.some((g) => g.applicationId === r.applicationId))).map(tableRef),
  };
});
on('GET', '/migration-events', ({ query }) => store.migrationEvents.filter((m) => !query.get('datasourceId') || m.datasourceId === query.get('datasourceId')).sort((a, b) => b.at.localeCompare(a.at)));

// access grants
on('GET', '/access-grants', ({ query }) => store.accessGrants.filter((g) => (!query.get('applicationId') || g.applicationId === query.get('applicationId')) && (!query.get('datasourceId') || g.datasourceId === query.get('datasourceId'))));
on('POST', '/access-grants', ({ body }) => upsert(store.accessGrants, body as Partial<AccessGrant>, { maxLogicalConnections: 10, maxProxyConnections: 0, poolModeOverride: null, readOnly: false, enabled: true, note: null }), 201);
on('PUT', '/access-grants/:id', ({ params, body }) => patch(store.accessGrants, params.id, body as Partial<AccessGrant>, 'access grant'));
on('DELETE', '/access-grants/:id', ({ params }) => { remove(store.accessGrants, params.id, 'access grant'); return undefined; }, 204);

// tables
on('GET', '/tables', ({ query }) => {
  let list = store.tables;
  const q = query.get('q')?.toLowerCase();
  if (query.get('databaseId')) list = list.filter((t) => t.databaseId === query.get('databaseId'));
  if (query.get('schema')) list = list.filter((t) => t.schema.toLowerCase() === query.get('schema')!.toLowerCase());
  if (query.get('ownerTeamId')) list = list.filter((t) => t.ownerTeamId === query.get('ownerTeamId'));
  if (query.get('unowned') === 'true') list = list.filter((t) => !t.ownerTeamId);
  if (query.get('classification')) list = list.filter((t) => t.classification === query.get('classification'));
  if (q) list = list.filter((t) => `${t.schema}.${t.name}`.toLowerCase().includes(q) || (t.description ?? '').toLowerCase().includes(q));
  return paged([...list].sort((a, b) => `${a.schema}.${a.name}`.localeCompare(`${b.schema}.${b.name}`)), query);
});
on('POST', '/tables/bulk-ownership', ({ body }) => {
  const tm = byId(store.teams, String(body.teamId), 'team');
  let updated = 0;
  for (const t of store.tables) {
    if (t.databaseId === body.databaseId && t.schema === body.schema) { t.ownerTeamId = tm.id; t.ownerSource = 'DECLARED'; t.ownerConfirmed = true; updated++; }
  }
  bump();
  return { updated };
});
on('GET', '/tables/:id', ({ params }) => byId(store.tables, params.id, 'table'));
on('PUT', '/tables/:id', ({ params, body }) => {
  const allowed = ['ownerTeamId', 'producerApplicationId', 'description', 'tags', 'classification', 'migration'] as const;
  const p: Partial<Table> = {};
  for (const k of allowed) if (k in body) (p as Record<string, unknown>)[k] = body[k];
  if ('ownerTeamId' in p) { p.ownerSource = p.ownerTeamId ? 'DECLARED' : 'NONE'; p.ownerConfirmed = !!p.ownerTeamId; }
  return patch(store.tables, params.id, p, 'table');
});
on('GET', '/tables/:id/columns', ({ params }) => { byId(store.tables, params.id, 'table'); return store.columns.filter((c) => c.tableId === params.id).sort((a, b) => a.position - b.position); });
on('PUT', '/tables/:id/columns/:columnId', ({ params, body }) => {
  const c = store.columns.find((x) => x.tableId === params.id && x.id === params.columnId);
  if (!c) throw notFound('column', params.columnId);
  if ('comment' in body) c.comment = (body.comment as string) ?? null;
  if ('classification' in body) c.classification = (body.classification as Column['classification']) ?? null;
  return c;
});
on('GET', '/tables/:id/summary', ({ params }) => tableSummary(byId(store.tables, params.id, 'table')));
on('POST', '/tables/:id/ownership', ({ params, body }) => {
  const t = byId(store.tables, params.id, 'table');
  const teamId = (body.teamId as string | null) ?? null;
  if (teamId) byId(store.teams, teamId, 'team');
  return patch(store.tables, t.id, { ownerTeamId: teamId, ownerConfirmed: !!body.confirmed, ownerSource: teamId ? 'DECLARED' : 'NONE' }, 'table');
});

// routines
on('GET', '/routines', ({ query }) => {
  let list = store.routines;
  const q = query.get('q')?.toLowerCase();
  if (query.get('databaseId')) list = list.filter((r) => r.databaseId === query.get('databaseId'));
  if (query.get('schema')) list = list.filter((r) => r.schema.toLowerCase() === query.get('schema')!.toLowerCase());
  if (query.get('kind')) list = list.filter((r) => r.kind === query.get('kind'));
  if (q) list = list.filter((r) => `${r.schema}.${r.name}`.toLowerCase().includes(q));
  return [...list].sort((a, b) => a.name.localeCompare(b.name));
});
on('GET', '/routines/:id', ({ params }) => byId(store.routines, params.id, 'routine'));
const depName = (type: Dependency['fromType'], id: string) => (type === 'TABLE' ? tbl(id) : rtn(id));
const resolveDep = (d: Dependency): Dependency => { const f = depName(d.fromType, d.fromId); const t = depName(d.toType, d.toId); return { ...d, fromName: f ? `${f.schema}.${f.name}` : d.fromId, toName: t ? `${t.schema}.${t.name}` : d.toId }; };
on('GET', '/routines/:id/summary', ({ params }) => {
  const r = byId(store.routines, params.id, 'routine');
  const deps = store.dependencies.filter((d) => d.fromId === r.id || d.toId === r.id).map(resolveDep);
  const tables = uniqueBy(deps.filter((d) => d.fromId === r.id && d.toType === 'TABLE').map((d) => tbl(d.toId)).filter((x): x is Table => !!x), (x) => x.id);
  const callers = uniqueBy(store.relationships.filter((x) => x.objectType === 'ROUTINE' && x.objectId === r.id).map((x) => app(x.applicationId)).filter((x): x is Application => !!x), (x) => x.id);
  return { routine: r, dependencies: deps, callers, tables: tables.map(tableRef) };
});

// dependencies & relationships
on('GET', '/dependencies', ({ query }) => store.dependencies.filter((d) => (!query.get('fromId') || d.fromId === query.get('fromId')) && (!query.get('toId') || d.toId === query.get('toId')) && (!query.get('kind') || d.kind === query.get('kind'))).map(resolveDep));
on('POST', '/dependencies', ({ body }) => { const d: Dependency = { id: randomUUID(), fromType: body.fromType as Dependency['fromType'], fromId: String(body.fromId), toType: body.toType as Dependency['toType'], toId: String(body.toId), kind: body.kind as Dependency['kind'], source: 'DECLARED', confidence: 1, firstSeenAt: now(), lastSeenAt: now() }; store.dependencies.push(d); bump(); return resolveDep(d); }, 201);
on('DELETE', '/dependencies/:id', ({ params }) => { const d = byId(store.dependencies, params.id, 'dependency'); if (d.source !== 'DECLARED') throw new HttpError(409, 'CONFLICT', 'only DECLARED dependencies can be deleted'); remove(store.dependencies, params.id, 'dependency'); return undefined; }, 204);
on('GET', '/relationships', ({ query }) => store.relationships.filter((r) => (!query.get('applicationId') || r.applicationId === query.get('applicationId')) && (!query.get('objectId') || r.objectId === query.get('objectId')) && (!query.get('kind') || r.kind === query.get('kind')) && (!query.get('source') || r.source === query.get('source'))));
on('POST', '/relationships', ({ body }) => { const r: Relationship = { id: randomUUID(), applicationId: String(body.applicationId), objectType: body.objectType as Relationship['objectType'], objectId: String(body.objectId), kind: body.kind as Relationship['kind'], source: 'DECLARED', confidence: 1, queryCount: 0, firstSeenAt: now(), lastSeenAt: null, confirmed: true, viaRoutineId: null }; store.relationships.push(r); bump(); return r; }, 201);
on('PUT', '/relationships/:id', ({ params, body }) => { const r = byId(store.relationships, params.id, 'relationship'); if ('confirmed' in body) r.confirmed = !!body.confirmed; bump(); return r; });
on('DELETE', '/relationships/:id', ({ params }) => { remove(store.relationships, params.id, 'relationship'); return undefined; }, 204);

// graph & impact
on('GET', '/graph', ({ query }) => buildGraph(query));
on('GET', '/impact/table/:id', ({ params }) => impactForTable(byId(store.tables, params.id, 'table')));
on('GET', '/impact/column/:id', ({ params }) => {
  const c = store.columns.find((x) => x.id === params.id);
  if (!c) throw notFound('column', params.id);
  const t = byId(store.tables, c.tableId, 'table');
  const impact = impactForTable(t);
  const queries = store.queryStats.filter((q) => q.tables.some((x) => x.id === t.id) && new RegExp(`\\b${c.name}\\b`, 'i').test(q.sqlNormalized));
  const factors = [...impact.riskFactors];
  if (c.classification) factors.push(`column classified ${c.classification}`);
  if (!c.nullable) factors.push('NOT NULL column');
  return { ...impact, target: { type: 'COLUMN', id: c.id, label: `${t.schema}.${t.name}.${c.name}` }, riskFactors: factors, queriesReferencingColumn: queries };
});
on('GET', '/impact/datasource/:id', ({ params }) => impactForDatasource(byId(store.datasources, params.id, 'datasource')));

// stats & observability
on('GET', '/stats/overview', () => overview());
on('GET', '/stats/connections', ({ query }) => connectionsBy(query.get('groupBy') ?? 'application'));
on('GET', '/stats/queries/top', ({ query }) => {
  const by = query.get('by') ?? 'count';
  let list = store.queryStats;
  if (query.get('databaseId')) list = list.filter((q) => q.databaseId === query.get('databaseId'));
  if (query.get('applicationId')) list = list.filter((q) => q.applicationId === query.get('applicationId'));
  const w = query.get('window') ?? '24h';
  const factor = w === '1h' ? 1 / 168 : w === '24h' ? 1 / 7 : 1;
  list = list.map((q) => ({ ...q, count: Math.max(1, Math.round(q.count * factor)), errors: Math.round(q.errors * factor), rows: Math.max(1, Math.round(q.rows * factor)) }));
  const key = (q: QueryStat) => (by === 'duration' ? q.avgDurationMs * q.count : by === 'rows' ? q.rows : q.count);
  return [...list].sort((a, b) => key(b) - key(a)).slice(0, Number(query.get('limit') ?? 50));
});
on('GET', '/stats/tables/hot', ({ query }) => hotTables(Number(query.get('limit') ?? 50)));
on('GET', '/stats/tables/unused', ({ query }) => {
  const days = Number(query.get('days') ?? 30);
  const cutoff = Date.now() - days * 86_400_000;
  return store.tables.filter((t) => !store.relationships.some((r) => r.objectType === 'TABLE' && r.objectId === t.id && r.lastSeenAt && Date.parse(r.lastSeenAt) > cutoff)).map(tableRef);
});
on('GET', '/stats/pools', () => store.poolStats);
on('GET', '/connections/live', () => store.liveConnections);
on('GET', '/components', () => store.components);

// governance
on('GET', '/governance/policies', () => store.policies);
on('PUT', '/governance/policies/:id', ({ params, body }) => { const p = byId(store.policies, params.id, 'policy'); if ('enabled' in body) p.enabled = !!body.enabled; if (body.severity) p.severity = body.severity as typeof p.severity; bump(); return p; });
on('GET', '/governance/violations', ({ query }) => store.violations.filter((v) => !query.get('status') || v.status === query.get('status')).sort((a, b) => b.lastSeenAt.localeCompare(a.lastSeenAt)));
on('PUT', '/governance/violations/:id', ({ params, body }) => { const v = byId(store.violations, params.id, 'violation'); if (body.status) v.status = body.status as typeof v.status; return v; });
on('POST', '/governance/evaluate', () => ({ violations: store.violations.filter((v) => v.status === 'OPEN').length }), 202);

// import / export / seed
on('GET', '/export', () => exportDocument());
on('POST', '/import', ({ body }) => importDocument(body));
on('POST', '/seed/demo', () => { store = buildDemoStore(); return { seeded: true }; });

// internal (enough for a gateway/proxy to boot against the mock)
on('GET', '/internal/config-version', () => ({ configVersion: store.configVersion }));
on('POST', '/internal/heartbeat', ({ body }) => {
  const id = String(body.componentId ?? 'unknown');
  const existing = store.components.find((c) => c.componentId === id);
  const entry = { componentType: (body.componentType as 'GATEWAY' | 'PROXY') ?? 'GATEWAY', componentId: id, version: body.version as string, host: body.host as string, startedAt: body.startedAt as string, lastHeartbeat: now(), healthy: true, stats: (body.stats as Record<string, unknown>) ?? {} };
  if (existing) Object.assign(existing, entry); else store.components.push(entry);
  return { configVersion: store.configVersion };
});
on('POST', '/internal/telemetry/queries', ({ body }) => ({ accepted: Array.isArray(body) ? body.length : 0 }), 202);
on('POST', '/internal/telemetry/connections', ({ body }) => ({ accepted: Array.isArray(body) ? body.length : 0 }), 202);
on('POST', '/internal/telemetry/pools', ({ body }) => ({ accepted: Array.isArray(body) ? body.length : 0 }), 202);

// ------------------------------------------------------------------ http plumbing
const MIME: Record<string, string> = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.map': 'application/json' };
function serveStatic(pathname: string, res: ServerResponse): boolean {
  if (!existsSync(join(DIST, 'index.html'))) return false;
  let file = normalize(join(DIST, decodeURIComponent(pathname)));
  if (!file.startsWith(DIST)) return false;
  if (!existsSync(file) || statSync(file).isDirectory()) file = join(DIST, 'index.html');
  res.writeHead(200, { 'Content-Type': MIME[extname(file)] ?? 'application/octet-stream', 'Cache-Control': file.endsWith('index.html') ? 'no-cache' : 'public, max-age=31536000, immutable' });
  res.end(readFileSync(file));
  return true;
}
function send(res: ServerResponse, status: number, body: unknown) {
  const headers: Record<string, string> = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-DBP-Service-Token', 'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS' };
  if (body === undefined) { res.writeHead(status, headers); res.end(); return; }
  headers['Content-Type'] = 'application/json; charset=utf-8';
  res.writeHead(status, headers);
  res.end(JSON.stringify(body));
}
async function readBody(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  const text = Buffer.concat(chunks).toString('utf8');
  if (!text) return {};
  try { return JSON.parse(text); } catch { throw bad('request body is not valid JSON'); }
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  const method = (req.method ?? 'GET').toUpperCase();
  try {
    if (method === 'OPTIONS') return send(res, 204, undefined);
    if (url.pathname === '/actuator/health') return send(res, 200, { status: 'UP' });
    if (!url.pathname.startsWith(API)) {
      if (method === 'GET' && serveStatic(url.pathname, res)) return;
      return send(res, 404, { status: 404, error: 'NOT_FOUND', message: `No route for ${url.pathname} (build the UI with "npm run build" to serve it from the mock)`, path: url.pathname });
    }
    const path = url.pathname.slice(API.length).replace(/\/$/, '') || '/';
    const route = routes.find((r) => r.method === method && r.pattern.test(path));
    if (!route) {
      const exists = routes.some((r) => r.pattern.test(path));
      return send(res, exists ? 405 : 404, { status: exists ? 405 : 404, error: exists ? 'METHOD_NOT_ALLOWED' : 'NOT_FOUND', message: exists ? `${method} not allowed on ${url.pathname}` : `No route for ${url.pathname}`, path: url.pathname });
    }
    const m = route.pattern.exec(path)!;
    const params: Record<string, string> = {};
    route.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])));
    const body = method === 'GET' || method === 'DELETE' ? {} : await readBody(req);
    if (LATENCY_MS > 0) await sleep(LATENCY_MS * (0.5 + Math.random()));
    const result = route.handler({ params, query: url.searchParams, body });
    send(res, route.status ?? 200, result);
  } catch (e) {
    if (e instanceof HttpError) return send(res, e.status, { status: e.status, error: e.code, message: e.message, path: url.pathname });
    console.error(e);
    send(res, 500, { status: 500, error: 'INTERNAL_ERROR', message: e instanceof Error ? e.message : String(e), path: url.pathname });
  }
});

server.listen(PORT, () => {
  console.log(`dbp mock control plane listening on http://localhost:${PORT}${API}  (${routes.length} routes${existsSync(join(DIST, 'index.html')) ? ', serving dist/' : ''})`);
  console.log(`  teams=${store.teams.length} applications=${store.applications.length} databases=${store.databases.length} datasources=${store.datasources.length} tables=${store.tables.length} routines=${store.routines.length}`);
});
