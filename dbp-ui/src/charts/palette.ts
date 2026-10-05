/**
 * Validated categorical palette (dataviz skill, `scripts/validate_palette.js` passes in light and dark).
 * Series are assigned by entity in fixed order and never cycled: slots are referenced through CSS
 * variables so the same chart renders correctly in both themes.
 */
export const SERIES = ['var(--series-1)', 'var(--series-2)', 'var(--series-3)', 'var(--series-4)', 'var(--series-5)', 'var(--series-6)', 'var(--series-7)', 'var(--series-8)'] as const;

/** Fixed meaning → slot mapping shared across the whole UI. */
export const SERIES_BY_MEASURE = {
  proxy: SERIES[1],          // orange
  gatewayLogical: SERIES[0], // blue
  gatewayPhysical: SERIES[2], // aqua
  reads: SERIES[0],
  writes: SERIES[1],
} as const;

export const NODE_COLOR: Record<string, string> = {
  TEAM: 'var(--series-7)',
  APPLICATION: 'var(--series-1)',
  DATASOURCE: 'var(--series-2)',
  DATABASE: 'var(--series-4)',
  TABLE: 'var(--series-3)',
  ROUTINE: 'var(--series-5)',
};

/** Resolve a CSS variable to its current hex value (Cytoscape cannot read CSS variables). */
export function cssVar(name: string, el: Element = document.documentElement): string {
  const v = name.startsWith('var(') ? name.slice(4, -1) : name;
  return getComputedStyle(el).getPropertyValue(v).trim() || '#888888';
}
