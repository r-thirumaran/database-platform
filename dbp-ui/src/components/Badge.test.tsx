import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Confidence, EngineBadge, SeverityBadge, SourceBadge } from './Badge';

describe('badges', () => {
  it('renders engine and severity badges with text (never colour alone)', () => {
    render(<><EngineBadge engine="ORACLE" /><SeverityBadge severity="HIGH" /></>);
    expect(screen.getByText('ORACLE')).toBeInTheDocument();
    expect(screen.getByText('HIGH')).toBeInTheDocument();
  });
  it('labels relationship sources in plain words', () => {
    render(<SourceBadge source="PROXY_CORRELATION" />);
    expect(screen.getByTitle('PROXY_CORRELATION')).toHaveTextContent('proxy');
  });
  it('exposes confidence as an accessible percentage', () => {
    render(<Confidence value={0.6} />);
    expect(screen.getByLabelText('confidence 60 percent')).toHaveTextContent('60%');
  });
});
