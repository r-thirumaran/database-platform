import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { API, REAL_API, byName, dialog, expectToast, getJson, heading, region, watch, type Named } from './helpers';

/**
 * Clicks through every page of the portal against the control plane behind the vite proxy (mock or real) and
 * fails on any console error, uncaught exception or 4xx/5xx response. Mutations are undone at the end of each
 * test so the suite can be re-run against the same seeded instance.
 */

interface Datasource extends Named { currentDatabaseId: string; targetDatabaseId: string | null; poolPolicy: { maxConnections: number }; routingRules: Array<{ id: string; applicationId: string | null; tag: string | null; enabled: boolean }> }
interface Table extends Named { schema: string; ownerTeamId: string | null; producerApplicationId: string | null }
interface Grant { id: string; applicationId: string; datasourceId: string }

test.describe.configure({ mode: 'serial' });

test('dashboard', async ({ page }, testInfo) => {
  const w = watch(page, testInfo);
  await page.goto('/');
  await expect(heading(page)).toHaveText('Dashboard');
  await expect(page.getByText(/^\d+ · \d+ · \d+$/)).toBeVisible();
  await expect(page.getByRole('region', { name: 'Hot tables' }).getByRole('link', { name: /SALES\./ }).first()).toBeVisible();
  await expect(page.getByRole('region', { name: 'Top queries' }).locator('tbody tr').first()).toBeVisible();
  for (const g of ['team', 'datasource', 'database', 'application']) {
    await page.getByRole('group', { name: 'Group connections by' }).getByRole('button', { name: g, exact: true }).click();
    await expect(page.getByRole('region', { name: 'Connections' }).getByText(/No connections observed|Gateway logical/).first()).toBeVisible();
  }
  await w.assertClean();
});

test('databases: list, detail, test connection, collectors, schema tree', async ({ page, request }, testInfo) => {
  const w = watch(page, testInfo);
  const oracle = await byName<Named>(request, '/databases', 'sales-oracle');
  await page.goto('/databases');
  await expect(heading(page)).toHaveText('Databases');
  await page.getByRole('link', { name: 'sales-oracle', exact: true }).first().click();
  await expect(heading(page)).toContainText('sales-oracle');

  // demo hosts are unreachable: a clean banner, never a crash or a 500
  await page.getByRole('button', { name: 'Test connection' }).click();
  const banner = page.getByRole('status').filter({ hasText: /Connection failed\.|Connected\./ });
  await expect(banner).toBeVisible({ timeout: 30_000 });
  if (REAL_API) await expect(banner).toContainText('Connection failed.');
  await banner.getByRole('button', { name: 'Dismiss' }).click();

  await page.getByRole('button', { name: 'Run dictionary' }).click();
  await expectToast(page, 'dictionary collection started');
  await page.getByRole('button', { name: 'Run runtime' }).click();
  await expectToast(page, 'runtime collection started');
  if (REAL_API) {
    // the run fails fast (unreachable host / unset credential env var) and the status shows the error
    await expect.poll(async () => {
      const s = await getJson<{ lastError: string | null; lastDictionaryRun: string | null }>(request, `/databases/${oracle.id}/collector-status`);
      return !!(s.lastError || s.lastDictionaryRun);
    }, { timeout: 40_000 }).toBe(true);
    await page.reload();
    await expect(page.getByRole('region', { name: 'Collector' })).toContainText(/Last error/);
  }

  // schema tree → tables and routines of the schema (a single schema is expanded by default)
  const schemaNode = page.getByRole('button', { name: /SALES/ }).first();
  if ((await schemaNode.getAttribute('aria-expanded')) !== 'true') await schemaNode.click();
  await expect(page.getByRole('link', { name: /^ORDERS/ }).first()).toBeVisible();
  await expect(page.getByRole('link', { name: /TRG_ORDERS_AUDIT/ }).first()).toBeVisible();
  await page.getByRole('button', { name: 'Bulk ownership' }).click();
  await expect(dialog(page)).toContainText('Assign owner for every table in SALES');
  await dialog(page).getByRole('button', { name: 'Cancel' }).click();

  // edit dialog opens with the current values and saves unchanged
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(dialog(page).getByLabel('Host')).toHaveValue(/./);
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await expectToast(page, 'Saved');
  await w.assertClean();
});

