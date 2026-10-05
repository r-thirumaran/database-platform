import type { TooltipProps } from 'recharts';
import { formatNumber } from '../lib/format';

/** One tooltip, every series; values lead, labels follow; series keyed with a short colour stroke. */
export function ChartTooltip({ active, payload, label, format = formatNumber }: TooltipProps<number, string> & { format?: (n: number) => string }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="chart-tooltip" role="status">
      <div className="tt-title">{String(label)}</div>
      {payload.map((p) => (
        <div className="tt-row" key={String(p.dataKey)}>
          <span className="k">
            <span className="swatch line" style={{ background: p.color, display: 'inline-block', width: 14, height: 2 }} aria-hidden="true" />
            {p.name}
          </span>
          <span className="v">{format(Number(p.value ?? 0))}</span>
        </div>
      ))}
    </div>
  );
}
