import { expect, type APIRequestContext, type Page, type TestInfo } from '@playwright/test';

export const API = '/api/v1';
/** True when the suite targets the real control plane (`npm run e2e:real`) instead of `npm run mock`. */
export const REAL_API = process.env.PW_USE_REAL_API === '1' || process.env.PW_USE_REAL_API === 'true' || !!process.env.PW_BASE_URL;

export async function getJson<T = unknown>(request: APIRequestContext, path: string): Promise<T> {
  const res = await request.get(`${API}${path}`);
  expect(res.ok(), `${path} → ${res.status()}`).toBeTruthy();
  return (await res.json()) as T;
}

export interface Named { id: string; name: string }
export async function byName<T extends Named>(request: APIRequestContext, path: string, name: string): Promise<T> {
  const list = await getJson<T[]>(request, path);
  const hit = list.find((x) => x.name === name);
  expect(hit, `${name} in ${path}`).toBeTruthy();
  return hit!;
}

/** Waits for a toast with the text to show, then to disappear, so the next identical toast can be asserted. */
export async function expectToast(page: Page, text: string | RegExp) {
  const toast = page.locator('.toast').filter({ hasText: text });
  await expect(toast.first()).toBeVisible({ timeout: 15_000 });
  await expect(toast).toHaveCount(0, { timeout: 12_000 });
}

export interface Issue { kind: 'console' | 'pageerror' | 'http'; detail: string }

/**
 * Records console errors, uncaught exceptions and failed/4xx/5xx requests for a page. Call `assertClean()`
 * at the end of a test: every page of the portal must render without any of them against the real API.
 */
export function watch(page: Page, testInfo: TestInfo, options: { allowStatus?: (url: string, status: number) => boolean } = {}) {
  const issues: Issue[] = [];
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    // Chromium logs its own line for every failed resource; tolerate it for responses the test allows
    if (msg.text().startsWith('Failed to load resource') && options.allowStatus) {
      const m = /status of (\d{3})/.exec(msg.text());
      if (m && options.allowStatus(msg.location().url, Number(m[1]))) return;
    }
    issues.push({ kind: 'console', detail: msg.text().split('\n')[0].slice(0, 300) });
  });
  page.on('pageerror', (err) => issues.push({ kind: 'pageerror', detail: err.message.slice(0, 300) }));
  page.on('response', (res) => {
    const url = res.url();
    if (res.status() < 400 || url.includes('/@vite') || url.includes('/node_modules/')) return;
    if (options.allowStatus?.(url, res.status())) return;
    issues.push({ kind: 'http', detail: `${res.request().method()} ${url.replace(/^https?:\/\/[^/]+/, '')} → ${res.status()}` });
  });
  page.on('requestfailed', (req) => {
    const f = req.failure()?.errorText ?? '';
    if (f.includes('ERR_ABORTED')) return;
    issues.push({ kind: 'http', detail: `${req.method()} ${req.url().replace(/^https?:\/\/[^/]+/, '')} failed: ${f}` });
  });
  return {
    issues,
    async assertClean() {
      if (issues.length) await testInfo.attach('issues', { body: JSON.stringify(issues, null, 2), contentType: 'application/json' });
      expect(issues, `console/network issues on ${testInfo.title}:\n${issues.map((i) => `- [${i.kind}] ${i.detail}`).join('\n')}`).toEqual([]);
    },
  };
}

export const heading = (page: Page) => page.getByRole('heading', { level: 1 });
export const dialog = (page: Page) => page.getByRole('dialog');
export const region = (page: Page, name: string) => page.getByRole('region', { name, exact: true });