test('datasources: pool policy, routing rules, grants, switch with impact, migration events', async ({ page, request }, testInfo) => {
  const w = watch(page, testInfo);
  const pg = await byName<Named>(request, '/databases', 'sales-postgres');
  const oracle = await byName<Named>(request, '/databases', 'sales-oracle');
  const apps = await getJson<Named[]>(request, '/applications');
  const portal = apps.find((a) => a.name === 'customer-portal')!;
  // self-healing: drop rules left behind by an interrupted run, make sure the datasource routes to oracle
  let ds = await byName<Datasource>(request, '/datasources', 'sales');
  for (const r of ds.routingRules.filter((r) => r.applicationId === portal.id)) await request.delete(`${API}/datasources/${ds.id}/routing-rules/${r.id}`);
  if (ds.currentDatabaseId !== oracle.id) await request.post(`${API}/datasources/${ds.id}/switch`, { data: { databaseId: oracle.id, note: 'e2e reset' } });
  ds = await byName<Datasource>(request, '/datasources', 'sales');

  await page.goto('/datasources');
  await expect(heading(page)).toHaveText('Datasources');
  await page.getByRole('link', { name: 'sales', exact: true }).first().click();
  await expect(heading(page)).toContainText('sales');

  // pool policy: PUT sends the whole datasource, so routing rules must survive
  const max = page.getByLabel('Max connections');
  const before = ds.poolPolicy.maxConnections;
  await max.fill(String(before + 1));
  await page.getByRole('button', { name: 'Save policy' }).click();
  await expectToast(page, 'Pool policy saved');
  let after = await getJson<Datasource>(request, `/datasources/${ds.id}`);
  expect(after.poolPolicy.maxConnections).toBe(before + 1);
  expect(after.routingRules.length).toBe(ds.routingRules.length);
  await max.fill(String(before));
  await page.getByRole('button', { name: 'Save policy' }).click();
  await expectToast(page, 'Pool policy saved');

  // routing rules: add, edit, toggle, delete
  const rules = region(page, 'Routing rules');
  await rules.getByRole('button', { name: 'Add rule' }).click();
  await dialog(page).getByLabel('Priority').fill('5');
  await dialog(page).getByLabel('Application').selectOption({ label: 'customer-portal' });
  await dialog(page).getByLabel('Route to database').selectOption(pg.id);
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await expectToast(page, 'Rule added');
  const row = rules.locator('tbody tr').filter({ hasText: 'customer-portal' });
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: 'Edit rule' }).click();
  await dialog(page).getByLabel('Priority').fill('7');
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await expectToast(page, 'Rule saved');
  await expect(row.locator('td').first()).toHaveText('7');
  await row.locator('label.switch').click();
  await expect.poll(async () => (await getJson<Datasource>(request, `/datasources/${ds.id}`)).routingRules.find((r) => r.applicationId === apps.find((a) => a.name === 'customer-portal')!.id)?.enabled).toBe(false);
  await row.getByRole('button', { name: 'Delete rule' }).click();
  await dialog(page).getByRole('button', { name: 'Delete' }).click();
  await expectToast(page, 'Rule deleted');
  await expect(row).toHaveCount(0);
  after = await getJson<Datasource>(request, `/datasources/${ds.id}`);
  expect(after.routingRules.map((r) => r.id).sort()).toEqual(ds.routingRules.map((r) => r.id).sort());

  // grants: grant an application that has none yet, then revoke it
  const grants = await getJson<Grant[]>(request, `/access-grants?datasourceId=${ds.id}`);
  const candidate = apps.find((a) => !grants.some((g) => g.applicationId === a.id));
  if (candidate) {
    const gr = region(page, 'Access grants');
    await gr.getByRole('button', { name: 'Grant access' }).click();
    await dialog(page).getByLabel('Application').selectOption({ label: candidate.name });
    await dialog(page).getByRole('button', { name: 'Save' }).click();
    await expectToast(page, 'Grant created');
    const grow = gr.locator('tbody tr').filter({ hasText: candidate.name });
    await expect(grow).toBeVisible();
    await grow.getByRole('button', { name: 'Edit grant' }).click();
    await dialog(page).getByLabel('Max logical connections').fill('33');
    await dialog(page).getByRole('button', { name: 'Save' }).click();
    await expectToast(page, 'Grant saved');
    await expect(grow).toContainText('33');
    await grow.locator('label.switch').click();
    await expect.poll(async () => (await getJson<Array<Grant & { enabled: boolean }>>(request, `/access-grants?datasourceId=${ds.id}&applicationId=${candidate.id}`))[0]?.enabled).toBe(false);
    await grow.getByRole('button', { name: 'Delete grant' }).click();
    await dialog(page).getByRole('button', { name: 'Revoke' }).click();
    await expectToast(page, 'Grant revoked');
    await expect(grow).toHaveCount(0);
  }

  // switch: impact preview, confirmation, migration event, then switch back
  await page.getByRole('button', { name: 'Switch database' }).click();
  const sd = dialog(page);
  await expect(sd.getByText('What this switch touches')).toBeVisible();
  await expect(sd.getByRole('meter', { name: /Risk score/ })).toBeVisible();
  await expect(sd.getByText('Applications affected')).toBeVisible();
  await expect(sd.getByRole('link', { name: 'orders-service' })).toBeVisible();
  await expect(sd.getByText('Teams to notify')).toBeVisible();
  await expect(sd.getByText('Tables routed through this datasource')).toBeVisible();
  await expect(sd.getByRole('link', { name: 'SALES.ORDERS' })).toBeVisible();
  const confirm = sd.getByRole('button', { name: 'Switch now' });
  await sd.getByLabel('Target database').selectOption(pg.id);
  await expect(confirm).toBeDisabled();
  await sd.getByLabel(/^Type/).fill('sales');
  await sd.getByLabel('Note for the migration log (optional)').fill('e2e switch');
  await expect(confirm).toBeEnabled();
  await confirm.click();
  await expectToast(page, /now routes to sales-postgres/);
  await expect(region(page, 'Migration events').locator('li').filter({ hasText: 'e2e switch' })).toBeVisible();
  await expect(region(page, 'Migration events').locator('li').filter({ hasText: 'e2e switch' })).toContainText('sales-postgres');
  await expect(region(page, 'Routing')).toContainText('sales-postgres');
  // switch back through the same dialog
  await page.getByRole('button', { name: 'Switch database' }).click();
  await dialog(page).getByLabel('Target database').selectOption(oracle.id);
  await dialog(page).getByLabel(/^Type/).fill('sales');
  await dialog(page).getByLabel('Note for the migration log (optional)').fill('e2e switch back');
  await dialog(page).getByRole('button', { name: 'Switch now' }).click();
  await expectToast(page, /now routes to sales-oracle/);
  expect((await getJson<Datasource>(request, `/datasources/${ds.id}`)).currentDatabaseId).toBe(oracle.id);

  // edit dialog round-trips the full datasource (routing rules included)
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await dialog(page).getByLabel('Display name').fill('Sales domain data');
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await expectToast(page, 'Saved');
  expect((await getJson<Datasource>(request, `/datasources/${ds.id}`)).routingRules.length).toBe(ds.routingRules.length);
  await w.assertClean();
});

