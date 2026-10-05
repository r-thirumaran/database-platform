import { defineConfig } from '@playwright/test';

// Smoke tests run against the vite dev server (5173), which proxies /api to the control plane on 8080.
//   npm run e2e        → starts `npm run mock` on 8080 (no Java needed)
//   npm run e2e:real   → PW_USE_REAL_API=1: expects a real control plane (seeded with POST /api/v1/seed/demo);
//                        PW_BASE_URL overrides its address (default http://localhost:8080)
// Chromium is expected at $PLAYWRIGHT_BROWSERS_PATH (e.g. /opt/pw-browsers); do not run `playwright install`.
const realApi = process.env.PW_USE_REAL_API === '1' || process.env.PW_USE_REAL_API === 'true' || !!process.env.PW_BASE_URL;
const apiUrl = (process.env.PW_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  retries: 0,
  reporter: 'list',
  workers: 1, // the specs mutate the shared control plane state
  use: {
    baseURL: 'http://localhost:5173',
    headless: true,
    viewport: { width: 1366, height: 800 },
  },
  webServer: [
    ...(realApi ? [] : [{ command: 'npm run mock', port: 8080, reuseExistingServer: true, timeout: 30_000 }]),
    { command: 'npm run dev', port: 5173, reuseExistingServer: !process.env.CI, timeout: 60_000, env: { DBP_API_URL: apiUrl } },
  ],
});
