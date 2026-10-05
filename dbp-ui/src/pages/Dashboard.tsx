import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Activity, AlertTriangle, Database, Layers, Table2, Users, Zap, AppWindow } from 'lucide-react';
import { useComponents, useConnectionsBy, useHotTables, useOverview, usePools, useTopQueries } from '../api/hooks';
import type { ConnectionsGroupBy } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { StatTile } from '../components/StatTile';
import { Card } from '../components/Card';
import { QueryBoundary } from '../components/States';
import { Meter } from '../components/Meter';
import { StackedBars } from '../charts/StackedBars';
import { SERIES_BY_MEASURE } from '../charts/palette';
import { DataTable } from '../components/DataTable';
import { Badge, HealthBadge } from '../components/Badge';
import { RelativeTime } from '../components/RelativeTime';
import { compact, formatMs, formatNumber } from '../lib/format';
import { links } from '../lib/links';

export function Dashboard() {
  const overview = useOverview();
  const [groupBy, setGroupBy] = useState<ConnectionsGroupBy>('application');
  const connections = useConnectionsBy(groupBy);
  const hot = useHotTables('24h', 8);
  const top = useTopQueries({ by: 'count', window: '24h', limit: 8 });
  const pools = usePools();
  const components = useComponents();
  const o = overview.data;

  return (
    <>
      <PageHeader title="Dashboard" subtitle="Who connects to what, through which path, and what is at risk." />

      <section aria-label="Key figures" style={{ marginBottom: 16 }}>
        {overview.isError ? (
          <QueryBoundary query={overview}>{() => null}</QueryBoundary>
        ) : (
          <div className="kpi-row">
            <StatTile label="Proxy connections" icon={<Activity size={13} />} value={o?.connections.proxyActive} sub="live, through the transparent proxy" to={links.connections()} />
            <StatTile label="Gateway logical sessions" icon={<Activity size={13} />} value={o?.connections.gatewayLogical} sub={o ? <>on <strong>{formatNumber(o.connections.gatewayPhysical)}</strong> physical connections</> : undefined} to={links.connections()} />
            <StatTile label="Queries last hour" icon={<Zap size={13} />} value={o?.queriesLastHour} to={links.queries()} />
            <StatTile label="Unowned tables" icon={<Table2 size={13} />} value={o?.unownedTables} sub={o ? `of ${formatNumber(o.tables)} tables` : undefined} to={links.tables({ unowned: 'true' })} alert={!!o && o.unownedTables > 0} />
            <StatTile label="Open violations" icon={<AlertTriangle size={13} />} value={o?.violations} sub={o ? `${o.crossTeamAccesses} cross-team accesses` : undefined} to={links.governance()} alert={!!o && o.violations > 0} />
            <StatTile label="Inventory" icon={<Layers size={13} />} value={o ? `${o.databases} · ${o.datasources} · ${o.applications}` : undefined} sub="databases · datasources · applications" to={links.datasources()} />
          </div>
        )}
      </section>

      <div className="grid cols-3">
        <Card
          className="span-2"
          title="Connections"
          hint="proxy vs gateway"
          actions={
            <div className="segmented" role="group" aria-label="Group connections by">
              {(['application', 'team', 'datasource', 'database'] as ConnectionsGroupBy[]).map((g) => (
                <button key={g} type="button" aria-pressed={groupBy === g} onClick={() => setGroupBy(g)}>{g}</button>
              ))}
            </div>
          }
        >
          <QueryBoundary query={connections}>
            {(rows) => (
              <StackedBars
                data={rows.slice(0, 12)}
                category="key"
                refetching={connections.isFetching}
                ariaLabel={`Connections by ${groupBy}: proxy, gateway logical and gateway physical`}
                series={[
                  { key: 'gatewayLogical', name: 'Gateway logical', color: SERIES_BY_MEASURE.gatewayLogical },
                  { key: 'proxy', name: 'Proxy', color: SERIES_BY_MEASURE.proxy },
                  { key: 'gatewayPhysical', name: 'Gateway physical', color: SERIES_BY_MEASURE.gatewayPhysical },
                ]}
              />
            )}
          </QueryBoundary>
        </Card>

        <Card title="Components" hint="from heartbeats" actions={<Link to={links.admin()} className="btn sm ghost">Admin</Link>}>
          <QueryBoundary query={components}>
            {(rows) => (
              <div className="stack" style={{ gap: 8 }}>
                {rows.map((c) => (
                  <div key={c.componentId} className="row between">
                    <span className="row" style={{ gap: 8 }}>
                      <Badge outline>{c.componentType}</Badge>
                      <strong>{c.componentId}</strong>
                      <span className="muted small">{c.version}</span>
                    </span>
                    <span className="row" style={{ gap: 8 }}>
                      <span className="muted small"><RelativeTime value={c.lastHeartbeat} /></span>
                      <HealthBadge healthy={c.healthy} />
                    </span>
                  </div>
                ))}
              </div>
            )}
          </QueryBoundary>
        </Card>

        <Card title="Pool utilisation" hint="gateway pools" actions={<Link to={links.datasources()} className="btn sm ghost">Datasources</Link>}>
          <QueryBoundary query={pools}>
            {(rows) => (
              <div>
                {rows.map((p) => (
                  <Meter
                    key={`${p.gatewayId}-${p.datasourceId}-${p.databaseId}`}
                    label={<Link to={links.datasource(p.datasourceId)}>{p.datasource}</Link>}
                    sub={`${p.gatewayId} · ${p.engine}`}
                    value={p.active + p.idle}
                    max={p.max}
                    format={(v, m) => `${p.active} active · ${p.idle} idle / ${m}${p.waiting ? ` · ${p.waiting} waiting` : ''}${v > m ? '' : ''}`}
                  />
                ))}
              </div>
            )}
          </QueryBoundary>
        </Card>

        <Card className="span-2" title="Hot tables" hint="last 24h" actions={<Link to={links.tables()} className="btn sm ghost">Catalogue</Link>} tight>
          <QueryBoundary query={hot}>
            {(rows) => (
              <DataTable
                rows={rows}
                rowKey={(r) => r.table.id}
                rowLink={(r) => links.table(r.table.id)}
                compact
                columns={[
                  { key: 'table', header: 'Table', render: (r) => <Link to={links.table(r.table.id)}>{r.table.schema}.{r.table.name}</Link> },
                  { key: 'reads', header: 'Reads', align: 'right', render: (r) => compact(r.reads), sort: (r) => r.reads },
                  { key: 'writes', header: 'Writes', align: 'right', render: (r) => compact(r.writes), sort: (r) => r.writes },
                  { key: 'apps', header: 'Apps', align: 'right', render: (r) => r.applications, sort: (r) => r.applications },
                  { key: 'teams', header: 'Teams', align: 'right', render: (r) => r.teams, sort: (r) => r.teams },
                ]}
              />
            )}
          </QueryBoundary>
        </Card>

        <Card className="span-2" title="Top queries" hint="by count, 24h" actions={<Link to={links.queries()} className="btn sm ghost">All queries</Link>} tight>
          <QueryBoundary query={top}>
            {(rows) => (
              <DataTable
                rows={rows}
                rowKey={(r) => `${r.sqlHash}-${r.applicationId}`}
                compact
                columns={[
                  { key: 'sql', header: 'SQL', className: 'truncate', render: (r) => <span className="sql" title={r.sqlNormalized}>{r.sqlNormalized}</span> },
                  { key: 'op', header: 'Op', render: (r) => <Badge outline>{r.operation}</Badge> },
                  { key: 'app', header: 'Application', render: (r) => (r.applicationId ? <Link to={links.application(r.applicationId)}>{r.applicationName}</Link> : <span className="muted">unknown</span>) },
                  { key: 'count', header: 'Count', align: 'right', render: (r) => compact(r.count) },
                  { key: 'p95', header: 'p95', align: 'right', render: (r) => formatMs(r.p95DurationMs) },
                ]}
              />
            )}
          </QueryBoundary>
        </Card>

        <Card title="Shortcuts">
          <div className="stack" style={{ gap: 6 }}>
            <Link to={links.graph()} className="btn" style={{ justifyContent: 'flex-start' }}><Database /> Explore the dependency graph</Link>
            <Link to={links.impact()} className="btn" style={{ justifyContent: 'flex-start' }}><AlertTriangle /> Run an impact analysis</Link>
            <Link to={links.tables({ unowned: 'true' })} className="btn" style={{ justifyContent: 'flex-start' }}><Table2 /> Assign owners to unowned tables</Link>
            <Link to={links.applications()} className="btn" style={{ justifyContent: 'flex-start' }}><AppWindow /> Register an application</Link>
            <Link to={links.teams()} className="btn" style={{ justifyContent: 'flex-start' }}><Users /> Teams</Link>
          </div>
          {o && (
            <p className="muted small" style={{ marginTop: 12 }}>
              {formatNumber(o.tables)} tables and {formatNumber(o.routines)} routines across {o.databases} databases · {o.teams} teams
            </p>
          )}
        </Card>
      </div>
    </>
  );
}