test('applications: summary, api keys, identity rules, access, grants', async ({ page, request }, testInfo) => {
  const w = watch(page, testInfo);
  const app = await byName<Named>(request, '/applications', 'orders-service');
  await page.goto('/applications');
  await expect(heading(page)).toHaveText('Applications');
  await page.getByLabel('Search').fill('orders');
  await page.getByRole('link', { name: 'orders-service', exact: true }).first().click();
  await expect(heading(page)).toContainText('orders-service');
  await expect(page.getByText('Queries 24h')).toBeVisible();
  await expect(region(page, 'Reads, writes and calls').locator('tbody tr').first()).toBeVisible();
  await region(page, 'Reads, writes and calls').getByRole('button', { name: 'writes' }).click();
  await expect(region(page, 'Routines called')).toContainText('ORDER_PKG');

  // api keys: plaintext once, then revoke
  await page.getByLabel('API key label').fill('e2e-key');
  await page.getByRole('button', { name: 'Create key' }).click();
  await expect(dialog(page).getByText(/^dbp_[A-Za-z0-9]+_[A-Za-z0-9]+$/)).toBeVisible();
  await dialog(page).getByRole('button', { name: 'I have stored it' }).click();
  const keys = region(page, 'API keys');
  const krow = keys.locator('tbody tr').filter({ hasText: 'e2e-key' }).first();
  await expect(krow).toBeVisible();
  await krow.getByRole('button', { name: 'Revoke' }).click();
  await dialog(page).getByRole('button', { name: 'Revoke' }).click();
  await expectToast(page, 'Key revoked');
  await expect(krow.getByText('revoked')).toBeVisible();

  // identity rules: add a CIDR, save (full PUT), remove it again
  const cidr = page.getByLabel('10.20.0.0/16');
  await cidr.fill('192.0.2.0/24');
  await cidr.press('Enter');
  await page.getByRole('button', { name: 'Save rules' }).click();
  await expectToast(page, 'Identity rules saved');
  let a = await getJson<{ name: string; identityRules: { cidrs: string[] } }>(request, `/applications/${app.id}`);
  expect(a.identityRules.cidrs).toContain('192.0.2.0/24');
  expect(a.name).toBe('orders-service');
  await page.getByRole('button', { name: 'Remove 192.0.2.0/24' }).click();
  await page.getByRole('button', { name: 'Save rules' }).click();
  await expectToast(page, 'Identity rules saved');
  a = await getJson(request, `/applications/${app.id}`);
  expect(a.identityRules.cidrs).not.toContain('192.0.2.0/24');

  // edit dialog round-trip
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await expectToast(page, 'Saved');
  await w.assertClean();
});

