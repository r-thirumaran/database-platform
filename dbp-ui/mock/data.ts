/**
 * In-memory retail demo dataset for `npm run mock`.
 *
 * Every object is typed against src/api/types.ts, so this file doubles as documentation of the JSON
 * shapes the control plane returns. Ids are readable slugs here; the real control plane uses UUIDs
 * and the UI treats ids as opaque strings. Timestamps are relative to process start so relative
 * times in the UI look alive.
 */
import type {
  AccessGrant,
  ApiKey,
  Application,
  CollectorStatus,
  Column,
  ComponentInfo,
  Credential,
  Database,
  Datasource,
  Dependency,
  LiveConnection,
  MigrationEvent,
  Policy,
  PoolStats,
  QueryStat,
  Relationship,
  Routine,
  Table,
  Team,
  Violation,
} from '../src/api/types';

export interface DemoStore {
  configVersion: number;
  teams: Team[];
  applications: Application[];
  apiKeys: Record<string, ApiKey[]>;
  databases: Database[];
  credentials: Credential[];
  datasources: Datasource[];
  accessGrants: AccessGrant[];
  tables: Table[];
  columns: Column[];
  routines: Routine[];
  dependencies: Dependency[];
  relationships: Relationship[];
  queryStats: QueryStat[];
  poolStats: PoolStats[];
  liveConnections: LiveConnection[];
  components: ComponentInfo[];
  policies: Policy[];
  violations: Violation[];
  migrationEvents: MigrationEvent[];
  collectorStatus: Record<string, CollectorStatus>;
}

const NOW = Date.now();
const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
export const ago = (ms: number): string => new Date(NOW - ms).toISOString();
const created = (days: number) => ({ createdAt: ago(days * DAY), updatedAt: ago(Math.min(days, 3) * DAY) });

// ------------------------------------------------------------------ ids
export const ID = {
  team: {
    sales: 'team-sales-platform',
    inventory: 'team-inventory',
    finance: 'team-finance',
    analytics: 'team-analytics',
    cx: 'team-customer-experience',
  },
  app: {
    orders: 'app-orders-service',
    inventory: 'app-inventory-service',
    payment: 'app-payment-service',
    reporting: 'app-reporting-batch',
    portal: 'app-customer-portal',
    billing: 'app-legacy-billing',
  },
  db: { oracle: 'db-sales-oracle', postgres: 'db-sales-postgres' },
  cred: { oracle: 'cred-sales-oracle-platform', postgres: 'cred-sales-postgres-platform', reader: 'cred-oracle-readonly' },
  ds: { sales: 'ds-sales', inventory: 'ds-inventory', payments: 'ds-payments' },
  tbl: {
    vCustomerOrders: 'tbl-sales-v-customer-orders',
    customer: 'tbl-sales-customer',
    product: 'tbl-sales-product',
    orders: 'tbl-sales-orders',
    orderItem: 'tbl-sales-order-item',
    inventory: 'tbl-sales-inventory',
    payment: 'tbl-sales-payment',
    auditLog: 'tbl-sales-audit-log',
    tmpExport: 'tbl-sales-tmp-export',
    productFeed: 'tbl-staging-product-feed',
    priceImport: 'tbl-staging-price-import',
    pgCustomer: 'tbl-pg-sales-customer',
    pgOrders: 'tbl-pg-sales-orders',
    pgOrderItem: 'tbl-pg-sales-order-item',
  },
  rtn: {
    orderPkg: 'rtn-order-pkg',
    placeOrder: 'rtn-order-pkg-place-order',
    calcTotal: 'rtn-order-pkg-calc-total',
    reserveStock: 'rtn-reserve-stock',
    customerTier: 'rtn-get-customer-tier',
    trgOrdersAudit: 'rtn-trg-orders-audit',
    trgOrderItemStock: 'rtn-trg-order-item-stock',
  },
} as const;

// ------------------------------------------------------------------ teams
const teams: Team[] = [
  { id: ID.team.sales, name: 'sales-platform', displayName: 'Sales Platform', description: 'Order capture and fulfilment APIs.', contacts: ['sales-platform@example.org'], tags: ['domain:sales'], ...created(400) },
  { id: ID.team.inventory, name: 'inventory', displayName: 'Inventory', description: 'Stock levels, warehouses and product master data.', contacts: ['inventory@example.org'], tags: ['domain:supply-chain'], ...created(380) },
  { id: ID.team.finance, name: 'finance', displayName: 'Finance', description: 'Payments, invoicing and the legacy billing system.', contacts: ['finance-eng@example.org'], tags: ['domain:finance'], ...created(500) },
  { id: ID.team.analytics, name: 'analytics', displayName: 'Analytics', description: 'Reporting and nightly aggregation jobs.', contacts: ['analytics@example.org'], tags: ['domain:data'], ...created(200) },
  { id: ID.team.cx, name: 'customer-experience', displayName: 'Customer Experience', description: 'Customer-facing web portal and customer profile.', contacts: ['cx@example.org'], tags: ['domain:customer'], ...created(300) },
];

// ------------------------------------------------------------------ applications
const rules = (p: Partial<Application['identityRules']>): Application['identityRules'] => ({
  cidrs: [], programNames: [], machinePatterns: [], serviceAliases: [], pgApplicationNames: [], ...p,
});
const applications: Application[] = [
  { id: ID.app.orders, name: 'orders-service', displayName: 'Orders Service', teamId: ID.team.sales, kind: 'SERVICE', runtime: 'KUBERNETES',
    description: 'Places and tracks customer orders through the gateway (jdbc:dbp://…/sales).',
    identityRules: rules({ cidrs: ['10.20.0.0/16'], programNames: ['orders-service', 'JDBC Thin Client/orders'], machinePatterns: ['orders-service-*'], serviceAliases: ['orders-service'], pgApplicationNames: ['orders-service'] }),
    tags: ['tier:1', 'pilot'], ...created(390) },
  { id: ID.app.inventory, name: 'inventory-service', displayName: 'Inventory Service', teamId: ID.team.inventory, kind: 'SERVICE', runtime: 'KUBERNETES',
    description: 'Stock reservations and product master updates. Already migrated to PostgreSQL for the sales datasource.',
    identityRules: rules({ cidrs: ['10.21.0.0/16'], programNames: ['inventory-service'], machinePatterns: ['inventory-*'], serviceAliases: ['inventory-service'], pgApplicationNames: ['inventory-service'] }),
    tags: ['tier:1'], ...created(370) },
  { id: ID.app.payment, name: 'payment-service', displayName: 'Payment Service', teamId: ID.team.finance, kind: 'SERVICE', runtime: 'CLOUD_RUN',
    description: 'Captures payments and refunds.',
    identityRules: rules({ cidrs: ['10.22.0.0/16'], programNames: ['payment-service'], serviceAliases: ['payment-service'], pgApplicationNames: ['payment-service'] }),
    tags: ['tier:1', 'pci'], ...created(360) },
  { id: ID.app.reporting, name: 'reporting-batch', displayName: 'Reporting Batch', teamId: ID.team.analytics, kind: 'BATCH', runtime: 'VM',
    description: 'Nightly aggregation of orders and payments into the warehouse.',
    identityRules: rules({ cidrs: ['10.40.1.0/24'], programNames: ['reporting-batch', 'sqlplus'], machinePatterns: ['batch-*'], serviceAliases: ['reporting-batch'] }),
    tags: ['tier:3'], ...created(200) },
  { id: ID.app.portal, name: 'customer-portal', displayName: 'Customer Portal', teamId: ID.team.cx, kind: 'UI', runtime: 'KUBERNETES',
    description: 'Public web portal: customer profile, order history.',
    identityRules: rules({ cidrs: ['10.23.0.0/16'], programNames: ['customer-portal'], machinePatterns: ['portal-*'], serviceAliases: ['customer-portal'], pgApplicationNames: ['customer-portal'] }),
    tags: ['tier:1', 'public'], ...created(300) },
  { id: ID.app.billing, name: 'legacy-billing', displayName: 'Legacy Billing', teamId: ID.team.finance, kind: 'LEGACY', runtime: 'VM',
    description: 'Monolith connecting through the transparent proxy with a plain Oracle driver. No code changes possible.',
    identityRules: rules({ cidrs: ['10.50.0.0/24'], programNames: ['billing.exe', 'JDBC Thin Client'], machinePatterns: ['billing-vm-*'], serviceAliases: ['legacy-billing'] }),
    tags: ['tier:2', 'legacy'], ...created(500) },
];

