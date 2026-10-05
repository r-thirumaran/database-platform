import { useEffect, useMemo, useRef, useState } from 'react';
import { useSyncedState } from '../lib/useSyncedState';
import { AppWindow, Database, Layers, Search, Table2, Users, Workflow } from 'lucide-react';
import { useAllTables, useApplications, useDatabases, useDatasources, useRoutines, useTeams } from '../api/hooks';
import type { GraphRootType } from '../api/types';

export interface EntityOption { type: GraphRootType; id: string; label: string; sub?: string }
const ICON: Record<GraphRootType, typeof Database> = { team: Users, application: AppWindow, datasource: Layers, database: Database, table: Table2, routine: Workflow };

/** Combobox over every entity (teams, applications, datasources, databases, tables, routines). */
export function EntityPicker({ types, value, onChange, placeholder = 'Search teams, applications, datasources, tables, routines…', id, autoFocus }: {
  types?: GraphRootType[]; value?: EntityOption | null; onChange: (o: EntityOption) => void; placeholder?: string; id?: string; autoFocus?: boolean;
}) {
  const teams = useTeams();
  const apps = useApplications();
  const dss = useDatasources();
  const dbs = useDatabases();
  const tables = useAllTables();
  const routines = useRoutines();
  const [q, setQ] = useSyncedState(value?.label ?? '');
  const [open, setOpen] = useState(false);
  const [idx, setIdx] = useState(0);
  const ref = useRef<HTMLDivElement>(null);

  const options = useMemo<EntityOption[]>(() => {
    const want = (t: GraphRootType) => !types || types.includes(t);
    const out: EntityOption[] = [];
    if (want('team')) teams.data?.forEach((t) => out.push({ type: 'team', id: t.id, label: t.name, sub: t.displayName }));
    if (want('application')) apps.data?.forEach((a) => out.push({ type: 'application', id: a.id, label: a.name, sub: a.kind }));
    if (want('datasource')) dss.data?.forEach((d) => out.push({ type: 'datasource', id: d.id, label: d.name, sub: d.state }));
    if (want('database')) dbs.data?.forEach((d) => out.push({ type: 'database', id: d.id, label: d.name, sub: d.engine }));
    if (want('table')) tables.data?.forEach((t) => out.push({ type: 'table', id: t.id, label: `${t.schema}.${t.name}`, sub: t.kind }));
    if (want('routine')) routines.data?.forEach((r) => out.push({ type: 'routine', id: r.id, label: `${r.schema}.${r.name}`, sub: r.kind }));
    return out;
  }, [teams.data, apps.data, dss.data, dbs.data, tables.data, routines.data, types]);

  const filtered = useMemo(() => {
    const s = q.trim().toLowerCase();
    const list = s ? options.filter((o) => o.label.toLowerCase().includes(s) || o.type.includes(s)) : options;
    return list.slice(0, 40);
  }, [q, options]);

  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, []);

  const pick = (o: EntityOption) => { onChange(o); setQ(o.label); setOpen(false); };
  const listId = `${id ?? 'entity'}-list`;
  return (
    <div className="search" ref={ref} style={{ position: 'relative' }}>
      <Search />
      <input
        id={id}
        className="input"
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={open && filtered[idx] ? `${listId}-${idx}` : undefined}
        autoFocus={autoFocus}
        placeholder={placeholder}
        value={q}
        onChange={(e) => { setQ(e.target.value); setOpen(true); setIdx(0); }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setIdx((i) => Math.min(filtered.length - 1, i + 1)); }
          else if (e.key === 'ArrowUp') { e.preventDefault(); setIdx((i) => Math.max(0, i - 1)); }
          else if (e.key === 'Enter' && open && filtered[idx]) { e.preventDefault(); pick(filtered[idx]); }
          else if (e.key === 'Escape') setOpen(false);
        }}
      />
      {open && (
        <ul id={listId} role="listbox" className="card" style={{ position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 30, margin: '4px 0 0', padding: 4, listStyle: 'none', maxHeight: 320, overflowY: 'auto' }}>
          {filtered.length === 0 && <li className="muted" style={{ padding: '8px 10px' }}>No matches</li>}
          {filtered.map((o, i) => {
            const Icon = ICON[o.type];
            return (
              <li
                key={`${o.type}:${o.id}`}
                id={`${listId}-${i}`}
                role="option"
                aria-selected={i === idx}
                onMouseDown={(e) => { e.preventDefault(); pick(o); }}
                onMouseEnter={() => setIdx(i)}
                style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', borderRadius: 4, cursor: 'pointer', background: i === idx ? 'var(--accent-soft)' : undefined }}
              >
                <Icon size={14} style={{ color: 'var(--text-3)' }} />
                <span style={{ flex: 1 }}>{o.label}</span>
                <span className="muted small">{o.type}{o.sub ? ` · ${o.sub}` : ''}</span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