test('teams: list and summary', async ({ page }, testInfo) => {
  const w = watch(page, testInfo);
  await page.goto('/teams');
  await expect(heading(page)).toHaveText('Teams');
  await page.getByRole('link', { name: 'Sales Platform Team' }).first().click();
  await expect(heading(page)).toContainText('Sales Platform Team');
  await expect(page.getByText('Owned tables').first()).toBeVisible();
  await expect(region(page, 'Applications').getByRole('link', { name: 'orders-service' })).toBeVisible();
  await expect(region(page, 'Owned tables').getByRole('link', { name: 'SALES.ORDERS' })).toBeVisible();
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await expectToast(page, 'Saved');
  await w.assertClean();
});

test('tables: filters, paging, detail with ownership, producer, columns, migration, impact link', async ({ page, request }, testInfo) => {
  const w = watch(page, testInfo);
  const oracle = await byName<Named>(request, '/databases', 'sales-oracle');
  const orders = (await getJson<Table[]>(request, '/tables?schema=SALES&q=ORDERS')).find((t) => t.name === 'ORDERS')!;
  const teams = await getJson<Array<Named & { displayName: string }>>(request, '/teams');

  await page.goto('/tables');
  await expect(heading(page)).toHaveText('Tables');
  await page.getByLabel('Database').selectOption(oracle.id);
  await page.getByLabel('Schema').selectOption('SALES');
  await expect(page.getByRole('link', { name: 'SALES.ORDERS' })).toBeVisible();
  await page.getByLabel('Classification').selectOption('PII');
  await expect(page.getByRole('link', { name: 'SALES.CUSTOMER' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'SALES.ORDERS' })).toHaveCount(0);
  await page.getByLabel('Classification').selectOption('');
  await page.getByLabel('Unowned only').check();
  await expect(page.getByText(/No tables match|tables · page/)).toBeVisible();
  await page.getByLabel('Unowned only').uncheck();
  await page.getByLabel('Search').fill('order');
  await expect(page.getByRole('link', { name: 'SALES.ORDER_ITEM' })).toBeVisible();
  await page.getByLabel('Search').fill('');
  await expect(page.getByText(/\d+ tables · page 1 of \d+/)).toBeVisible();
  const next = page.getByRole('button', { name: 'Next' });
  if (await next.isEnabled()) {
    await next.click();
    await expect(page.getByText(/page 2 of/)).toBeVisible();
    await page.getByRole('button', { name: 'Previous' }).click();
  }

  await page.getByRole('link', { name: 'SALES.ORDERS' }).click();
  await expect(heading(page)).toContainText('SALES.ORDERS');
  await expect(region(page, 'Consumers').locator('tbody tr').first()).toBeVisible();
  await expect(region(page, 'Database-side dependencies')).toContainText('TRG_ORDERS_AUDIT');

  // ownership: assign another team, then restore
  const owner = page.getByRole('combobox', { name: 'Owner team' });
  const other = teams.find((t) => t.id !== orders.ownerTeamId)!;
  await owner.selectOption(other.id);
  await page.getByRole('button', { name: 'Assign' }).click();
  await expectToast(page, 'Owner updated');
  expect((await getJson<Table>(request, `/tables/${orders.id}`)).ownerTeamId).toBe(other.id);
  await owner.selectOption(orders.ownerTeamId ?? '');
  await page.getByRole('button', { name: 'Assign' }).click();
  await expectToast(page, 'Owner updated');

  // producer: clear (explicit null) and set back
  const producer = page.getByRole('combobox', { name: 'Producer application' });
  await producer.selectOption('');
  await page.getByRole('button', { name: 'Set' }).click();
  await expectToast(page, 'Producer updated');
  expect((await getJson<Table>(request, `/tables/${orders.id}`)).producerApplicationId).toBeNull();
  await producer.selectOption(orders.producerApplicationId ?? '');
  await page.getByRole('button', { name: 'Set' }).click();
  await expectToast(page, 'Producer updated');
  expect((await getJson<Table>(request, `/tables/${orders.id}`)).producerApplicationId).toBe(orders.producerApplicationId);

  // columns: classify, then clear with null
  const colRow = region(page, 'Columns').locator('tbody tr').filter({ hasText: 'ORDER_ID' });
  await colRow.getByRole('button', { name: 'Edit column ORDER_ID' }).click();
  await dialog(page).getByLabel('Classification').selectOption('PII');
  await dialog(page).getByLabel('Comment').fill('e2e comment');
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await expectToast(page, 'Column saved');
  await expect(colRow).toContainText('PII');
  await expect(colRow).toContainText('e2e comment');
  await colRow.getByRole('button', { name: 'Edit column ORDER_ID' }).click();
  await dialog(page).getByLabel('Classification').selectOption('');
  await dialog(page).getByLabel('Comment').fill('');
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await expectToast(page, 'Column saved');
  await expect(colRow).not.toContainText('PII');

  // migration plan: change state and revert
  const mig = region(page, 'Migration');
  await mig.getByLabel('State').selectOption('PLANNED');
  await mig.getByRole('button', { name: 'Save plan' }).click();
  await expectToast(page, 'Migration plan saved');
  await mig.getByLabel('State').selectOption('IN_PROGRESS');
  await mig.getByRole('button', { name: 'Save plan' }).click();
  await expectToast(page, 'Migration plan saved');

  // metadata dialog round-trip
  await page.getByRole('button', { name: 'Edit metadata' }).click();
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await expectToast(page, 'Saved');

  await page.locator('.page-header').getByRole('link', { name: 'Impact analysis' }).click();
  await expect(heading(page)).toHaveText('Impact analysis');
  await expect(page.getByRole('meter', { name: /Risk score/ })).toBeVisible();
  await w.assertClean();
});