const apiKeys: Record<string, ApiKey[]> = {
  [ID.app.orders]: [
    { id: 'key-orders-prod', prefix: 'k7f3a1', label: 'prod', createdAt: ago(120 * DAY), lastUsedAt: ago(2 * MIN), revokedAt: null },
    { id: 'key-orders-staging', prefix: 'c91e0d', label: 'staging', createdAt: ago(90 * DAY), lastUsedAt: ago(3 * HOUR), revokedAt: null },
    { id: 'key-orders-old', prefix: '4b2a77', label: 'prod (2025)', createdAt: ago(400 * DAY), lastUsedAt: ago(130 * DAY), revokedAt: ago(119 * DAY) },
  ],
  [ID.app.inventory]: [{ id: 'key-inventory-prod', prefix: '9ad11c', label: 'prod', createdAt: ago(100 * DAY), lastUsedAt: ago(40_000), revokedAt: null }],
  [ID.app.payment]: [{ id: 'key-payment-prod', prefix: 'e0c5f2', label: 'prod', createdAt: ago(100 * DAY), lastUsedAt: ago(5 * MIN), revokedAt: null }],
  [ID.app.reporting]: [{ id: 'key-reporting-prod', prefix: '77b0de', label: 'nightly', createdAt: ago(60 * DAY), lastUsedAt: ago(9 * HOUR), revokedAt: null }],
  [ID.app.portal]: [{ id: 'key-portal-prod', prefix: '3f9c21', label: 'prod', createdAt: ago(80 * DAY), lastUsedAt: ago(30_000), revokedAt: null }],
  [ID.app.billing]: [],
};

// ------------------------------------------------------------------ credentials & databases
const credentials: Credential[] = [
  { id: ID.cred.oracle, name: 'sales-oracle-platform', username: 'DBP_PLATFORM', provider: 'INLINE', ref: null, rotatedAt: ago(20 * DAY), version: 3, description: 'Pool and collector account on the Oracle database.', ...created(400) },
  { id: ID.cred.postgres, name: 'sales-postgres-platform', username: 'dbp_platform', provider: 'ENV', ref: 'SALES_PG_PASSWORD', rotatedAt: ago(5 * DAY), version: 2, description: 'Pool and collector account on PostgreSQL.', ...created(90) },
  { id: ID.cred.reader, name: 'oracle-readonly-collector', username: 'DBP_READER', provider: 'FILE', ref: '/var/run/secrets/dbp/oracle-reader', rotatedAt: null, version: 1, description: 'Read-only dictionary crawler account.', ...created(200) },
];

const databases: Database[] = [
  { id: ID.db.oracle, name: 'sales-oracle', engine: 'ORACLE', host: 'oracle.demo.internal', port: 1521, serviceName: 'FREEPDB1', credentialId: ID.cred.oracle,
    maxPhysicalConnections: 60, jdbcProperties: { 'oracle.jdbc.ReadTimeout': '60000', 'oracle.net.CONNECT_TIMEOUT': '5000' },
    collector: { enabled: true, dictionaryIntervalSeconds: 3600, runtimeIntervalSeconds: 15, schemas: ['SALES', 'STAGING'], auditTrail: true },
    description: 'System of record for the retail domain. Being migrated to PostgreSQL schema by schema.', tags: ['prod', 'legacy'], ...created(400) },
  { id: ID.db.postgres, name: 'sales-postgres', engine: 'POSTGRES', host: 'postgres.demo.internal', port: 5432, serviceName: 'sales', credentialId: ID.cred.postgres,
    maxPhysicalConnections: 80, jdbcProperties: { ApplicationName: 'dbp-gateway', connectTimeout: '5' },
    collector: { enabled: true, dictionaryIntervalSeconds: 1800, runtimeIntervalSeconds: 15, schemas: ['sales'], auditTrail: false },
    description: 'Migration target for the sales datasource.', tags: ['prod', 'target'], ...created(90) },
];

// ------------------------------------------------------------------ datasources & grants
const datasources: Datasource[] = [
  { id: ID.ds.sales, name: 'sales', displayName: 'Sales domain data', ownerTeamId: ID.team.sales, state: 'MIGRATING',
    currentDatabaseId: ID.db.oracle, targetDatabaseId: ID.db.postgres,
    poolPolicy: { mode: 'TRANSACTION', maxConnections: 40, minIdle: 2, connectionTimeoutMs: 10000, idleTimeoutMs: 600000, maxLifetimeMs: 1800000, statementTimeoutSeconds: 0, validationQuery: null },
    routingRules: [
      { id: 'rule-sales-inventory-pg', priority: 10, applicationId: ID.app.inventory, tag: null, databaseId: ID.db.postgres, readOnly: false, enabled: true },
      { id: 'rule-sales-pilot-pg', priority: 20, applicationId: null, tag: 'pilot', databaseId: ID.db.postgres, readOnly: true, enabled: false },
    ],
    description: 'Customers, orders, order items. Migrating Oracle → PostgreSQL application by application.', tags: ['domain:sales'], ...created(380) },
  { id: ID.ds.inventory, name: 'inventory', displayName: 'Inventory and product master', ownerTeamId: ID.team.inventory, state: 'ACTIVE',
    currentDatabaseId: ID.db.oracle, targetDatabaseId: null,
    poolPolicy: { mode: 'TRANSACTION', maxConnections: 20, minIdle: 1, connectionTimeoutMs: 10000, idleTimeoutMs: 600000, maxLifetimeMs: 1800000, statementTimeoutSeconds: 30, validationQuery: null },
    routingRules: [], description: 'Stock levels and product catalogue.', tags: ['domain:supply-chain'], ...created(370) },
  { id: ID.ds.payments, name: 'payments', displayName: 'Payments', ownerTeamId: ID.team.finance, state: 'ACTIVE',
    currentDatabaseId: ID.db.oracle, targetDatabaseId: null,
    poolPolicy: { mode: 'SESSION', maxConnections: 25, minIdle: 2, connectionTimeoutMs: 5000, idleTimeoutMs: 300000, maxLifetimeMs: 1800000, statementTimeoutSeconds: 60, validationQuery: 'SELECT 1 FROM DUAL' },
    routingRules: [], description: 'Payment capture; SESSION pooling because legacy-billing relies on session state.', tags: ['domain:finance', 'pci'], ...created(360) },
];

const grant = (id: string, applicationId: string, datasourceId: string, p: Partial<AccessGrant> = {}): AccessGrant => ({
  id, applicationId, datasourceId, maxLogicalConnections: 50, maxProxyConnections: 20, poolModeOverride: null, readOnly: false, enabled: true, note: null, ...created(200), ...p,
});
const accessGrants: AccessGrant[] = [
  grant('grant-orders-sales', ID.app.orders, ID.ds.sales, { maxLogicalConnections: 120, maxProxyConnections: 0 }),
  grant('grant-inventory-sales', ID.app.inventory, ID.ds.sales, { readOnly: true, maxLogicalConnections: 30 }),
  grant('grant-inventory-inventory', ID.app.inventory, ID.ds.inventory, { maxLogicalConnections: 60 }),
  grant('grant-payment-payments', ID.app.payment, ID.ds.payments, { maxLogicalConnections: 40 }),
  grant('grant-payment-sales', ID.app.payment, ID.ds.sales, { readOnly: true, maxLogicalConnections: 10 }),
  grant('grant-reporting-sales', ID.app.reporting, ID.ds.sales, { readOnly: true, maxLogicalConnections: 8, note: 'Nightly window 01:00–04:00' }),
  grant('grant-reporting-inventory', ID.app.reporting, ID.ds.inventory, { readOnly: true, maxLogicalConnections: 4 }),
  grant('grant-portal-sales', ID.app.portal, ID.ds.sales, { readOnly: true, maxLogicalConnections: 80 }),
  grant('grant-billing-payments', ID.app.billing, ID.ds.payments, { maxLogicalConnections: 0, maxProxyConnections: 25, poolModeOverride: 'SESSION', note: 'Proxy only (plain Oracle driver)' }),
];

