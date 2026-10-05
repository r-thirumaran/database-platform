/** Route helpers so pages never hard-code paths of other pages. */
export const links = {
  dashboard: () => '/',
  databases: () => '/databases',
  database: (id: string) => `/databases/${id}`,
  datasources: () => '/datasources',
  datasource: (id: string) => `/datasources/${id}`,
  applications: () => '/applications',
  application: (id: string) => `/applications/${id}`,
  teams: () => '/teams',
  team: (id: string) => `/teams/${id}`,
  tables: (q?: Record<string, string | undefined>) => {
    const sp = new URLSearchParams();
    for (const [k, v] of Object.entries(q ?? {})) if (v) sp.set(k, v);
    const s = sp.toString();
    return `/tables${s ? `?${s}` : ''}`;
  },
  table: (id: string) => `/tables/${id}`,
  routines: () => '/routines',
  routine: (id: string) => `/routines/${id}`,
  graph: (root?: string, depth?: number) => `/graph${root ? `?root=${encodeURIComponent(root)}${depth ? `&depth=${depth}` : ''}` : ''}`,
  impactTable: (id: string) => `/impact?table=${id}`,
  impactColumn: (id: string) => `/impact?column=${id}`,
  impact: () => '/impact',
  connections: () => '/connections',
  queries: (q?: Record<string, string | undefined>) => {
    const sp = new URLSearchParams();
    for (const [k, v] of Object.entries(q ?? {})) if (v) sp.set(k, v);
    const s = sp.toString();
    return `/queries${s ? `?${s}` : ''}`;
  },
  governance: () => '/governance',
  admin: () => '/admin',
  forNode: (type: string, refId: string): string => {
    switch (type.toUpperCase()) {
      case 'TEAM': return links.team(refId);
      case 'APPLICATION': return links.application(refId);
      case 'DATASOURCE': return links.datasource(refId);
      case 'DATABASE': return links.database(refId);
      case 'TABLE': return links.table(refId);
      case 'ROUTINE': return links.routine(refId);
      default: return '/';
    }
  },
};