test('routines: filters and detail with callers, dependencies and referenced-by', async ({ page }, testInfo) => {
  const w = watch(page, testInfo);
  await page.goto('/routines');
  await expect(heading(page)).toHaveText('Routines');
  await page.getByLabel('Kind').selectOption('TRIGGER');
  await expect(page.getByRole('link', { name: 'SALES.TRG_ORDERS_AUDIT' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'SALES.ORDER_PKG.PLACE_ORDER' })).toHaveCount(0);
  await page.getByLabel('Kind').selectOption('');
  await page.getByLabel('Search').fill('place_order');
  await expect(page.getByRole('link', { name: 'SALES.ORDER_PKG.PLACE_ORDER' })).toBeVisible();
  await page.getByRole('link', { name: 'SALES.ORDER_PKG.PLACE_ORDER' }).click();
  await expect(heading(page)).toContainText('PLACE_ORDER');
  await expect(region(page, 'Callers').getByRole('link', { name: 'orders-service' })).toBeVisible();
  await expect(region(page, 'Dependencies').getByRole('link', { name: 'SALES.ORDERS' })).toBeVisible();
  await expect(region(page, 'Tables touched').getByRole('link', { name: 'SALES.ORDERS' })).toBeVisible();
  await page.goto('/routines?kind=TRIGGER');
  await page.getByRole('link', { name: 'SALES.TRG_ORDERS_AUDIT' }).click();
  await expect(region(page, 'Referenced by').getByRole('link', { name: 'SALES.ORDERS' })).toBeVisible();
  await expect(region(page, 'Dependencies').getByRole('link', { name: 'SALES.AUDIT_LOG' })).toBeVisible();
  await w.assertClean();
});