// ------------------------------------------------------------------ tables & columns
const table = (id: string, databaseId: string, schema: string, name: string, p: Partial<Table> = {}): Table => ({
  id, databaseId, schema, name, kind: 'TABLE', ownerTeamId: null, ownerConfirmed: false, ownerSource: 'NONE', producerApplicationId: null,
  rowCountEstimate: null, lastDdlAt: ago(60 * DAY), lastSeenAt: ago(10 * MIN), firstSeenAt: ago(380 * DAY),
  migration: { targetDatabaseId: null, targetSchema: null, targetName: null, state: 'NOT_PLANNED' }, description: null, tags: [], classification: null, ...p,
});
const tables: Table[] = [
  table(ID.tbl.customer, ID.db.oracle, 'SALES', 'CUSTOMER', { ownerTeamId: ID.team.cx, ownerConfirmed: true, ownerSource: 'DECLARED', producerApplicationId: ID.app.portal, rowCountEstimate: 1_240_000, classification: 'PII', description: 'Customer master: identity, contact details, loyalty tier.', tags: ['gdpr'],
    migration: { targetDatabaseId: ID.db.postgres, targetSchema: 'sales', targetName: 'customer', state: 'DONE' } }),
  table(ID.tbl.product, ID.db.oracle, 'SALES', 'PRODUCT', { ownerTeamId: ID.team.inventory, ownerConfirmed: true, ownerSource: 'DECLARED', producerApplicationId: ID.app.inventory, rowCountEstimate: 48_200, classification: 'INTERNAL', description: 'Product catalogue (SKU, price, category).' }),
  table(ID.tbl.orders, ID.db.oracle, 'SALES', 'ORDERS', { ownerTeamId: ID.team.sales, ownerConfirmed: true, ownerSource: 'DECLARED', producerApplicationId: ID.app.orders, rowCountEstimate: 9_800_000, classification: 'CONFIDENTIAL', description: 'Order header. One row per customer order.', tags: ['hot'],
    migration: { targetDatabaseId: ID.db.postgres, targetSchema: 'sales', targetName: 'orders', state: 'IN_PROGRESS' }, lastSeenAt: ago(20_000) }),
  table(ID.tbl.orderItem, ID.db.oracle, 'SALES', 'ORDER_ITEM', { ownerTeamId: ID.team.sales, ownerConfirmed: true, ownerSource: 'DECLARED', producerApplicationId: ID.app.orders, rowCountEstimate: 31_500_000, classification: 'INTERNAL', description: 'Order lines.',
    migration: { targetDatabaseId: ID.db.postgres, targetSchema: 'sales', targetName: 'order_item', state: 'IN_PROGRESS' }, lastSeenAt: ago(25_000) }),
  table(ID.tbl.inventory, ID.db.oracle, 'SALES', 'INVENTORY', { ownerTeamId: ID.team.inventory, ownerConfirmed: true, ownerSource: 'DECLARED', producerApplicationId: ID.app.inventory, rowCountEstimate: 96_400, classification: 'INTERNAL', description: 'Stock level per product and warehouse.' }),
  table(ID.tbl.payment, ID.db.oracle, 'SALES', 'PAYMENT', { ownerTeamId: ID.team.finance, ownerConfirmed: false, ownerSource: 'INFERRED', producerApplicationId: ID.app.payment, rowCountEstimate: 8_900_000, classification: 'CONFIDENTIAL', description: 'Payment transactions (tokenised card references).', tags: ['pci'] }),
  table(ID.tbl.auditLog, ID.db.oracle, 'SALES', 'AUDIT_LOG', { rowCountEstimate: 120_000_000, classification: null, description: 'Written by triggers; nobody has claimed it.', lastSeenAt: ago(30_000) }),
  table(ID.tbl.tmpExport, ID.db.oracle, 'SALES', 'TMP_EXPORT_2026Q3', { rowCountEstimate: 12_000, discovered: true, firstSeenAt: ago(3 * DAY), lastDdlAt: ago(3 * DAY), lastSeenAt: ago(3 * DAY), description: null }),
  table(ID.tbl.productFeed, ID.db.oracle, 'STAGING', 'PRODUCT_FEED', { rowCountEstimate: 500_000, lastSeenAt: ago(40 * DAY), description: 'Supplier feed landing table.' }),
  table(ID.tbl.priceImport, ID.db.oracle, 'STAGING', 'PRICE_IMPORT', { rowCountEstimate: 2_000, lastSeenAt: ago(70 * DAY) }),
  table(ID.tbl.pgCustomer, ID.db.postgres, 'sales', 'customer', { ownerTeamId: ID.team.cx, ownerConfirmed: true, ownerSource: 'DECLARED', producerApplicationId: ID.app.portal, rowCountEstimate: 1_240_000, classification: 'PII', description: 'Migrated copy of SALES.CUSTOMER.', firstSeenAt: ago(60 * DAY), lastSeenAt: ago(2 * MIN) }),
  table(ID.tbl.pgOrders, ID.db.postgres, 'sales', 'orders', { ownerTeamId: ID.team.sales, ownerConfirmed: true, ownerSource: 'DECLARED', producerApplicationId: ID.app.orders, rowCountEstimate: 4_100_000, classification: 'CONFIDENTIAL', description: 'Migration target of SALES.ORDERS (dual-write in progress).', firstSeenAt: ago(30 * DAY), lastSeenAt: ago(MIN) }),
  table(ID.tbl.pgOrderItem, ID.db.postgres, 'sales', 'order_item', { ownerTeamId: ID.team.sales, ownerConfirmed: true, ownerSource: 'DECLARED', producerApplicationId: ID.app.orders, rowCountEstimate: 13_000_000, classification: 'INTERNAL', firstSeenAt: ago(30 * DAY), lastSeenAt: ago(MIN) }),
];

