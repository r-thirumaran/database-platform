import { useMemo, useState } from 'react';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ChartTooltip } from './ChartTooltip';
import { compact, formatNumber } from '../lib/format';

export interface Series { key: string; name: string; color: string }

/**
 * Horizontal stacked bars for a handful of categories × 2–4 series. Thin marks (18px), square at the
 * baseline, 2px surface gap between segments, hairline grid, legend always present, table twin
 * behind a toggle so no value is gated behind hover.
 */
export function StackedBars<T extends Record<string, unknown>>({ data, series, category, height, refetching = false, valueFormat = compact, ariaLabel }: {
  data: T[]; series: Series[]; category: keyof T & string; height?: number; refetching?: boolean; valueFormat?: (n: number) => string; ariaLabel: string;
}) {
  const [view, setView] = useState<'chart' | 'table'>('chart');
  const h = height ?? Math.max(160, 34 * data.length + 40);
  const totals = useMemo(() => data.map((d) => series.reduce((s, x) => s + Number(d[x.key] ?? 0), 0)), [data, series]);
  return (
    <div className={`chart-wrap ${refetching ? 'refetching' : ''}`}>
      <div className="row between" style={{ marginBottom: 8 }}>
        <div className="legend" aria-label="Legend">
          {series.map((s) => (
            <span key={s.key}>
              <span className="swatch" style={{ background: s.color }} aria-hidden="true" />
              {s.name}
            </span>
          ))}
        </div>
        <div className="segmented" role="group" aria-label="View">
          <button type="button" aria-pressed={view === 'chart'} onClick={() => setView('chart')}>Chart</button>
          <button type="button" aria-pressed={view === 'table'} onClick={() => setView('table')}>Table</button>
        </div>
      </div>
      {view === 'chart' ? (
        <div style={{ width: '100%', height: h }} role="img" aria-label={ariaLabel}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} layout="vertical" margin={{ top: 4, right: 48, bottom: 4, left: 4 }} barCategoryGap={10}>
              <CartesianGrid horizontal={false} stroke="var(--grid)" />
              <XAxis type="number" tickFormatter={(v: number) => compact(v)} axisLine={{ stroke: 'var(--axis)' }} tickLine={false} />
              <YAxis type="category" dataKey={category} width={130} tickLine={false} axisLine={false} interval={0} />
              <Tooltip content={<ChartTooltip format={formatNumber} />} cursor={{ fill: 'var(--surface-2)' }} isAnimationActive={false} />
              {series.map((s, i) => (
                <Bar
                  key={s.key}
                  dataKey={s.key}
                  name={s.name}
                  stackId="a"
                  fill={s.color}
                  stroke="var(--surface)"
                  strokeWidth={2}
                  barSize={18}
                  isAnimationActive={false}
                  radius={i === series.length - 1 ? [0, 4, 4, 0] : 0}
                  label={i === series.length - 1 ? { position: 'right', fill: 'var(--text-2)', fontSize: 11.5, formatter: (_v: number, _n?: unknown, props?: { index?: number }) => valueFormat(totals[props?.index ?? 0] ?? 0) } : undefined}
                />
              ))}
            </BarChart>
          </ResponsiveContainer>
        </div>
      ) : (
        <div className="table-wrap">
          <table className="data compact">
            <thead>
              <tr>
                <th>{category}</th>
                {series.map((s) => (
                  <th key={s.key} className="num">{s.name}</th>
                ))}
                <th className="num">Total</th>
              </tr>
            </thead>
            <tbody>
              {data.map((d, i) => (
                <tr key={String(d[category])}>
                  <td>{String(d[category])}</td>
                  {series.map((s) => (
                    <td key={s.key} className="num">{formatNumber(Number(d[s.key] ?? 0))}</td>
                  ))}
                  <td className="num">{formatNumber(totals[i])}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