test('graph: root pickers, filters, expand, whole graph', async ({ page, request }, testInfo) => {
  const w = watch(page, testInfo);
  const orders = (await getJson<Table[]>(request, '/tables?schema=SALES&q=ORDERS')).find((t) => t.name === 'ORDERS')!;
  await page.goto(`/graph?root=table:${orders.id}&depth=1`);
  await expect(page.getByRole('application', { name: 'Dependency graph' })).toBeVisible();
  const counter = page.getByText(/\d+ nodes · \d+ edges/);
  await expect(counter).toBeVisible();
  const initial = await counter.textContent();
  // node type filter
  await page.getByRole('button', { name: /^Applications/ }).click();
  await expect(counter).not.toHaveText(initial!);
  await page.getByRole('button', { name: /^Applications/ }).click();
  await expect(counter).toHaveText(initial!);
  // edge kind filter
  await page.getByRole('button', { name: /^reads/ }).click();
  await expect(counter).not.toHaveText(initial!);
  await page.getByRole('button', { name: /^reads/ }).click();
  // select a node on the canvas through cytoscape's rendered positions, then expand it from the panel
  const pos = await page.evaluate((rootId) => {
    // cytoscape registers itself on its container element as `_cyreg`; no DOM typings in the node tsconfig
    type CyNode = { id: () => string; renderedPosition: () => { x: number; y: number } };
    type CyEl = { _cyreg?: { cy?: { nodes: () => { filter: (f: (n: CyNode) => boolean) => CyNode[] } } }; getBoundingClientRect: () => { left: number; top: number } };
    const g = globalThis as unknown as { document: { querySelectorAll: (sel: string) => ArrayLike<CyEl> } };
    const el = Array.from(g.document.querySelectorAll('[role="application"]')).find((d) => d._cyreg);
    const cy = el?._cyreg?.cy;
    if (!el || !cy) return null;
    const n = cy.nodes().filter((x) => x.id() !== rootId)[0];
    if (!n) return null;
    const r = el.getBoundingClientRect();
    const p = n.renderedPosition();
    return { x: r.left + p.x, y: r.top + p.y };
  }, `table:${orders.id}`);
  expect(pos).not.toBeNull();
  await page.mouse.click(pos!.x, pos!.y);
  await expect(page.getByRole('button', { name: 'Expand' })).toBeVisible();
  await page.getByRole('button', { name: 'Expand' }).click();
  await expectToast(page, /Expanded/);
  await page.getByRole('button', { name: 'Close details' }).click();
  // root picker
  const picker = page.getByRole('combobox', { name: 'Root' });
  await picker.fill('customer-portal');
  await page.getByRole('option', { name: /customer-portal/ }).first().click();
  await expect(page).toHaveURL(/root=application/);
  await expect(counter).toBeVisible();
  await page.getByLabel('Depth').selectOption('2');
  await expect(page).toHaveURL(/depth=2/);
  await expect(counter).toBeVisible();
  // whole graph
  await page.getByRole('button', { name: 'Whole graph' }).click();
  await expect(page).not.toHaveURL(/root=/);
  await expect(counter).toBeVisible();
  await w.assertClean();
});

test('impact: table and column', async ({ page, request }, testInfo) => {
  const w = watch(page, testInfo);
  const orders = (await getJson<Table[]>(request, '/tables?schema=SALES&q=ORDERS')).find((t) => t.name === 'ORDERS')!;
  await page.goto('/impact');
  await expect(page.getByText('Pick a table to analyse')).toBeVisible();
  await page.getByRole('combobox', { name: 'Table' }).fill('SALES.ORDERS');
  await page.getByRole('option', { name: /^SALES\.ORDERS\b/ }).first().click();
  await expect(page).toHaveURL(new RegExp(`table=${orders.id}`));
  await expect(page.getByRole('meter', { name: /Risk score/ })).toBeVisible();
  await expect(region(page, 'Consumers').getByRole('link', { name: 'orders-service' }).first()).toBeVisible();
  await expect(region(page, 'Teams affected').getByRole('link').first()).toBeVisible();
  await expect(region(page, 'Database-side logic')).toContainText('TRG_ORDERS_AUDIT');
  await expect(page.getByText(/\d+ nodes · \d+ edges/)).toBeVisible();
  await page.getByLabel('Column (optional)').selectOption({ label: 'ORDER_ID' });
  await expect(page).toHaveURL(/column=/);
  await expect(page.getByRole('heading', { level: 2, name: 'SALES.ORDERS.ORDER_ID' })).toBeVisible();
  await expect(page.getByRole('meter', { name: /Risk score/ })).toBeVisible();
  if (REAL_API) await expect(page.getByText('Queries referencing the column')).toBeVisible();
  await w.assertClean();
});