let colSeq = 0;
const col = (tableId: string, name: string, dataType: string, p: Partial<Column> = {}): Column => ({
  id: `col-${tableId.replace(/^tbl-/, '')}-${name.toLowerCase()}`, tableId, name, position: ++colSeq, dataType, length: null, precision: null, scale: null, nullable: true, defaultValue: null, comment: null, classification: null, ...p,
});
const columnsFor = (tableId: string, defs: Array<[string, string, Partial<Column>?]>): Column[] => {
  colSeq = 0;
  return defs.map(([n, t, p]) => col(tableId, n, t, p));
};
const columns: Column[] = [
  ...columnsFor(ID.tbl.customer, [
    ['CUSTOMER_ID', 'NUMBER', { precision: 12, scale: 0, nullable: false, comment: 'Surrogate key' }],
    ['EMAIL', 'VARCHAR2', { length: 320, nullable: false, classification: 'PII' }],
    ['FIRST_NAME', 'VARCHAR2', { length: 100, classification: 'PII' }],
    ['LAST_NAME', 'VARCHAR2', { length: 100, classification: 'PII' }],
    ['PHONE', 'VARCHAR2', { length: 32, classification: 'PII' }],
    ['LOYALTY_TIER', 'VARCHAR2', { length: 16, defaultValue: "'BRONZE'", comment: 'BRONZE/SILVER/GOLD, recomputed by GET_CUSTOMER_TIER' }],
    ['CREATED_AT', 'TIMESTAMP(6)', { nullable: false, defaultValue: 'SYSTIMESTAMP' }],
  ]),
  ...columnsFor(ID.tbl.product, [
    ['PRODUCT_ID', 'NUMBER', { precision: 12, scale: 0, nullable: false }],
    ['SKU', 'VARCHAR2', { length: 64, nullable: false }],
    ['NAME', 'VARCHAR2', { length: 255, nullable: false }],
    ['CATEGORY', 'VARCHAR2', { length: 64 }],
    ['UNIT_PRICE', 'NUMBER', { precision: 12, scale: 2, nullable: false }],
    ['ACTIVE', 'CHAR', { length: 1, defaultValue: "'Y'" }],
  ]),
  ...columnsFor(ID.tbl.orders, [
    ['ORDER_ID', 'NUMBER', { precision: 14, scale: 0, nullable: false }],
    ['CUSTOMER_ID', 'NUMBER', { precision: 12, scale: 0, nullable: false, comment: 'FK → CUSTOMER' }],
    ['STATUS', 'VARCHAR2', { length: 20, nullable: false, defaultValue: "'NEW'" }],
    ['TOTAL_AMOUNT', 'NUMBER', { precision: 14, scale: 2, comment: 'Set by ORDER_PKG.CALC_TOTAL' }],
    ['CURRENCY', 'CHAR', { length: 3, defaultValue: "'EUR'" }],
    ['PLACED_AT', 'TIMESTAMP(6)', { nullable: false }],
    ['UPDATED_AT', 'TIMESTAMP(6)' ],
  ]),
  ...columnsFor(ID.tbl.orderItem, [
    ['ORDER_ITEM_ID', 'NUMBER', { precision: 16, scale: 0, nullable: false }],
    ['ORDER_ID', 'NUMBER', { precision: 14, scale: 0, nullable: false, comment: 'FK → ORDERS' }],
    ['PRODUCT_ID', 'NUMBER', { precision: 12, scale: 0, nullable: false, comment: 'FK → PRODUCT' }],
    ['QUANTITY', 'NUMBER', { precision: 8, scale: 0, nullable: false }],
    ['UNIT_PRICE', 'NUMBER', { precision: 12, scale: 2, nullable: false }],
  ]),
  ...columnsFor(ID.tbl.inventory, [
    ['PRODUCT_ID', 'NUMBER', { precision: 12, scale: 0, nullable: false }],
    ['WAREHOUSE_CODE', 'VARCHAR2', { length: 8, nullable: false }],
    ['ON_HAND', 'NUMBER', { precision: 10, scale: 0, nullable: false, defaultValue: '0' }],
    ['RESERVED', 'NUMBER', { precision: 10, scale: 0, nullable: false, defaultValue: '0' }],
    ['UPDATED_AT', 'TIMESTAMP(6)'],
  ]),
  ...columnsFor(ID.tbl.payment, [
    ['PAYMENT_ID', 'NUMBER', { precision: 14, scale: 0, nullable: false }],
    ['ORDER_ID', 'NUMBER', { precision: 14, scale: 0, nullable: false, comment: 'FK → ORDERS' }],
    ['AMOUNT', 'NUMBER', { precision: 14, scale: 2, nullable: false }],
    ['METHOD', 'VARCHAR2', { length: 16 }],
    ['CARD_TOKEN', 'VARCHAR2', { length: 64, classification: 'CONFIDENTIAL' }],
    ['STATUS', 'VARCHAR2', { length: 16, nullable: false }],
    ['CAPTURED_AT', 'TIMESTAMP(6)'],
  ]),
  ...columnsFor(ID.tbl.auditLog, [
    ['AUDIT_ID', 'NUMBER', { precision: 18, scale: 0, nullable: false }],
    ['TABLE_NAME', 'VARCHAR2', { length: 128 }],
    ['ROW_PK', 'VARCHAR2', { length: 128 }],
    ['ACTION', 'VARCHAR2', { length: 16 }],
    ['CHANGED_BY', 'VARCHAR2', { length: 128 }],
    ['CHANGED_AT', 'TIMESTAMP(6)', { defaultValue: 'SYSTIMESTAMP' }],
    ['PAYLOAD', 'CLOB'],
  ]),
  ...columnsFor(ID.tbl.tmpExport, [['ID', 'NUMBER'], ['DATA', 'VARCHAR2', { length: 4000 }]]),
  ...columnsFor(ID.tbl.productFeed, [['SUPPLIER_SKU', 'VARCHAR2', { length: 64 }], ['RAW_LINE', 'VARCHAR2', { length: 4000 }], ['LOADED_AT', 'DATE']]),
  ...columnsFor(ID.tbl.priceImport, [['SKU', 'VARCHAR2', { length: 64 }], ['NEW_PRICE', 'NUMBER', { precision: 12, scale: 2 }]]),
  ...columnsFor(ID.tbl.pgCustomer, [
    ['customer_id', 'bigint', { nullable: false }], ['email', 'text', { nullable: false, classification: 'PII' }], ['first_name', 'text', { classification: 'PII' }], ['last_name', 'text', { classification: 'PII' }], ['phone', 'text', { classification: 'PII' }], ['loyalty_tier', 'text', { defaultValue: "'BRONZE'" }], ['created_at', 'timestamptz', { nullable: false, defaultValue: 'now()' }],
  ]),
  ...columnsFor(ID.tbl.pgOrders, [
    ['order_id', 'bigint', { nullable: false }], ['customer_id', 'bigint', { nullable: false }], ['status', 'text', { nullable: false }], ['total_amount', 'numeric', { precision: 14, scale: 2 }], ['currency', 'char', { length: 3 }], ['placed_at', 'timestamptz', { nullable: false }], ['updated_at', 'timestamptz'],
  ]),
  ...columnsFor(ID.tbl.pgOrderItem, [
    ['order_item_id', 'bigint', { nullable: false }], ['order_id', 'bigint', { nullable: false }], ['product_id', 'bigint', { nullable: false }], ['quantity', 'integer', { nullable: false }], ['unit_price', 'numeric', { precision: 12, scale: 2, nullable: false }],
  ]),
];

// ------------------------------------------------------------------ routines
const routine = (id: string, name: string, kind: Routine['kind'], p: Partial<Routine> = {}): Routine => ({
  id, databaseId: ID.db.oracle, schema: 'SALES', name, kind, triggerTableId: null, triggerEvent: null, ownerTeamId: ID.team.sales, status: 'VALID', lastDdlAt: ago(45 * DAY), lastSeenAt: ago(5 * MIN), ...p,
});
const routines: Routine[] = [
  routine(ID.rtn.orderPkg, 'ORDER_PKG', 'PACKAGE'),
  routine(ID.rtn.placeOrder, 'ORDER_PKG.PLACE_ORDER', 'PROCEDURE', { lastSeenAt: ago(15_000) }),
  routine(ID.rtn.calcTotal, 'ORDER_PKG.CALC_TOTAL', 'FUNCTION', { lastSeenAt: ago(15_000) }),
  routine(ID.rtn.reserveStock, 'RESERVE_STOCK', 'PROCEDURE', { ownerTeamId: ID.team.inventory }),
  routine(ID.rtn.customerTier, 'GET_CUSTOMER_TIER', 'FUNCTION', { ownerTeamId: ID.team.cx, lastSeenAt: ago(9 * HOUR) }),
  routine(ID.rtn.trgOrdersAudit, 'TRG_ORDERS_AUDIT', 'TRIGGER', { triggerTableId: ID.tbl.orders, triggerEvent: 'AFTER INSERT OR UPDATE', ownerTeamId: null }),
  routine(ID.rtn.trgOrderItemStock, 'TRG_ORDER_ITEM_STOCK', 'TRIGGER', { triggerTableId: ID.tbl.orderItem, triggerEvent: 'AFTER INSERT', ownerTeamId: ID.team.inventory }),
];

// Views are catalogued as tables of kind VIEW; they reference their base tables through REFERENCES dependencies.
tables.push(table(ID.tbl.vCustomerOrders, ID.db.oracle, 'SALES', 'V_CUSTOMER_ORDERS', {
  kind: 'VIEW', ownerTeamId: ID.team.cx, ownerConfirmed: true, ownerSource: 'DECLARED', description: 'Customer orders joined with the customer master.', lastSeenAt: ago(40_000),
}));

