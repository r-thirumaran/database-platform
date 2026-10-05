import { riskTone } from '../../lib/risk';

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
