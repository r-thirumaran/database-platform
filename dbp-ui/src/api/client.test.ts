import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, buildQuery, errorMessage, request } from './client';

const mockFetch = (status: number, body: unknown, statusText = '') =>
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(body === undefined ? null : typeof body === 'string' ? body : JSON.stringify(body), { status, statusText, headers: { 'Content-Type': 'application/json' } }),
  );

afterEach(() => vi.restoreAllMocks());

describe('buildQuery', () => {
  it('drops empty values and joins arrays with commas', () => {
    expect(buildQuery({ a: 1, b: '', c: undefined, d: null, e: ['x', 'y'], f: true, g: [] })).toBe('?a=1&e=x%2Cy&f=true');
  });
  it('returns an empty string without params', () => {
    expect(buildQuery()).toBe('');
    expect(buildQuery({})).toBe('');
  });
});

describe('request', () => {
  it('prefixes /api/v1 and parses JSON', async () => {
    const f = mockFetch(200, { id: '1' });
    const res = await request<{ id: string }>('/teams/1');
    expect(res).toEqual({ id: '1' });
    expect(f.mock.calls[0][0]).toBe('/api/v1/teams/1');
  });
  it('sends JSON bodies with the right content type', async () => {
    const f = mockFetch(201, { id: 'new' });
    await request('/teams', { method: 'POST', body: { name: 'x' } });
    const init = f.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe('POST');
    expect(init.body).toBe('{"name":"x"}');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
  });
  it('normalises contract error bodies into ApiError', async () => {
    mockFetch(404, { status: 404, error: 'NOT_FOUND', message: 'team nope not found', path: '/api/v1/teams/nope' });
    await expect(request('/teams/nope')).rejects.toMatchObject({ name: 'ApiError', status: 404, code: 'NOT_FOUND', message: 'team nope not found', isNotFound: true });
  });
  it('normalises non-JSON error bodies', async () => {
    mockFetch(502, '<html>Bad gateway</html>', 'Bad Gateway');
    const err = await request<never>('/stats/overview').catch((e) => e as ApiError);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(502);
    expect(err.code).toBe('SERVER_ERROR');
    expect(errorMessage(err)).toContain('Bad gateway');
  });
  it('maps network failures to status 0', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));
    const err = await request<never>('/teams').catch((e) => e as ApiError);
    expect(err.status).toBe(0);
    expect(err.code).toBe('NETWORK_ERROR');
  });
  it('returns undefined for 204', async () => {
    mockFetch(204, undefined);
    await expect(request('/teams/1', { method: 'DELETE' })).resolves.toBeUndefined();
  });
});