// ------------------------------------------------------------------ dependencies (object → object)
let depSeq = 0;
const dep = (fromType: Dependency['fromType'], fromId: string, toType: Dependency['toType'], toId: string, kind: Dependency['kind'], p: Partial<Dependency> = {}): Dependency => ({
  id: `dep-${String(++depSeq).padStart(3, '0')}`, fromType, fromId, toType, toId, kind, source: 'DICTIONARY', confidence: kind === 'REFERENCES' || kind === 'FOREIGN_KEY' || kind === 'TRIGGERS' ? 1.0 : 0.8,
  firstSeenAt: ago(300 * DAY), lastSeenAt: ago(HOUR), ...p,
});
const dependencies: Dependency[] = [
  dep('ROUTINE', ID.rtn.placeOrder, 'TABLE', ID.tbl.orders, 'WRITES'),
  dep('ROUTINE', ID.rtn.placeOrder, 'TABLE', ID.tbl.orderItem, 'WRITES'),
  dep('ROUTINE', ID.rtn.placeOrder, 'TABLE', ID.tbl.customer, 'READS'),
  dep('ROUTINE', ID.rtn.placeOrder, 'TABLE', ID.tbl.product, 'READS'),
  dep('ROUTINE', ID.rtn.placeOrder, 'ROUTINE', ID.rtn.calcTotal, 'CALLS', { confidence: 1.0 }),
  dep('ROUTINE', ID.rtn.placeOrder, 'ROUTINE', ID.rtn.reserveStock, 'CALLS', { confidence: 1.0 }),
  dep('ROUTINE', ID.rtn.calcTotal, 'TABLE', ID.tbl.orderItem, 'READS'),
  dep('ROUTINE', ID.rtn.calcTotal, 'TABLE', ID.tbl.product, 'READS'),
  dep('ROUTINE', ID.rtn.reserveStock, 'TABLE', ID.tbl.inventory, 'WRITES'),
  dep('ROUTINE', ID.rtn.reserveStock, 'TABLE', ID.tbl.product, 'REFERENCES'),
  dep('ROUTINE', ID.rtn.customerTier, 'TABLE', ID.tbl.customer, 'READS'),
  dep('ROUTINE', ID.rtn.customerTier, 'TABLE', ID.tbl.orders, 'READS'),
  dep('TABLE', ID.tbl.orders, 'ROUTINE', ID.rtn.trgOrdersAudit, 'TRIGGERS'),
  dep('ROUTINE', ID.rtn.trgOrdersAudit, 'TABLE', ID.tbl.auditLog, 'WRITES'),
  dep('TABLE', ID.tbl.orderItem, 'ROUTINE', ID.rtn.trgOrderItemStock, 'TRIGGERS'),
  dep('ROUTINE', ID.rtn.trgOrderItemStock, 'TABLE', ID.tbl.inventory, 'WRITES'),
  dep('TABLE', ID.tbl.orders, 'TABLE', ID.tbl.customer, 'FOREIGN_KEY'),
  dep('TABLE', ID.tbl.orderItem, 'TABLE', ID.tbl.orders, 'FOREIGN_KEY'),
  dep('TABLE', ID.tbl.orderItem, 'TABLE', ID.tbl.product, 'FOREIGN_KEY'),
  dep('TABLE', ID.tbl.inventory, 'TABLE', ID.tbl.product, 'FOREIGN_KEY'),
  dep('TABLE', ID.tbl.payment, 'TABLE', ID.tbl.orders, 'FOREIGN_KEY'),
  dep('TABLE', ID.tbl.vCustomerOrders, 'TABLE', ID.tbl.customer, 'REFERENCES'),
  dep('TABLE', ID.tbl.vCustomerOrders, 'TABLE', ID.tbl.orders, 'REFERENCES'),
  dep('ROUTINE', ID.rtn.placeOrder, 'TABLE', ID.tbl.pgOrders, 'WRITES', { source: 'DECLARED', confidence: 1.0, firstSeenAt: ago(20 * DAY) }),
  dep('TABLE', ID.tbl.pgOrders, 'TABLE', ID.tbl.pgCustomer, 'FOREIGN_KEY'),
  dep('TABLE', ID.tbl.pgOrderItem, 'TABLE', ID.tbl.pgOrders, 'FOREIGN_KEY'),
];

// ------------------------------------------------------------------ relationships (application → object)
let relSeq = 0;
const CONF: Record<Relationship['source'], number> = { GATEWAY: 1.0, COLLECTOR_AUDIT: 1.0, PROXY_CORRELATION: 0.9, COLLECTOR_SESSION: 0.6, DECLARED: 1.0 };
const rel = (applicationId: string, objectType: Relationship['objectType'], objectId: string, kind: Relationship['kind'], source: Relationship['source'], queryCount: number, p: Partial<Relationship> = {}): Relationship => ({
  id: `rel-${String(++relSeq).padStart(3, '0')}`, applicationId, objectType, objectId, kind, source, confidence: CONF[source], queryCount,
  firstSeenAt: ago(200 * DAY), lastSeenAt: ago(2 * MIN), confirmed: source === 'DECLARED', viaRoutineId: null, ...p,
});
const relationships: Relationship[] = [
  // orders-service: through the gateway, mostly via ORDER_PKG
  rel(ID.app.orders, 'ROUTINE', ID.rtn.placeOrder, 'CALLS', 'GATEWAY', 48_210, { lastSeenAt: ago(15_000) }),
  rel(ID.app.orders, 'TABLE', ID.tbl.orders, 'WRITES', 'GATEWAY', 48_210, { viaRoutineId: ID.rtn.placeOrder, lastSeenAt: ago(15_000) }),
  rel(ID.app.orders, 'TABLE', ID.tbl.orderItem, 'WRITES', 'GATEWAY', 48_210, { viaRoutineId: ID.rtn.placeOrder, lastSeenAt: ago(15_000) }),
  rel(ID.app.orders, 'TABLE', ID.tbl.inventory, 'WRITES', 'GATEWAY', 48_210, { viaRoutineId: ID.rtn.reserveStock, lastSeenAt: ago(15_000) }),
  rel(ID.app.orders, 'TABLE', ID.tbl.auditLog, 'WRITES', 'GATEWAY', 48_210, { viaRoutineId: ID.rtn.trgOrdersAudit, lastSeenAt: ago(15_000) }),
  rel(ID.app.orders, 'TABLE', ID.tbl.orders, 'READS', 'GATEWAY', 211_400, { lastSeenAt: ago(5_000) }),
  rel(ID.app.orders, 'TABLE', ID.tbl.orderItem, 'READS', 'GATEWAY', 180_900, { lastSeenAt: ago(5_000) }),
  rel(ID.app.orders, 'TABLE', ID.tbl.customer, 'READS', 'GATEWAY', 95_300, { lastSeenAt: ago(8_000) }),
  rel(ID.app.orders, 'TABLE', ID.tbl.product, 'READS', 'GATEWAY', 60_120),
  rel(ID.app.orders, 'TABLE', ID.tbl.pgOrders, 'WRITES', 'GATEWAY', 12_400, { firstSeenAt: ago(30 * DAY), lastSeenAt: ago(20_000) }),
  rel(ID.app.orders, 'TABLE', ID.tbl.pgOrderItem, 'WRITES', 'GATEWAY', 12_400, { firstSeenAt: ago(30 * DAY), lastSeenAt: ago(20_000) }),
  // inventory-service
  rel(ID.app.inventory, 'TABLE', ID.tbl.inventory, 'WRITES', 'GATEWAY', 33_000, { lastSeenAt: ago(40_000) }),
  rel(ID.app.inventory, 'TABLE', ID.tbl.inventory, 'READS', 'GATEWAY', 140_000, { lastSeenAt: ago(40_000) }),
  rel(ID.app.inventory, 'TABLE', ID.tbl.product, 'WRITES', 'GATEWAY', 2_300, { lastSeenAt: ago(3 * HOUR) }),
  rel(ID.app.inventory, 'TABLE', ID.tbl.product, 'READS', 'GATEWAY', 71_000),
  rel(ID.app.inventory, 'ROUTINE', ID.rtn.reserveStock, 'CALLS', 'GATEWAY', 8_700),
  rel(ID.app.inventory, 'TABLE', ID.tbl.pgCustomer, 'READS', 'GATEWAY', 4_100, { firstSeenAt: ago(50 * DAY) }),
  // payment-service
  rel(ID.app.payment, 'TABLE', ID.tbl.payment, 'WRITES', 'GATEWAY', 27_800, { lastSeenAt: ago(5 * MIN) }),
  rel(ID.app.payment, 'TABLE', ID.tbl.payment, 'READS', 'GATEWAY', 54_000, { lastSeenAt: ago(5 * MIN) }),
  rel(ID.app.payment, 'TABLE', ID.tbl.orders, 'READS', 'GATEWAY', 27_800, { lastSeenAt: ago(5 * MIN) }),
  // reporting-batch (nightly)
  rel(ID.app.reporting, 'TABLE', ID.tbl.orders, 'READS', 'GATEWAY', 1_200, { lastSeenAt: ago(9 * HOUR) }),
  rel(ID.app.reporting, 'TABLE', ID.tbl.orderItem, 'READS', 'GATEWAY', 1_200, { lastSeenAt: ago(9 * HOUR) }),
  rel(ID.app.reporting, 'TABLE', ID.tbl.customer, 'READS', 'GATEWAY', 600, { lastSeenAt: ago(9 * HOUR) }),
  rel(ID.app.reporting, 'TABLE', ID.tbl.product, 'READS', 'GATEWAY', 300, { lastSeenAt: ago(9 * HOUR) }),
  rel(ID.app.reporting, 'TABLE', ID.tbl.payment, 'READS', 'GATEWAY', 900, { lastSeenAt: ago(9 * HOUR) }),
  rel(ID.app.reporting, 'ROUTINE', ID.rtn.customerTier, 'CALLS', 'GATEWAY', 600, { lastSeenAt: ago(9 * HOUR) }),
  rel(ID.app.reporting, 'TABLE', ID.tbl.customer, 'READS', 'GATEWAY', 600, { viaRoutineId: ID.rtn.customerTier, lastSeenAt: ago(9 * HOUR) }),
  // customer-portal
  rel(ID.app.portal, 'TABLE', ID.tbl.customer, 'READS', 'GATEWAY', 420_000, { lastSeenAt: ago(3_000) }),
  rel(ID.app.portal, 'TABLE', ID.tbl.customer, 'WRITES', 'GATEWAY', 18_000, { lastSeenAt: ago(60_000) }),
  rel(ID.app.portal, 'TABLE', ID.tbl.orders, 'READS', 'GATEWAY', 310_000, { lastSeenAt: ago(3_000) }),
  rel(ID.app.portal, 'TABLE', ID.tbl.vCustomerOrders, 'READS', 'GATEWAY', 150_000, { lastSeenAt: ago(3_000) }),
  rel(ID.app.portal, 'TABLE', ID.tbl.orders, 'READS', 'GATEWAY', 150_000, { lastSeenAt: ago(3_000) }),
  // legacy-billing: through the proxy, attribution by correlation / collector
  rel(ID.app.billing, 'TABLE', ID.tbl.payment, 'WRITES', 'PROXY_CORRELATION', 6_200, { lastSeenAt: ago(12 * MIN) }),
  rel(ID.app.billing, 'TABLE', ID.tbl.payment, 'READS', 'PROXY_CORRELATION', 15_900, { lastSeenAt: ago(12 * MIN) }),
  rel(ID.app.billing, 'TABLE', ID.tbl.customer, 'READS', 'COLLECTOR_AUDIT', 4_400, { lastSeenAt: ago(25 * MIN) }),
  rel(ID.app.billing, 'TABLE', ID.tbl.orders, 'WRITES', 'COLLECTOR_SESSION', 310, { lastSeenAt: ago(2 * HOUR) }),
  rel(ID.app.billing, 'TABLE', ID.tbl.orders, 'READS', 'DECLARED', 0, { firstSeenAt: ago(100 * DAY), lastSeenAt: null }),
];