test('connections and queries', async ({ page }, testInfo) => {
  const w = watch(page, testInfo);
  await page.goto('/connections');
  await expect(heading(page)).toHaveText('Connections');
  await expect(page.getByText(/No live connections|Live connections/).first()).toBeVisible();
  await page.getByLabel('Source').selectOption('PROXY');
  await page.getByLabel('Group by').selectOption('team');
  await page.getByRole('button', { name: 'Refresh' }).click();

  await page.goto('/queries');
  await expect(heading(page)).toHaveText('Queries');
  await expect(page.locator('tbody tr').first()).toBeVisible();
  await page.getByRole('group', { name: 'Window' }).getByRole('button', { name: '7d' }).click();
  await page.getByRole('group', { name: 'Rank by' }).getByRole('button', { name: 'duration' }).click();
  await expect(page).toHaveURL(/by=duration/);
  await page.getByLabel('Application').selectOption({ label: 'orders-service' });
  await expect(page.locator('tbody tr').first()).toBeVisible();
  await page.locator('tbody tr').first().click();
  await expect(page.getByRole('button', { name: 'Copy SQL' })).toBeVisible();
  await w.assertClean();
});

test('governance: policies, violation lifecycle, evaluate', async ({ page, request }, testInfo) => {
  const w = watch(page, testInfo);
  await page.goto('/governance');
  await expect(heading(page)).toHaveText('Governance');
  const policies = await getJson<Array<{ id: string; kind: string; enabled: boolean; severity: string }>>(request, '/governance/policies');
  const pol = policies.find((p) => p.kind === 'CROSS_TEAM_DIRECT_ACCESS')!;
  const prow = region(page, 'Policies').locator('tbody tr').filter({ hasText: 'Cross-team direct access' });
  await prow.locator('label.switch').click();
  await expect.poll(async () => (await getJson<Array<{ id: string; enabled: boolean }>>(request, '/governance/policies')).find((p) => p.id === pol.id)!.enabled).toBe(!pol.enabled);
  await prow.locator('label.switch').click();
  await expect.poll(async () => (await getJson<Array<{ id: string; enabled: boolean }>>(request, '/governance/policies')).find((p) => p.id === pol.id)!.enabled).toBe(pol.enabled);
  await prow.getByRole('combobox').selectOption('HIGH');
  await expect.poll(async () => (await getJson<Array<{ id: string; severity: string }>>(request, '/governance/policies')).find((p) => p.id === pol.id)!.severity).toBe('HIGH');
  await prow.getByRole('combobox').selectOption(pol.severity);

  const violations = region(page, 'Violations');
  const first = violations.locator('tbody tr').first();
  await expect(first).toBeVisible();
  const label = (await first.locator('strong').first().textContent())!.trim();
  await first.getByRole('button', { name: 'Acknowledge' }).click();
  await page.getByRole('tab', { name: /Acknowledged/ }).click();
  const ack = violations.locator('tbody tr').filter({ hasText: label }).first();
  await expect(ack).toContainText('ACKNOWLEDGED');
  await ack.getByRole('button', { name: 'Resolve' }).click();
  await page.getByRole('tab', { name: /Resolved/ }).click();
  const res = violations.locator('tbody tr').filter({ hasText: label }).first();
  await expect(res).toContainText('RESOLVED');
  await res.getByRole('button', { name: 'Reopen' }).click();
  await page.getByRole('tab', { name: /^Open/ }).click();
  await expect(violations.locator('tbody tr').filter({ hasText: label }).first()).toContainText('OPEN');
  await page.getByRole('button', { name: 'Evaluate now' }).click();
  await expectToast(page, 'Evaluation started');
  await w.assertClean();
});

