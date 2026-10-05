import type { ApiErrorBody } from './types';

/**
 * Minimal typed fetch wrapper for the control plane API.
 *
 * `VITE_API_BASE` (default "") is prepended to every path, so the UI can be served by the control
 * plane itself (same origin), by the Vite dev server (proxy), or from another host.
 */
export const API_BASE: string = ((import.meta.env?.VITE_API_BASE as string | undefined) ?? '').replace(/\/$/, '');
export const API_PREFIX = '/api/v1';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly path?: string;
  readonly body?: unknown;

  constructor(status: number, code: string, message: string, path?: string, body?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.path = path;
    this.body = body;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export type QueryParams = Record<string, string | number | boolean | string[] | undefined | null>;

export function buildQuery(params?: QueryParams): string {
  if (!params) return '';
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v)) {
      if (v.length === 0) continue;
      sp.set(k, v.join(','));
    } else {
      sp.set(k, String(v));
    }
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  params?: QueryParams;
  signal?: AbortSignal;
  headers?: Record<string, string>;
}

async function normaliseError(res: Response, url: string): Promise<ApiError> {
  let body: unknown;
  let parsed: Partial<ApiErrorBody> | undefined;
  const text = await res.text().catch(() => '');
  if (text) {
    try {
      body = JSON.parse(text);
      if (body && typeof body === 'object') parsed = body as Partial<ApiErrorBody>;
    } catch {
      body = text;
    }
  }
  const code = parsed?.error ?? (res.status === 404 ? 'NOT_FOUND' : res.status >= 500 ? 'SERVER_ERROR' : 'REQUEST_FAILED');
  const message = parsed?.message ?? (typeof body === 'string' && body ? body : `${res.status} ${res.statusText || 'request failed'}`);
  return new ApiError(res.status, code, message, parsed?.path ?? url, body);
}

export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const url = `${API_BASE}${path.startsWith('/api') || path.startsWith('/actuator') ? '' : API_PREFIX}${path}${buildQuery(opts.params)}`;
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  let body: BodyInit | undefined;
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(opts.body);
  }
  let res: Response;
  try {
    res = await fetch(url, { method: opts.method ?? 'GET', headers, body, signal: opts.signal });
  } catch (e) {
    throw new ApiError(0, 'NETWORK_ERROR', e instanceof Error ? e.message : 'Network error', url);
  }
  if (!res.ok) throw await normaliseError(res, url);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError(res.status, 'BAD_RESPONSE', 'Response was not valid JSON', url, text);
  }
}

export const http = {
  get: <T>(path: string, params?: QueryParams, signal?: AbortSignal) => request<T>(path, { params, signal }),
  post: <T>(path: string, body?: unknown, params?: QueryParams) => request<T>(path, { method: 'POST', body, params }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  delete: <T = void>(path: string) => request<T>(path, { method: 'DELETE' }),
};

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.status ? `${e.message} (${e.code} ${e.status})` : e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}
