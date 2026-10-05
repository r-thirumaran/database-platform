import { useMemo, useState, type ReactNode } from 'react';
import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { EmptyState } from './States';

export interface Column<T> {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  sort?: (row: T) => string | number | null | undefined;
  align?: 'left' | 'right';
  className?: string;
  width?: string | number;
}

export function DataTable<T>({
  rows,
  columns,
  rowKey,
  rowLink,
  onRowClick,
  empty,
  compact = false,
  initialSort,
  caption,
  maxHeight,
}: {
  rows: T[];
  columns: Column<T>[];
  rowKey: (row: T) => string;
  rowLink?: (row: T) => string | undefined;
  onRowClick?: (row: T) => void;
  empty?: ReactNode;
  compact?: boolean;
  initialSort?: { key: string; dir: 'asc' | 'desc' };
  caption?: string;
  maxHeight?: number;
}) {
  const navigate = useNavigate();
  const [sort, setSort] = useState<{ key: string; dir: 'asc' | 'desc' } | null>(initialSort ?? null);
  const sorted = useMemo(() => {
    if (!sort) return rows;
    const col = columns.find((c) => c.key === sort.key);
    if (!col?.sort) return rows;
    const s = col.sort;
    return [...rows].sort((a, b) => {
      const va = s(a);
      const vb = s(b);
      if (va === vb) return 0;
      if (va === null || va === undefined) return 1;
      if (vb === null || vb === undefined) return -1;
      const r = typeof va === 'number' && typeof vb === 'number' ? va - vb : String(va).localeCompare(String(vb));
      return sort.dir === 'asc' ? r : -r;
    });
  }, [rows, sort, columns]);

  if (rows.length === 0) return <>{empty ?? <EmptyState inline />}</>;
  const toggle = (key: string) => setSort((s) => (s?.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' }));
  const clickable = !!(rowLink || onRowClick);
  const activate = (row: T) => {
    if (onRowClick) onRowClick(row);
    else if (rowLink) {
      const l = rowLink(row);
      if (l) navigate(l);
    }
  };

  return (
    <div className="table-wrap" style={maxHeight ? { maxHeight, overflowY: 'auto' } : undefined}>
      <table className={`data ${compact ? 'compact' : ''}`}>
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} className={c.align === 'right' ? 'num' : ''} style={c.width ? { width: c.width } : undefined} aria-sort={sort?.key === c.key ? (sort.dir === 'asc' ? 'ascending' : 'descending') : undefined}>
                {c.sort ? (
                  <button type="button" onClick={() => toggle(c.key)}>
                    {c.header}
                    {sort?.key === c.key ? sort.dir === 'asc' ? <ArrowUp size={12} /> : <ArrowDown size={12} /> : <ArrowUpDown size={12} style={{ opacity: 0.4 }} />}
                  </button>
                ) : (
                  c.header
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {withUniqueKeys(sorted, rowKey).map(([row, key]) => (
            <tr
              key={key}
              className={clickable ? 'clickable' : ''}
              tabIndex={clickable ? 0 : undefined}
              onClick={clickable ? (e) => { if ((e.target as HTMLElement).closest('a,button,input,select')) return; activate(row); } : undefined}
              onKeyDown={clickable ? (e) => { if (e.key === 'Enter' && e.target === e.currentTarget) activate(row); } : undefined}
            >
              {columns.map((c) => (
                <td key={c.key} className={`${c.align === 'right' ? 'num' : ''} ${c.className ?? ''}`}>
                  {c.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Pairs rows with their key; a repeated key gets a positional suffix so React never sees duplicates. */
function withUniqueKeys<T>(rows: T[], rowKey: (row: T) => string): Array<[T, string]> {
  const seen = new Map<string, number>();
  return rows.map((row) => {
    const k = rowKey(row);
    const n = seen.get(k) ?? 0;
    seen.set(k, n + 1);
    return [row, n === 0 ? k : `${k}#${n}`];
  });
}
