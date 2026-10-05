import { expect, test } from '@playwright/test';

// Smoke test against `npm run mock` + `npm run dev` (both started by playwright.config.ts).

test('dashboard shows stat tiles, charts and components from the mock control plane', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible();
  await expect(page.getByText('Proxy connections')).toBeVisible();
  await expect(page.getByText('Open violations')).toBeVisible();
  await expect(page.getByRole('img', { name: /Connections by application/ })).toBeVisible();
  await expect(page.getByText('gw-1', { exact: true })).toBeVisible();
});

test('datasource switch dialog loads the impact preview before allowing the switch', async ({ page }) => {
  await page.goto('/datasources');
  await page.getByRole('link', { name: 'sales', exact: true }).first().click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText('sales');
  await page.getByRole('button', { name: 'Switch database' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('What this switch touches')).toBeVisible();
  await expect(dialog.getByText('Applications affected')).toBeVisible();
  await expect(dialog.getByRole('meter', { name: /Risk score/ })).toBeVisible();
  const confirm = dialog.getByRole('button', { name: 'Switch now' });
  await expect(confirm).toBeDisabled();
  await dialog.getByLabel(/Type/).fill('sales');
  await expect(confirm).toBeEnabled();
});

test('table catalogue filters unowned tables and opens a table with its impact', async ({ page }) => {
  await page.goto('/tables?unowned=true');
  await expect(page.getByText('AUDIT_LOG')).toBeVisible();
  await page.getByRole('link', { name: 'SALES.AUDIT_LOG' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText('SALES.AUDIT_LOG');
  await page.locator('.page-header').getByRole('link', { name: 'Impact analysis' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Impact analysis' })).toBeVisible();
  await expect(page.getByRole('meter', { name: /Risk score/ })).toBeVisible();
  await expect(page.getByText('TRG_ORDERS_AUDIT').first()).toBeVisible();
});

test('graph explorer renders a canvas for a root and shows the legend', async ({ page }) => {
  await page.goto('/graph?root=table:tbl-sales-orders&depth=1');
  await expect(page.getByRole('application', { name: 'Dependency graph' })).toBeVisible();
  await expect(page.getByText(/nodes · .* edges/)).toBeVisible();
  await expect(page.getByRole('button', { name: /Tables/ })).toBeVisible();
});

test('api key creation shows the plaintext once', async ({ page }) => {
  await page.goto('/applications');
  await page.getByRole('link', { name: 'orders-service', exact: true }).first().click();
  await page.getByLabel('API key label').fill('e2e');
  await page.getByRole('button', { name: 'Create key' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText(/^dbp_[a-f0-9]{6}_[a-f0-9]+$/)).toBeVisible();
  await dialog.getByRole('button', { name: 'I have stored it' }).click();
  await expect(page.getByText('e2e', { exact: true })).toBeVisible();
});
