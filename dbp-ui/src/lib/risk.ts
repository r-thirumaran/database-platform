export function riskTone(score: number): { label: string; color: string } {
  if (score >= 0.75) return { label: 'critical', color: 'var(--status-critical)' };
  if (score >= 0.5) return { label: 'high', color: 'var(--status-serious)' };
  if (score >= 0.25) return { label: 'moderate', color: 'var(--status-warning)' };
  return { label: 'low', color: 'var(--status-good)' };
}
