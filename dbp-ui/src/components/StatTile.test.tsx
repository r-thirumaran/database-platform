import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { StatTile } from './StatTile';
import { Meter } from './Meter';

describe('StatTile', () => {
  it('compacts numeric values and keeps the exact figure in the title', () => {
    render(<MemoryRouter><StatTile label="Queries last hour" value={48_211} to="/queries" /></MemoryRouter>);
    const value = screen.getByText('48.2K');
    expect(value).toHaveAttribute('title', '48,211');
    expect(screen.getByRole('link')).toHaveAttribute('href', '/queries');
  });
});

describe('Meter', () => {
  it('marks a nearly full pool as critical with an icon label', () => {
    render(<Meter label="payments" value={23} max={25} />);
    const meter = screen.getByRole('meter');
    expect(meter).toHaveAttribute("aria-valuenow", "23");
    expect(meter.className).toContain('critical');
    expect(screen.getByLabelText('critical')).toBeInTheDocument();
  });
});
