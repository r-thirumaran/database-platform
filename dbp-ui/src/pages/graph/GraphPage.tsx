import { useCallback, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Download, ExternalLink, GitBranch, Maximize2, Minus, Plus, RefreshCw, X, AlertTriangle } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { api, type EdgeKind, type Graph, type GraphNode, type GraphNodeType, type GraphRootType } from '../../api';
import { useGraph } from '../../api/hooks';
import { PageHeader } from '../../components/PageHeader';
import { Card, KV } from '../../components/Card';
import { Loading, ErrorState, EmptyState } from '../../components/States';
import { Badge, KindBadge } from '../../components/Badge';
import { EntityPicker, type EntityOption } from '../../components/EntityPicker';
import { GraphCanvas, type GraphCanvasHandle } from '../../charts/GraphCanvas';
import { EDGE_STYLE, NODE_SHAPE } from '../../charts/graphStyles';
import { useResettableState } from '../../lib/useSyncedState';
import { NODE_COLOR } from '../../charts/palette';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../api/client';
import { links } from '../../lib/links';
import { fileDownload, formatNumber, relativeTime } from '../../lib/format';
import { Field } from '../../components/Field';

const ALL_TYPES: Array<{ type: GraphNodeType; include: string; label: string }> = [
  { type: 'TEAM', include: 'teams', label: 'Teams' },
  { type: 'APPLICATION', include: 'applications', label: 'Applications' },
  { type: 'DATASOURCE', include: 'datasources', label: 'Datasources' },
  { type: 'DATABASE', include: 'databases', label: 'Databases' },
  { type: 'TABLE', include: 'tables', label: 'Tables' },
  { type: 'ROUTINE', include: 'routines', label: 'Routines' },
];
const ALL_EDGES = Object.keys(EDGE_STYLE) as EdgeKind[];

function mergeGraph(a: Graph, b: Graph): Graph {
  const nodes = new Map(a.nodes.map((n) => [n.id, n]));
  b.nodes.forEach((n) => nodes.set(n.id, n));
  const edges = new Map(a.edges.map((e) => [e.id, e]));
  b.edges.forEach((e) => edges.set(e.id, e));
  return { nodes: [...nodes.values()], edges: [...edges.values()], truncated: a.truncated || b.truncated };
}