test('admin: credentials, components, export, import, seed', async ({ page, request }, testInfo) => {
  const w = watch(page, testInfo);
  for (const c of (await getJson<Named[]>(request, '/credentials')).filter((c) => c.name === 'e2e-inline')) await request.delete(`${API}/credentials/${c.id}`);
  await page.goto('/admin');
  await expect(heading(page)).toHaveText('Admin');
  const creds = region(page, 'Credentials');
  await creds.getByRole('button', { name: 'Add credential' }).click();
  await dialog(page).getByLabel('Name', { exact: true }).fill('e2e-inline');
  await dialog(page).getByLabel('Username').fill('e2e_user');
  await dialog(page).getByLabel('Provider').selectOption('INLINE');
  await dialog(page).getByLabel('Secret', { exact: true }).fill('s3cret');
  await dialog(page).getByLabel('Description').fill('created by e2e');
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await expectToast(page, 'Credential created');
  const crow = creds.locator('tbody tr').filter({ hasText: 'e2e-inline' });
  await expect(crow).toBeVisible();
  await expect(crow).toContainText('INLINE');
  await crow.getByRole('button', { name: 'Rotate' }).click();
  await dialog(page).getByLabel('New secret').fill('n3w');
  await dialog(page).getByRole('button', { name: 'Rotate' }).click();
  await expectToast(page, /rotated/);
  const cred = await byName<Named & { version: number }>(request, '/credentials', 'e2e-inline');
  expect(cred.version).toBe(2);
  await crow.getByRole('button', { name: 'Edit' }).click();
  await dialog(page).getByLabel('Description').fill('edited by e2e');
  await dialog(page).getByRole('button', { name: 'Save' }).click();
  await expectToast(page, 'Credential saved');
  await expect(crow).toContainText('edited by e2e');
  await crow.getByRole('button', { name: 'Delete credential' }).click();
  await dialog(page).getByRole('button', { name: 'Delete' }).click();
  await expectToast(page, 'Credential deleted');
  await expect(crow).toHaveCount(0);

  const components = await getJson<unknown[]>(request, '/components');
  if (components.length === 0) await expect(region(page, 'Components')).toContainText('No component has sent a heartbeat');

  const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Export JSON' }).click()]);
  expect(download.suggestedFilename()).toMatch(/^dbp-export-\d{4}-\d{2}-\d{2}\.json$/);
  const doc = JSON.parse(readFileSync((await download.path())!, 'utf8')) as { teams: unknown[]; datasources: unknown[]; credentials: Array<Record<string, unknown>> };
  expect(doc.teams.length).toBeGreaterThan(0);
  expect(doc.datasources.length).toBeGreaterThan(0);
  expect(doc.credentials.every((c) => !('secret' in c) && !('encryptedSecret' in c))).toBe(true);
  await expectToast(page, 'Export downloaded');

  await page.locator('input[type=file]').setInputFiles({ name: 'dbp-export.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(doc)) });
  await expect(page.getByText('Ready to import.')).toBeVisible();
  await page.getByRole('button', { name: 'Import now' }).click();
  await expect(page.getByText('Imported.')).toBeVisible({ timeout: 30_000 });
  await expectToast(page, 'Import finished');

  await page.getByRole('button', { name: 'Seed demo dataset' }).click();
  await dialog(page).getByRole('button', { name: 'Seed' }).click();
  await expectToast(page, 'Demo dataset loaded');
  await w.assertClean();
});

test('deep links and not-found route', async ({ page, request }, testInfo) => {
  const w = watch(page, testInfo, { allowStatus: (url, status) => status === 404 && url.includes(`${API}/tables/does-not-exist`) });
  const orders = (await getJson<Table[]>(request, '/tables?schema=SALES&q=ORDERS')).find((t) => t.name === 'ORDERS')!;
  await page.goto(`/tables/${orders.id}`);
  await expect(heading(page)).toContainText('SALES.ORDERS');
  await page.goto('/tables/does-not-exist');
  await expect(page.getByRole('alert')).toContainText(/not found/i);
  await page.goto('/no-such-page');
  await expect(page.getByText(/not found/i).first()).toBeVisible();
  await w.assertClean();
});
