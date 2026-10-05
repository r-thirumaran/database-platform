import { defineConfig } from '@playwright/test';

// Smoke test against the mock server (port 8080) + vite dev server (5173).
// Chromium is expected at $PLAYWRIGHT_BROWSERS_PATH (e.g. /opt/pw-browsers); do not run `playwright install`.
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    headless: true,
    viewport: { width: 1366, height: 800 },
  },
  webServer: [
    { command: 'npm run mock', port: 8080, reuseExistingServer: true, timeout: 30_000 },
    { command: 'npm run dev', port: 5173, reuseExistingServer: true, timeout: 60_000 },
  ],
});