// ------------------------------------------------------------------ query stats
const tref = (id: string) => {
  const t = tables.find((x) => x.id === id)!;
  return { id: t.id, databaseId: t.databaseId, schema: t.schema, name: t.name, kind: t.kind };
};
const qs = (sqlHash: string, applicationId: string, databaseId: string, operation: QueryStat['operation'], sqlNormalized: string, tableIds: string[], count: number, avg: number, p: Partial<QueryStat> = {}): QueryStat => ({
  sqlHash, sqlNormalized, operation, applicationId, applicationName: applications.find((a) => a.id === applicationId)?.name ?? null, databaseId,
  tables: tableIds.map(tref), count, avgDurationMs: avg, p95DurationMs: Math.round(avg * 3.2), maxDurationMs: Math.round(avg * 11), rows: count, errors: 0, lastSeenAt: ago(MIN), ...p,
});
const queryStats: QueryStat[] = [
  qs('a1f09c3d', ID.app.portal, ID.db.oracle, 'SELECT', 'SELECT CUSTOMER_ID, EMAIL, FIRST_NAME, LAST_NAME, LOYALTY_TIER FROM SALES.CUSTOMER WHERE CUSTOMER_ID = ?', [ID.tbl.customer], 186_400, 2.1),
  qs('b7c2e114', ID.app.portal, ID.db.oracle, 'SELECT', 'SELECT * FROM SALES.V_CUSTOMER_ORDERS WHERE CUSTOMER_ID = ? ORDER BY PLACED_AT DESC FETCH FIRST ? ROWS ONLY', [ID.tbl.customer, ID.tbl.orders], 98_300, 14.8),
  qs('c3d8f0a2', ID.app.orders, ID.db.oracle, 'CALL', 'BEGIN SALES.ORDER_PKG.PLACE_ORDER(?, ?, ?, ?); END;', [ID.tbl.orders, ID.tbl.orderItem, ID.tbl.inventory, ID.tbl.auditLog], 21_900, 38.4, { errors: 41, rows: 21_859 }),
  qs('d91a77be', ID.app.orders, ID.db.oracle, 'SELECT', 'SELECT O.ORDER_ID, O.STATUS, O.TOTAL_AMOUNT, I.PRODUCT_ID, I.QUANTITY FROM SALES.ORDERS O JOIN SALES.ORDER_ITEM I ON I.ORDER_ID = O.ORDER_ID WHERE O.ORDER_ID = ?', [ID.tbl.orders, ID.tbl.orderItem], 142_000, 4.6),
  qs('e4420c9f', ID.app.orders, ID.db.oracle, 'UPDATE', 'UPDATE SALES.ORDERS SET STATUS = ?, UPDATED_AT = SYSTIMESTAMP WHERE ORDER_ID = ?', [ID.tbl.orders], 19_700, 3.9),
  qs('f00b1d55', ID.app.orders, ID.db.postgres, 'INSERT', 'INSERT INTO sales.orders (order_id, customer_id, status, total_amount, currency, placed_at) VALUES (?, ?, ?, ?, ?, ?)', [ID.tbl.pgOrders], 12_400, 1.7),
  qs('0a9e6b31', ID.app.inventory, ID.db.oracle, 'SELECT', 'SELECT ON_HAND, RESERVED FROM SALES.INVENTORY WHERE PRODUCT_ID = ? AND WAREHOUSE_CODE = ? FOR UPDATE', [ID.tbl.inventory], 61_000, 6.2),
  qs('1b3c9d80', ID.app.inventory, ID.db.oracle, 'UPDATE', 'UPDATE SALES.INVENTORY SET RESERVED = RESERVED + ?, UPDATED_AT = SYSTIMESTAMP WHERE PRODUCT_ID = ? AND WAREHOUSE_CODE = ?', [ID.tbl.inventory], 33_000, 5.1, { errors: 12 }),
  qs('2cd0e4a7', ID.app.payment, ID.db.oracle, 'INSERT', 'INSERT INTO SALES.PAYMENT (PAYMENT_ID, ORDER_ID, AMOUNT, METHOD, CARD_TOKEN, STATUS, CAPTURED_AT) VALUES (?, ?, ?, ?, ?, ?, ?)', [ID.tbl.payment], 27_800, 3.3),
  qs('3de1f5b8', ID.app.payment, ID.db.oracle, 'SELECT', 'SELECT P.PAYMENT_ID, P.STATUS, O.TOTAL_AMOUNT FROM SALES.PAYMENT P JOIN SALES.ORDERS O ON O.ORDER_ID = P.ORDER_ID WHERE P.ORDER_ID = ?', [ID.tbl.payment, ID.tbl.orders], 27_800, 7.4),
  qs('4ef206c9', ID.app.reporting, ID.db.oracle, 'SELECT', 'SELECT TRUNC(O.PLACED_AT), P.CATEGORY, SUM(I.QUANTITY * I.UNIT_PRICE) FROM SALES.ORDERS O JOIN SALES.ORDER_ITEM I ON I.ORDER_ID = O.ORDER_ID JOIN SALES.PRODUCT P ON P.PRODUCT_ID = I.PRODUCT_ID WHERE O.PLACED_AT >= ? GROUP BY TRUNC(O.PLACED_AT), P.CATEGORY', [ID.tbl.orders, ID.tbl.orderItem, ID.tbl.product], 24, 48_200, { rows: 1_900_000, lastSeenAt: ago(9 * HOUR), p95DurationMs: 71_000, maxDurationMs: 112_000 }),
  qs('5f0317da', ID.app.reporting, ID.db.oracle, 'SELECT', 'SELECT C.CUSTOMER_ID, SALES.GET_CUSTOMER_TIER(C.CUSTOMER_ID) FROM SALES.CUSTOMER C', [ID.tbl.customer], 1, 612_000, { rows: 1_240_000, lastSeenAt: ago(9 * HOUR), p95DurationMs: 612_000, maxDurationMs: 612_000 }),
  qs('6a1428eb', ID.app.billing, ID.db.oracle, 'UPDATE', 'UPDATE SALES.PAYMENT SET STATUS = ? WHERE PAYMENT_ID = ?', [ID.tbl.payment], 6_200, 2.8, { lastSeenAt: ago(12 * MIN) }),
  qs('7b2539fc', ID.app.billing, ID.db.oracle, 'UPDATE', 'UPDATE SALES.ORDERS SET STATUS = ? WHERE ORDER_ID = ?', [ID.tbl.orders], 310, 4.4, { lastSeenAt: ago(2 * HOUR), errors: 3 }),
  qs('8c364a0d', ID.app.portal, ID.db.oracle, 'UPDATE', 'UPDATE SALES.CUSTOMER SET EMAIL = ?, PHONE = ? WHERE CUSTOMER_ID = ?', [ID.tbl.customer], 18_000, 3.0),
  qs('9d475b1e', ID.app.inventory, ID.db.postgres, 'SELECT', 'SELECT customer_id, loyalty_tier FROM sales.customer WHERE customer_id = ANY(?)', [ID.tbl.pgCustomer], 4_100, 1.2),
];

