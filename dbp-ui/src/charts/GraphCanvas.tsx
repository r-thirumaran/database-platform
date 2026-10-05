import { forwardRef, useEffect, useImperativeHandle, useMemo, useRef } from 'react';
import cytoscape, { type Core, type ElementDefinition, type StylesheetStyle } from 'cytoscape';
import fcose from 'cytoscape-fcose';
import type { EdgeKind, Graph, GraphNode, GraphNodeType } from '../api/types';
import { NODE_COLOR, cssVar } from './palette';
import { useThemeVersion } from '../lib/useThemeVersion';

cytoscape.use(fcose);

export interface GraphCanvasHandle {
  fit: () => void;
  zoom: (factor: number) => void;
  relayout: () => void;
  exportPng: () => Blob | null;
  focus: (nodeId: string) => void;
}

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
};

function buildStyles(): StylesheetStyle[] {
  const text = cssVar('--text');
  const surface = cssVar('--surface');
  const accent = cssVar('--accent');
  const styles: StylesheetStyle[] = [
    {
      selector: 'node',
      style: {
        label: 'data(label)', 'font-family': 'system-ui, sans-serif', 'font-size': 10, color: text, 'text-valign': 'bottom', 'text-margin-y': 4,
        'text-wrap': 'ellipsis', 'text-max-width': '120px', width: 28, height: 28, 'border-width': 2, 'border-color': surface,
        'text-background-color': surface, 'text-background-opacity': 0.85, 'text-background-padding': '2px', 'text-background-shape': 'roundrectangle',
        'overlay-padding': 6, 'min-zoomed-font-size': 7,
      } as never,
    },
    { selector: 'node:selected', style: { 'border-color': accent, 'border-width': 3 } as never },
    { selector: 'node.root', style: { width: 40, height: 40, 'font-weight': 'bold', 'border-color': accent, 'border-width': 3 } as never },
    { selector: 'node.dim', style: { opacity: 0.25 } as never },
    {
      selector: 'edge',
      style: { 'curve-style': 'bezier', 'target-arrow-shape': 'triangle', 'arrow-scale': 0.8, 'font-size': 8, color: cssVar('--text-2'), 'text-rotation': 'autorotate', 'text-background-color': surface, 'text-background-opacity': 0.85, 'text-background-padding': '1px' } as never,
    },
    { selector: 'edge:selected', style: { label: 'data(kindLabel)', width: 3 } as never },
    { selector: 'edge.dim', style: { opacity: 0.15 } as never },
    { selector: 'edge.highlight', style: { label: 'data(kindLabel)' } as never },
  ];
  for (const [type, color] of Object.entries(NODE_COLOR)) {
    styles.push({ selector: `node[type = "${type}"]`, style: { 'background-color': cssVar(color), shape: NODE_SHAPE[type as GraphNodeType] } as never });
  }
  for (const [kind, s] of Object.entries(EDGE_STYLE)) {
    styles.push({ selector: `edge[kind = "${kind}"]`, style: { 'line-color': cssVar(s.color), 'target-arrow-color': cssVar(s.color), 'line-style': s.style, width: s.width } as never });
  }
  return styles;
}

const LAYOUT = { name: 'fcose', animate: false, quality: 'default', randomize: false, nodeSeparation: 90, idealEdgeLength: () => 110, nodeRepulsion: () => 6000, padding: 30, fit: true } as const;

