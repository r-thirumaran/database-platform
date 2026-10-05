import { describe, expect, it } from 'vitest';
import { compact, formatDuration, formatMs, relativeTime } from './format';

describe('format helpers', () => {
  it('compacts large numbers', () => {
    expect(compact(950)).toBe('950');
    expect(compact(12_900)).toBe('12.9K');
    expect(compact(4_200_000)).toBe('4.2M');
    expect(compact(null)).toBe('—');
  });
  it('formats milliseconds across magnitudes', () => {
    expect(formatMs(0.4)).toBe('0.40 ms');
    expect(formatMs(31)).toBe('31 ms');
    expect(formatMs(2500)).toBe('2.50 s');
    expect(formatMs(90_000)).toBe('1.5 min');
  });
  it('formats durations', () => {
    expect(formatDuration(45)).toBe('45s');
    expect(formatDuration(3700)).toBe('1h 1m');
  });
  it('renders relative time', () => {
    const now = Date.parse('2026-10-05T12:00:00Z');
    expect(relativeTime('2026-10-05T11:59:58Z', now)).toBe('just now');
    expect(relativeTime('2026-10-05T11:30:00Z', now)).toBe('30 minutes ago');
    expect(relativeTime('2026-10-03T12:00:00Z', now)).toBe('2 days ago');
    expect(relativeTime(null)).toBe('never');
  });
});