// ------------------------------------------------------------------ runtime: pools, connections, components
const poolStats: PoolStats[] = [
  { timestamp: ago(5_000), gatewayId: 'gw-1', datasource: 'sales', datasourceId: ID.ds.sales, databaseId: ID.db.oracle, engine: 'ORACLE', active: 23, idle: 6, waiting: 0, total: 29, max: 40, logicalSessions: 312, pinnedSessions: 14, credentialVersion: 3 },
  { timestamp: ago(5_000), gatewayId: 'gw-1', datasource: 'inventory', datasourceId: ID.ds.inventory, databaseId: ID.db.oracle, engine: 'ORACLE', active: 9, idle: 3, waiting: 0, total: 12, max: 20, logicalSessions: 88, pinnedSessions: 2, credentialVersion: 3 },
  { timestamp: ago(5_000), gatewayId: 'gw-1', datasource: 'payments', datasourceId: ID.ds.payments, databaseId: ID.db.oracle, engine: 'ORACLE', active: 21, idle: 1, waiting: 3, total: 22, max: 25, logicalSessions: 64, pinnedSessions: 40, credentialVersion: 3 },
  { timestamp: ago(6_000), gatewayId: 'gw-2', datasource: 'sales', datasourceId: ID.ds.sales, databaseId: ID.db.postgres, engine: 'POSTGRES', active: 7, idle: 5, waiting: 0, total: 12, max: 40, logicalSessions: 96, pinnedSessions: 0, credentialVersion: 2 },
  { timestamp: ago(6_000), gatewayId: 'gw-2', datasource: 'sales', datasourceId: ID.ds.sales, databaseId: ID.db.oracle, engine: 'ORACLE', active: 11, idle: 4, waiting: 0, total: 15, max: 40, logicalSessions: 140, pinnedSessions: 6, credentialVersion: 3 },
];

const live = (p: Partial<LiveConnection>): LiveConnection => ({
  source: 'PROXY', application: null, team: null, datasource: null, database: 'sales-oracle', engine: 'ORACLE', clientAddr: null, program: null, machine: null, osUser: null, dbUser: null, status: 'ACTIVE', openedAt: ago(10 * MIN), durationSeconds: 600, sqlId: null, currentSql: null, ...p,
});
const liveConnections: LiveConnection[] = [
  ...Array.from({ length: 6 }, (_, i) => live({ application: 'legacy-billing', team: 'finance', datasource: 'payments', clientAddr: `10.50.0.${11 + i}`, program: 'billing.exe', machine: `billing-vm-0${1 + (i % 2)}`, osUser: 'billing', dbUser: 'BILLING_APP', status: i % 3 === 0 ? 'ACTIVE' : 'INACTIVE', openedAt: ago((30 + i * 7) * MIN), durationSeconds: (30 + i * 7) * 60, sqlId: i % 3 === 0 ? 'g4t7q2m0x1' : null, currentSql: i % 3 === 0 ? 'UPDATE SALES.PAYMENT SET STATUS = :1 WHERE PAYMENT_ID = :2' : null })),
  live({ application: null, team: null, datasource: 'payments', clientAddr: '10.9.4.21', program: 'sqlplus', machine: 'laptop-7781', osUser: 'jdoe', dbUser: 'SALES_APP', status: 'ACTIVE', openedAt: ago(4 * MIN), durationSeconds: 240, sqlId: 'b1n4k8z2q7', currentSql: 'SELECT * FROM SALES.PAYMENT WHERE CAPTURED_AT > SYSDATE - 1' }),
  live({ source: 'COLLECTOR', application: 'orders-service', team: 'sales-platform', datasource: 'sales', clientAddr: '10.30.0.9', program: 'dbp-gateway/gw-1', machine: 'gw-1', osUser: 'dbp', dbUser: 'DBP_PLATFORM', status: 'ACTIVE', openedAt: ago(3 * HOUR), durationSeconds: 10_800, sqlId: 'c7m2p9r4t1', currentSql: 'BEGIN SALES.ORDER_PKG.PLACE_ORDER(:1, :2, :3, :4); END;' }),
  ...Array.from({ length: 4 }, (_, i) => live({ source: 'COLLECTOR', application: 'orders-service', team: 'sales-platform', datasource: 'sales', clientAddr: '10.30.0.9', program: 'dbp-gateway/gw-1', machine: 'gw-1', osUser: 'dbp', dbUser: 'DBP_PLATFORM', status: 'INACTIVE', openedAt: ago((3 + i) * HOUR), durationSeconds: (3 + i) * 3600 })),
  live({ source: 'COLLECTOR', application: 'reporting-batch', team: 'analytics', datasource: 'sales', clientAddr: '10.40.1.5', program: 'sqlplus', machine: 'batch-01', osUser: 'batch', dbUser: 'DBP_PLATFORM', status: 'ACTIVE', openedAt: ago(9 * HOUR), durationSeconds: 32_400, sqlId: 'q2w8e5r1t6', currentSql: 'SELECT TRUNC(O.PLACED_AT), P.CATEGORY, SUM(I.QUANTITY * I.UNIT_PRICE) FROM SALES.ORDERS O ...' }),
  live({ source: 'COLLECTOR', application: 'inventory-service', team: 'inventory', datasource: 'sales', database: 'sales-postgres', engine: 'POSTGRES', clientAddr: '10.30.0.10', program: 'dbp-gateway/gw-2', machine: 'gw-2', osUser: 'dbp', dbUser: 'dbp_platform', status: 'idle', openedAt: ago(50 * MIN), durationSeconds: 3000 }),
  live({ source: 'COLLECTOR', application: 'customer-portal', team: 'customer-experience', datasource: 'sales', clientAddr: '10.30.0.9', program: 'dbp-gateway/gw-1', machine: 'gw-1', osUser: 'dbp', dbUser: 'DBP_PLATFORM', status: 'ACTIVE', openedAt: ago(70 * MIN), durationSeconds: 4200, sqlId: 'a1f09c3dxx', currentSql: 'SELECT CUSTOMER_ID, EMAIL, FIRST_NAME, LAST_NAME, LOYALTY_TIER FROM SALES.CUSTOMER WHERE CUSTOMER_ID = :1' }),
];