export const GraphCanvas = forwardRef<GraphCanvasHandle, {
  graph: Graph;
  rootId?: string;
  onSelect?: (node: GraphNode | null) => void;
  onExpand?: (node: GraphNode) => void;
  className?: string;
}>(function GraphCanvas({ graph, rootId, onSelect, onExpand, className = 'cy' }, ref) {
  const el = useRef<HTMLDivElement>(null);
  const cy = useRef<Core | null>(null);
  const themeVersion = useThemeVersion();
  const handlers = useRef({ onSelect, onExpand });
  handlers.current = { onSelect, onExpand };

  const elements = useMemo<ElementDefinition[]>(() => {
    const ids = new Set(graph.nodes.map((n) => n.id));
    return [
      ...graph.nodes.map((n) => ({ data: { id: n.id, label: n.label, type: n.type, refId: n.refId, attrs: n.attrs }, classes: n.id === rootId ? 'root' : '' })),
      ...graph.edges.filter((e) => ids.has(e.from) && ids.has(e.to)).map((e) => ({ data: { id: e.id, source: e.from, target: e.to, kind: e.kind, kindLabel: EDGE_STYLE[e.kind]?.label ?? e.kind, attrs: e.attrs } })),
    ];
  }, [graph, rootId]);

  useEffect(() => {
    if (!el.current) return;
    const instance = cytoscape({ container: el.current, elements: [], style: buildStyles(), wheelSensitivity: 0.2, minZoom: 0.15, maxZoom: 3, boxSelectionEnabled: false });
    instance.on('tap', 'node', (ev) => {
      const d = ev.target.data();
      handlers.current.onSelect?.({ id: d.id, type: d.type, label: d.label, refId: d.refId, attrs: d.attrs ?? {} });
      const node = ev.target;
      instance.elements().removeClass('dim highlight');
      const hood = node.closedNeighborhood();
      instance.elements().not(hood).addClass('dim');
      node.connectedEdges().addClass('highlight');
    });
    instance.on('dbltap', 'node', (ev) => {
      const d = ev.target.data();
      handlers.current.onExpand?.({ id: d.id, type: d.type, label: d.label, refId: d.refId, attrs: d.attrs ?? {} });
    });
    instance.on('tap', (ev) => {
      if (ev.target === instance) {
        instance.elements().removeClass('dim highlight');
        handlers.current.onSelect?.(null);
      }
    });
    cy.current = instance;
    return () => {
      instance.destroy();
      cy.current = null;
    };
  }, []);

  // Sync elements: add new, remove gone, keep positions of survivors; lay out only the newcomers' graph.
  useEffect(() => {
    const instance = cy.current;
    if (!instance) return;
    const wanted = new Set(elements.map((e) => e.data.id as string));
    const existing = new Set(instance.elements().map((e) => e.id()));
    instance.elements().filter((e) => !wanted.has(e.id())).remove();
    const fresh = elements.filter((e) => !existing.has(e.data.id as string));
    instance.elements().removeClass('dim highlight');
    instance.nodes().removeClass('root');
    if (rootId) instance.$id(rootId).addClass('root');
    if (fresh.length) {
      instance.add(fresh);
      instance.layout({ ...LAYOUT, randomize: existing.size === 0 } as never).run();
    } else if (instance.elements().length) {
      instance.fit(undefined, 30);
    }
  }, [elements, rootId]);

  useEffect(() => {
    cy.current?.style(buildStyles() as never);
  }, [themeVersion]);

  useImperativeHandle(ref, () => ({
    fit: () => cy.current?.fit(undefined, 30),
    zoom: (factor) => { const c = cy.current; if (!c) return; c.zoom({ level: c.zoom() * factor, renderedPosition: { x: c.width() / 2, y: c.height() / 2 } }); },
    relayout: () => cy.current?.layout({ ...LAYOUT, randomize: true } as never).run(),
    exportPng: () => { const c = cy.current; if (!c) return null; return c.png({ output: 'blob', full: true, scale: 2, bg: cssVar('--surface') }) as unknown as Blob; },
    focus: (id) => { const c = cy.current; if (!c) return; const n = c.$id(id); if (n.length) { c.animate({ center: { eles: n }, zoom: Math.max(c.zoom(), 1.2) }, { duration: 250 }); n.select(); } },
  }), []);

  return <div ref={el} className={className} role="application" aria-label="Dependency graph" tabIndex={0} />;
});