export function GraphPage() {
  const [sp, setSp] = useSearchParams();
  const rootParam = sp.get('root') ?? '';
  const depth = Number(sp.get('depth') ?? 2);
  const [types, setTypes] = useState<Set<string>>(new Set(ALL_TYPES.map((t) => t.include)));
  const [edgeKinds, setEdgeKinds] = useState<Set<EdgeKind>>(new Set(ALL_EDGES));
  const viewKey = `${rootParam}|${depth}`;
  const [extra, setExtra] = useResettableState<Graph | null>(null, viewKey);
  const [selected, setSelected] = useResettableState<GraphNode | null>(null, viewKey);
  const canvas = useRef<GraphCanvasHandle>(null);
  const toast = useToast();
  const qc = useQueryClient();

  const query = useMemo(() => ({ root: rootParam || undefined, depth, include: [...types], edgeKinds: [...edgeKinds], limit: 400 }), [rootParam, depth, types, edgeKinds]);
  const graph = useGraph(query);

  // Reflect ?root=type:id into the picker label once the graph is loaded.
  const root = useMemo<EntityOption | null>(() => {
    if (!rootParam) return null;
    const [type, id] = rootParam.split(':');
    const n = graph.data?.nodes.find((x) => x.id === rootParam);
    return { type: type as GraphRootType, id, label: n?.label ?? id };
  }, [rootParam, graph.data]);

  const merged = useMemo(() => {
    if (!graph.data) return null;
    const g = extra ? mergeGraph(graph.data, extra) : graph.data;
    const typeOk = new Set(ALL_TYPES.filter((t) => types.has(t.include)).map((t) => t.type));
    const nodes = g.nodes.filter((n) => typeOk.has(n.type) || n.id === rootParam);
    const ids = new Set(nodes.map((n) => n.id));
    return { ...g, nodes, edges: g.edges.filter((e) => edgeKinds.has(e.kind) && ids.has(e.from) && ids.has(e.to)) };
  }, [graph.data, extra, types, edgeKinds, rootParam]);

  const setParam = (k: string, v?: string) => { const n = new URLSearchParams(sp); if (v) n.set(k, v); else n.delete(k); setSp(n, { replace: true }); };
  const pickRoot = (o: EntityOption) => setParam('root', `${o.type}:${o.id}`);

  const expand = useCallback(async (node: GraphNode) => {
    try {
      const q = { root: node.id, depth: 1, include: ALL_TYPES.map((t) => t.include), edgeKinds: ALL_EDGES, limit: 150 };
      const g = await qc.fetchQuery({ queryKey: ['graph', q], queryFn: () => api.graph.get(q) });
      setExtra((prev) => (prev ? mergeGraph(prev, g) : g));
      toast.toast(`Expanded ${node.label}: ${g.nodes.length - 1} neighbours`);
    } catch (e) {
      toast.error(errorMessage(e));
    }
  }, [qc, toast, setExtra]);

  const exportPng = () => {
    const blob = canvas.current?.exportPng();
    if (blob) fileDownload(`dbp-graph-${rootParam.replace(':', '-') || 'all'}.png`, blob, 'image/png');
  };
  const toggleSet = <T,>(set: Set<T>, v: T, setter: (s: Set<T>) => void) => { const n = new Set(set); if (n.has(v)) n.delete(v); else n.add(v); setter(n); };

  return (
    <>
      <PageHeader title="Graph explorer" subtitle="Teams, applications, datasources, databases, tables and routines, connected by what the platform observed or was told." />
      <div className="filters" style={{ alignItems: 'flex-end' }}>
        <div className="field grow" style={{ minWidth: 320 }}>
          <label htmlFor="graph-root">Root</label>
          <EntityPicker id="graph-root" value={root} onChange={pickRoot} />
        </div>
        <Field label="Depth">{(id) => <select id={id} className="input" value={depth} onChange={(e) => setParam('depth', e.target.value)}>{[1, 2, 3, 4].map((d) => <option key={d} value={d}>{d}</option>)}</select>}</Field>
        {rootParam && <button className="btn" onClick={() => setParam('root')}><X /> Whole graph</button>}
        <button className="btn ghost" onClick={() => graph.refetch()} disabled={graph.isFetching}><RefreshCw /> Refresh</button>
      </div>

      <div className="graph-page">
        <div className="graph-canvas">
          {graph.isPending && <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}><Loading inline /></div>}
          {graph.isError && <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}><ErrorState inline error={graph.error} retry={() => graph.refetch()} /></div>}
          {merged && merged.nodes.length === 0 && <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}><EmptyState inline title="Nothing to show" hint="Pick a root or enable more node types." /></div>}
          {merged && <GraphCanvas ref={canvas} graph={merged} rootId={rootParam || undefined} onSelect={setSelected} onExpand={expand} />}
          <div className="graph-toolbar">
            <button className="btn icon" onClick={() => canvas.current?.zoom(1.25)} aria-label="Zoom in" title="Zoom in"><Plus /></button>
            <button className="btn icon" onClick={() => canvas.current?.zoom(0.8)} aria-label="Zoom out" title="Zoom out"><Minus /></button>
            <button className="btn icon" onClick={() => canvas.current?.fit()} aria-label="Fit to screen" title="Fit"><Maximize2 /></button>
            <button className="btn icon" onClick={() => canvas.current?.relayout()} aria-label="Re-run layout" title="Re-layout"><RefreshCw /></button>
            <button className="btn icon" onClick={exportPng} aria-label="Export PNG" title="Export PNG"><Download /></button>
          </div>
          {merged?.truncated && (
            <div className="graph-notice notice warning" role="status"><AlertTriangle /><div><strong>Graph truncated.</strong> The server capped the result; pick a root or reduce the depth to see everything.</div></div>
          )}
          {merged && !merged.truncated && (
            <div className="graph-notice small muted" style={{ background: 'var(--surface)', padding: '2px 8px', borderRadius: 4, border: '1px solid var(--border)' }}>{merged.nodes.length} nodes · {merged.edges.length} edges · double-click a node to expand</div>
          )}
        </div>

        <aside className="graph-side">
          {selected ? <NodePanel node={selected} onExpand={() => expand(selected)} onClose={() => setSelected(null)} onFocus={() => canvas.current?.focus(selected.id)} /> : (
            <Card title="Details"><p className="muted small">Click a node to see its details. Double-click to load its neighbours.</p></Card>
          )}
          <Card title="Node types" hint="click to toggle">
            <div className="graph-legend">
              {ALL_TYPES.map((t) => (
                <button key={t.type} type="button" className="item" onClick={() => toggleSet(types, t.include, setTypes)} aria-pressed={types.has(t.include)} style={{ all: 'unset', display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', opacity: types.has(t.include) ? 1 : 0.4 }}>
                  <span className="shape" style={{ background: NODE_COLOR[t.type], borderRadius: NODE_SHAPE[t.type] === 'ellipse' ? '50%' : NODE_SHAPE[t.type] === 'round-rectangle' ? 3 : 0, transform: NODE_SHAPE[t.type] === 'diamond' ? 'rotate(45deg) scale(0.8)' : undefined, clipPath: NODE_SHAPE[t.type] === 'hexagon' ? 'polygon(25% 5%, 75% 5%, 100% 50%, 75% 95%, 25% 95%, 0 50%)' : undefined }} aria-hidden="true" />
                  {t.label}
                  <span className="muted" style={{ marginLeft: 'auto' }}>{merged?.nodes.filter((n) => n.type === t.type).length ?? 0}</span>
                </button>
              ))}
            </div>
          </Card>
          <Card title="Edge kinds" hint="click to toggle">
            <div className="graph-legend">
              {ALL_EDGES.map((k) => (
                <button key={k} type="button" className="item" onClick={() => toggleSet(edgeKinds, k, setEdgeKinds)} aria-pressed={edgeKinds.has(k)} style={{ all: 'unset', display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', opacity: edgeKinds.has(k) ? 1 : 0.4 }}>
                  <span className="edge" style={{ borderColor: EDGE_STYLE[k].color, borderTopStyle: EDGE_STYLE[k].style, borderTopWidth: EDGE_STYLE[k].width }} aria-hidden="true" />
                  {EDGE_STYLE[k].label}
                  <span className="muted" style={{ marginLeft: 'auto' }}>{merged?.edges.filter((e) => e.kind === k).length ?? 0}</span>
                </button>
              ))}
            </div>
          </Card>
        </aside>
      </div>
    </>
  );
}

function NodePanel({ node, onExpand, onClose, onFocus }: { node: GraphNode; onExpand: () => void; onClose: () => void; onFocus: () => void }) {
  const attrs = Object.entries(node.attrs ?? {}).filter(([k]) => k !== 'depth');
  const fmt = (k: string, v: unknown) => {
    if (v === null || v === undefined || v === '') return <span className="muted">—</span>;
    if (typeof v === 'number') return formatNumber(v);
    if (typeof v === 'boolean') return v ? 'yes' : 'no';
    if (Array.isArray(v)) return v.length ? <span className="badge-row">{v.map((x) => <Badge key={String(x)} outline>{String(x)}</Badge>)}</span> : <span className="muted">—</span>;
    if (k.toLowerCase().endsWith('at') && typeof v === 'string') return relativeTime(v);
    return String(v);
  };
  return (
    <Card title={<span className="row" style={{ gap: 6 }}><span className="shape" style={{ width: 10, height: 10, background: NODE_COLOR[node.type], display: 'inline-block', borderRadius: 2 }} aria-hidden="true" />{node.type.toLowerCase()}</span>} actions={<button className="btn ghost icon sm" onClick={onClose} aria-label="Close details"><X /></button>}>
      <h3 style={{ marginBottom: 8, wordBreak: 'break-word' }}>{node.label}</h3>
      <KV items={attrs.map(([k, v]) => [k, fmt(k, v)])} />
      {'kind' in node.attrs && typeof node.attrs.kind === 'string' && <div style={{ marginTop: 6 }}><KindBadge kind={node.attrs.kind} /></div>}
      <div className="btn-group" style={{ marginTop: 12 }}>
        <Link className="btn sm primary" to={links.forNode(node.type, node.refId)}><ExternalLink /> Open</Link>
        {node.type === 'TABLE' && <Link className="btn sm" to={links.impactTable(node.refId)}><GitBranch /> Impact</Link>}
        <button className="btn sm" onClick={onExpand}><Plus /> Expand</button>
        <button className="btn sm ghost" onClick={onFocus}>Focus</button>
      </div>
    </Card>
  );
}