const components: ComponentInfo[] = [
  { componentType: 'GATEWAY', componentId: 'gw-1', version: '0.1.0', host: 'gw-1.internal', startedAt: ago(6 * DAY), lastHeartbeat: ago(4_000), healthy: true, stats: { logicalSessions: 464, physicalConnections: 63, eventsDropped: 0, configVersion: 17 } },
  { componentType: 'GATEWAY', componentId: 'gw-2', version: '0.1.0', host: 'gw-2.internal', startedAt: ago(2 * DAY), lastHeartbeat: ago(6_000), healthy: true, stats: { logicalSessions: 236, physicalConnections: 27, eventsDropped: 0, configVersion: 17 } },
  { componentType: 'PROXY', componentId: 'proxy-1', version: '0.1.0', host: 'proxy-1.internal', startedAt: ago(14 * DAY), lastHeartbeat: ago(3_000), healthy: true, stats: { liveConnections: 7, refused: 2, eventsDropped: 0, configVersion: 17 } },
  { componentType: 'PROXY', componentId: 'proxy-2', version: '0.0.9', host: 'proxy-2.internal', startedAt: ago(40 * DAY), lastHeartbeat: ago(7 * MIN), healthy: false, stats: { liveConnections: 0, refused: 0, eventsDropped: 120, configVersion: 15 } },
];

// ------------------------------------------------------------------ governance
const policies: Policy[] = [
  { id: 'pol-cross-team', kind: 'CROSS_TEAM_DIRECT_ACCESS', enabled: true, severity: 'HIGH', description: 'An application accesses a table owned by another team without a declared or confirmed relationship.' },
  { id: 'pol-unowned', kind: 'UNOWNED_TABLE', enabled: true, severity: 'LOW', description: 'A table with runtime activity has no owner team.' },
  { id: 'pol-undeclared', kind: 'UNDECLARED_CONSUMER', enabled: true, severity: 'MEDIUM', description: 'A consumer observed at runtime that has no declared relationship.' },
  { id: 'pol-non-producer', kind: 'WRITE_BY_NON_PRODUCER', enabled: true, severity: 'MEDIUM', description: 'A table is written by an application other than its producer.' },
  { id: 'pol-bypass', kind: 'DIRECT_DB_ACCESS_BYPASSING_PLATFORM', enabled: true, severity: 'HIGH', description: 'A database session that did not come through the gateway or the proxy.' },
];
const violations: Violation[] = [
  { id: 'viol-001', policyKind: 'CROSS_TEAM_DIRECT_ACCESS', severity: 'HIGH', applicationId: ID.app.billing, teamId: ID.team.finance, objectType: 'TABLE', objectId: ID.tbl.orders, label: 'legacy-billing WRITES SALES.ORDERS', detail: 'finance application writes a table owned by sales-platform; no declared WRITES relationship (observed by COLLECTOR_SESSION, 310 statements in 7d).', firstSeenAt: ago(12 * DAY), lastSeenAt: ago(2 * HOUR), status: 'OPEN' },
  { id: 'viol-002', policyKind: 'WRITE_BY_NON_PRODUCER', severity: 'MEDIUM', applicationId: ID.app.billing, teamId: ID.team.finance, objectType: 'TABLE', objectId: ID.tbl.payment, label: 'legacy-billing WRITES SALES.PAYMENT', detail: 'Producer of SALES.PAYMENT is payment-service. legacy-billing also writes it (6,200 statements in 7d).', firstSeenAt: ago(40 * DAY), lastSeenAt: ago(12 * MIN), status: 'ACKNOWLEDGED' },
  { id: 'viol-003', policyKind: 'UNOWNED_TABLE', severity: 'LOW', applicationId: null, teamId: null, objectType: 'TABLE', objectId: ID.tbl.auditLog, label: 'SALES.AUDIT_LOG has no owner', detail: 'Written by TRG_ORDERS_AUDIT on behalf of orders-service; 48,210 writes in 7d.', firstSeenAt: ago(90 * DAY), lastSeenAt: ago(30_000), status: 'OPEN' },
  { id: 'viol-004', policyKind: 'UNDECLARED_CONSUMER', severity: 'MEDIUM', applicationId: ID.app.reporting, teamId: ID.team.analytics, objectType: 'TABLE', objectId: ID.tbl.payment, label: 'reporting-batch READS SALES.PAYMENT', detail: 'analytics reads a finance table without a declared relationship (900 statements in 7d).', firstSeenAt: ago(30 * DAY), lastSeenAt: ago(9 * HOUR), status: 'OPEN' },
  { id: 'viol-005', policyKind: 'DIRECT_DB_ACCESS_BYPASSING_PLATFORM', severity: 'HIGH', applicationId: null, teamId: null, objectType: null, objectId: null, label: 'sqlplus from 10.9.4.21 (laptop-7781) on sales-oracle', detail: 'Session connected directly to the listener (not via proxy-1); PROGRAM=sqlplus USER=jdoe. Matches no application identity rule.', firstSeenAt: ago(4 * MIN), lastSeenAt: ago(MIN), status: 'OPEN' },
  { id: 'viol-006', policyKind: 'UNOWNED_TABLE', severity: 'LOW', applicationId: null, teamId: null, objectType: 'TABLE', objectId: ID.tbl.productFeed, label: 'STAGING.PRODUCT_FEED has no owner', detail: 'No runtime activity for 40 days.', firstSeenAt: ago(200 * DAY), lastSeenAt: ago(40 * DAY), status: 'RESOLVED' },
  { id: 'viol-007', policyKind: 'CROSS_TEAM_DIRECT_ACCESS', severity: 'HIGH', applicationId: ID.app.portal, teamId: ID.team.cx, objectType: 'TABLE', objectId: ID.tbl.orders, label: 'customer-portal READS SALES.ORDERS', detail: 'customer-experience reads a sales-platform table directly (310,000 statements in 7d) in addition to the V_CUSTOMER_ORDERS view.', firstSeenAt: ago(60 * DAY), lastSeenAt: ago(3_000), status: 'ACKNOWLEDGED' },
];

const migrationEvents: MigrationEvent[] = [
  { id: 'mig-001', datasourceId: ID.ds.sales, fromDatabaseId: null, toDatabaseId: ID.db.oracle, at: ago(380 * DAY), by: 'import', note: 'Initial registration' },
  { id: 'mig-002', datasourceId: ID.ds.inventory, fromDatabaseId: null, toDatabaseId: ID.db.oracle, at: ago(370 * DAY), by: 'import', note: 'Initial registration' },
  { id: 'mig-003', datasourceId: ID.ds.payments, fromDatabaseId: null, toDatabaseId: ID.db.oracle, at: ago(360 * DAY), by: 'import', note: 'Initial registration' },
];

const collectorStatus: Record<string, CollectorStatus> = {
  [ID.db.oracle]: { lastDictionaryRun: ago(42 * MIN), lastRuntimeRun: ago(12_000), lastError: null, tablesSeen: 10 },
  [ID.db.postgres]: { lastDictionaryRun: ago(18 * MIN), lastRuntimeRun: ago(9_000), lastError: 'pg_stat_statements not installed; query text unavailable', tablesSeen: 3 },
};

export function buildDemoStore(): DemoStore {
  // structuredClone so that mutations against the store never leak into the template arrays above
  return structuredClone({
    configVersion: 17,
    teams, applications, apiKeys, databases, credentials, datasources, accessGrants, tables, columns, routines, dependencies,
    relationships, queryStats, poolStats, liveConnections, components, policies, violations, migrationEvents, collectorStatus,
  });
}

export const demoStore: DemoStore = buildDemoStore();
