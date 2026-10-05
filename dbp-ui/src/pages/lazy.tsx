import { lazy } from 'react';

// Cytoscape is only needed by the graph and impact pages: keep it out of the main bundle.
export const GraphPage = lazy(() => import('./graph/GraphPage').then((m) => ({ default: m.GraphPage })));
export const ImpactPage = lazy(() => import('./impact/ImpactPage').then((m) => ({ default: m.ImpactPage })));
