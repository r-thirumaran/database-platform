import { expect, test } from '@playwright/test';
import { byName, getJson, heading, type Named } from './helpers';

// Smoke test. `npm run e2e` runs it against `npm run mock`; `npm run e2e:real` against a seeded control plane
// (POST /api/v1/seed/demo). Ids are resolved through the API, names come from the shared retail demo dataset.

test('dashboard shows stat tiles and components from the control plane', async ({ page, request }) => {
  await page.goto('/');
  await expect(heading(page)).toHaveText('Dashboard');
  await expect(page.getByText('Proxy connections')).toBeVisible();
  await expect(page.getByText('Open violations')).toBeVisible();
  await expect(page.getByText(/^\d+ · \d+ · \d+$/)).toBeVisible(); // inventory tile rendered from /stats/overview
  const components = await getJson<Array<{ componentId: string }>>(request, '/components');
  if (components.length) await expect(page.getByText(components[0].componentId, { exact: true })).toBeVisible();
  else await expect(page.getByText('No component online')).toBeVisible();
  const connections = await getJson<unknown[]>(request, '/stats/connections?groupBy=application');
  if (connections.length) await expect(page.getByRole('img', { name: /Connections by application/ })).toBeVisible();
  else await expect(page.getByText('No connections observed')).toBeVisible();
});

test('datasource switch dialog loads the impact preview before allowing the switch', async ({ page }) => {
  await page.goto('/datasources');
  await page.getByRole('link', { name: 'sales', exact: true }).first().click();
  await expect(heading(page)).toContainText('sales');
  await page.getByRole('button', { name: 'Switch database' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('What this switch touches')).toBeVisible();
  await expect(dialog.getByText('Applications affected')).toBeVisible();
  await expect(dialog.getByRole('meter', { name: /Risk score/ })).toBeVisible();
  await expect(dialog.getByRole('link', { name: 'orders-service' })).toBeVisible();
  const confirm = dialog.getByRole('button', { name: 'Switch now' });
  await expect(confirm).toBeDisabled();
  await dialog.getByLabel(/Type/).fill('sales');
  await expect(confirm).toBeEnabled();
});

test('table catalogue searches, opens a table and its impact analysis', async ({ page }) => {
  await page.goto('/tables?q=AUDIT_LOG');
  await expect(page.getByText('AUDIT_LOG').first()).toBeVisible();
  await page.getByRole('link', { name: 'SALES.AUDIT_LOG' }).click();
  await expect(heading(page)).toContainText('SALES.AUDIT_LOG');
  await page.locator('.page-header').getByRole('link', { name: 'Impact analysis' }).click();
  await expect(heading(page)).toHaveText('Impact analysis');
  await expect(page.getByRole('meter', { name: /Risk score/ })).toBeVisible();
  await expect(page.getByText('TRG_ORDERS_AUDIT').first()).toBeVisible();
});

test('graph explorer renders a canvas for a root and shows the legend', async ({ page, request }) => {
  const orders = (await getJson<Named[]>(request, '/tables?q=ORDERS&schema=SALES')).find((t) => t.name === 'ORDERS')!;
  await page.goto(`/graph?root=table:${orders.id}&depth=1`);
  await expect(page.getByRole('application', { name: 'Dependency graph' })).toBeVisible();
  await expect(page.getByText(/nodes · .* edges/)).toBeVisible();
  await expect(page.getByRole('button', { name: /Tables/ })).toBeVisible();
});

test('api key creation shows the plaintext once', async ({ page, request }) => {
  await byName(request, '/applications', 'orders-service');
  await page.goto('/applications');
  await page.getByRole('link', { name: 'orders-service', exact: true }).first().click();
  await page.getByLabel('API key label').fill('e2e');
  await page.getByRole('button', { name: 'Create key' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText(/^dbp_[A-Za-z0-9]+_[A-Za-z0-9]+$/)).toBeVisible();
  await dialog.getByRole('button', { name: 'I have stored it' }).click();
  await expect(page.getByText('e2e', { exact: true })).toBeVisible();
});
