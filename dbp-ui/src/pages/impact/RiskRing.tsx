export function riskTone(score: number): { label: string; color: string } {
  if (score >= 0.75) return { label: 'critical', color: 'var(--status-critical)' };
  if (score >= 0.5) return { label: 'high', color: 'var(--status-serious)' };
  if (score >= 0.25) return { label: 'moderate', color: 'var(--status-warning)' };
  return { label: 'low', color: 'var(--status-good)' };
}

/** Risk score 0..1 as a ring; the label beside it keeps meaning off colour alone. */
export function RiskRing({ score, size = 84 }: { score: number; size?: number }) {
  const tone = riskTone(score);
  const pct = Math.round(score * 100);
  return (
    <div className="row" style={{ gap: 10 }}>
      <div className="risk-ring" style={{ ['--pct' as string]: pct, ['--ring-color' as string]: tone.color, width: size, height: size }} role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label={`Risk score ${pct}: ${tone.label}`}>
        <span>{pct}</span>
      </div>
      <div>
        <div className="muted small">Risk score</div>
        <div style={{ fontWeight: 600, textTransform: 'capitalize' }}>{tone.label}</div>
      </div>
    </div>
  );
}
