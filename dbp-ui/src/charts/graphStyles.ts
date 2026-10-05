import type { EdgeKind, GraphNodeType } from '../api/types';

export const NODE_SHAPE: Record<GraphNodeType, string> = {
  TEAM: 'hexagon', APPLICATION: 'round-rectangle', DATASOURCE: 'diamond', DATABASE: 'barrel', TABLE: 'rectangle', ROUTINE: 'ellipse',
};

export const EDGE_STYLE: Record<EdgeKind, { color: string; style: 'solid' | 'dashed' | 'dotted'; width: number; label: string }> = {
  READS: { color: 'var(--series-1)', style: 'solid', width: 1.5, label: 'reads' },
  WRITES: { color: 'var(--series-2)', style: 'solid', width: 2.5, label: 'writes' },
  CALLS: { color: 'var(--series-5)', style: 'solid', width: 1.5, label: 'calls' },
  REFERENCES: { color: 'var(--text-3)', style: 'dotted', width: 1, label: 'references' },
  FOREIGN_KEY: { color: 'var(--series-3)', style: 'dashed', width: 1, label: 'foreign key' },
  TRIGGERS: { color: 'var(--series-8)', style: 'solid', width: 2, label: 'triggers' },
  OWNS: { color: 'var(--series-7)', style: 'dashed', width: 1, label: 'owns' },
  HOSTS: { color: 'var(--border-strong)', style: 'dotted', width: 1, label: 'hosts' },
  MIGRATES_TO: { color: 'var(--series-6)', style: 'dashed', width: 2, label: 'migrates to' },
  BELONGS_TO: { color: 'var(--border-strong)', style: 'dotted', width: 1, label: 'belongs to' },
  ROUTES_TO: { color: 'var(--series-4)', style: 'solid', width: 1.5, label: 'routes to' },
  PRODUCES: { color: 'var(--series-2)', style: 'dashed', width: 1.5, label: 'produces' },
  GRANTED: { color: 'var(--series-6)', style: 'dotted', width: 1, label: 'granted' },
};
