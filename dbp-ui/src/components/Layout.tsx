import { NavLink, Outlet, Link, useLocation } from 'react-router-dom';
import {
  Activity, AppWindow, Boxes, ChevronRight, Database, GitBranch, LayoutDashboard, Layers, Monitor, Moon, Network, Settings, ShieldCheck, Sun, SunMoon, Table2, Users, Workflow, Zap, Search,
} from 'lucide-react';
import { useTheme } from '../lib/theme';
import { useOverview } from '../api/hooks';
import { useCrumb } from '../lib/crumb';

export interface Crumb { label: string; to?: string }

const NAV: Array<{ section: string; items: Array<{ to: string; label: string; icon: typeof Database; end?: boolean }> }> = [
  { section: 'Overview', items: [{ to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true }] },
  {
    section: 'Platform',
    items: [
      { to: '/databases', label: 'Databases', icon: Database },
      { to: '/datasources', label: 'Datasources', icon: Layers },
      { to: '/applications', label: 'Applications', icon: AppWindow },
      { to: '/teams', label: 'Teams', icon: Users },
    ],
  },
  {
    section: 'Catalogue',
    items: [
      { to: '/tables', label: 'Tables', icon: Table2 },
      { to: '/routines', label: 'Routines', icon: Workflow },
      { to: '/graph', label: 'Graph explorer', icon: Network },
      { to: '/impact', label: 'Impact analysis', icon: GitBranch },
    ],
  },
  {
    section: 'Runtime',
    items: [
      { to: '/connections', label: 'Connections', icon: Activity },
      { to: '/queries', label: 'Queries', icon: Zap },
      { to: '/governance', label: 'Governance', icon: ShieldCheck },
    ],
  },
  { section: 'System', items: [{ to: '/admin', label: 'Admin', icon: Settings }] },
];

export function Layout() {
  const [theme, setTheme] = useTheme();
  const overview = useOverview();
  const online = overview.data?.componentsOnline.filter((c) => c.healthy).length;
  const total = overview.data?.componentsOnline.length;
  const next = theme === 'system' ? 'light' : theme === 'light' ? 'dark' : 'system';
  const ThemeIcon = theme === 'system' ? SunMoon : theme === 'light' ? Sun : Moon;
  return (
    <div className="app">
      <aside className="sidebar" aria-label="Main navigation">
        <Link to="/" className="brand" style={{ textDecoration: 'none' }}>
          <Boxes size={26} style={{ color: 'var(--accent)' }} />
          <span>
            Database Access Platform
            <small>control plane</small>
          </span>
        </Link>
        <nav>
          {NAV.map((s) => (
            <div key={s.section}>
              <div className="nav-section">{s.section}</div>
              {s.items.map((i) => (
                <NavLink key={i.to} to={i.to} end={i.end} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                  <i.icon aria-hidden="true" />
                  {i.label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-footer">
          <div className="row" style={{ gap: 6 }}>
            <Monitor size={13} />
            {overview.isPending ? 'connecting…' : overview.isError ? <span style={{ color: 'var(--danger)' }}>control plane unreachable</span> : `${online}/${total} components online`}
          </div>
          <button className="btn sm ghost" onClick={() => setTheme(next)} aria-label={`Theme: ${theme}. Switch to ${next}`} style={{ justifyContent: 'flex-start' }}>
            <ThemeIcon /> Theme: {theme}
          </button>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <Breadcrumbs />
          <Link to="/graph" className="btn sm ghost" aria-label="Open graph explorer">
            <Search /> Explore
          </Link>
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

const SEGMENT_LABEL: Record<string, string> = {
  databases: 'Databases', datasources: 'Datasources', applications: 'Applications', teams: 'Teams', tables: 'Tables', routines: 'Routines', graph: 'Graph explorer',
  impact: 'Impact analysis', connections: 'Connections', queries: 'Queries', governance: 'Governance', admin: 'Admin',
};

export function Breadcrumbs() {
  const location = useLocation();
  const detail = useCrumb();
  const crumbs: Crumb[] = [{ label: 'Dashboard', to: '/' }];
  const segs = location.pathname.split('/').filter(Boolean);
  if (segs.length) {
    const first = segs[0];
    crumbs.push({ label: SEGMENT_LABEL[first] ?? first, to: `/${first}` });
    if (segs.length > 1) crumbs.push({ label: detail ?? segs[1] });
  }
  return (
    <nav className="breadcrumbs" aria-label="Breadcrumb">
      {crumbs.map((c, i) => {
        const last = i === crumbs.length - 1;
        return (
          <span key={i} className="row" style={{ gap: 6, minWidth: 0 }}>
            {i > 0 && <ChevronRight aria-hidden="true" />}
            {last || !c.to ? <span className="current" aria-current="page">{c.label}</span> : <Link to={c.to}>{c.label}</Link>}
          </span>
        );
      })}
    </nav>
  );
}
