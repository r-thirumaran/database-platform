package org.dbplatform.controlplane.service.seed;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dbplatform.controlplane.api.dto.CredentialRequest;
import org.dbplatform.controlplane.config.DbpProperties;
import org.dbplatform.controlplane.domain.AccessGrant;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.CollectorConfig;
import org.dbplatform.controlplane.domain.Credential;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.DbColumn;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Enums.DependencyKind;
import org.dbplatform.controlplane.domain.Enums.DependencySource;
import org.dbplatform.controlplane.domain.Enums.ObjectType;
import org.dbplatform.controlplane.domain.Enums.RelationshipKind;
import org.dbplatform.controlplane.domain.Enums.RelationshipSource;
import org.dbplatform.controlplane.domain.IdentityRules;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.domain.Json;
import org.dbplatform.controlplane.domain.PoolPolicy;
import org.dbplatform.controlplane.domain.QueryStat;
import org.dbplatform.controlplane.domain.Relationship;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.domain.RoutingRule;
import org.dbplatform.controlplane.domain.TableMigration;
import org.dbplatform.controlplane.domain.Team;
import org.dbplatform.controlplane.repo.DbColumnRepository;
import org.dbplatform.controlplane.repo.DbTableRepository;
import org.dbplatform.controlplane.repo.QueryStatRepository;
import org.dbplatform.controlplane.repo.RelationshipRepository;
import org.dbplatform.controlplane.repo.RoutineRepository;
import org.dbplatform.controlplane.service.AccessGrantService;
import org.dbplatform.controlplane.service.ApplicationService;
import org.dbplatform.controlplane.service.CatalogueService;
import org.dbplatform.controlplane.service.CredentialService;
import org.dbplatform.controlplane.service.DatabaseService;
import org.dbplatform.controlplane.service.DatasourceService;
import org.dbplatform.controlplane.service.SecretCipher;
import org.dbplatform.controlplane.service.TeamService;
import org.dbplatform.controlplane.service.governance.GovernanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The retail demo dataset used by the examples and the UI demo mode. Idempotent: every object is
 * upserted by name, runtime counters are set to fixed values.
 */
@Service
public class DemoSeedService {
    private static final Logger log = LoggerFactory.getLogger(DemoSeedService.class);

    private final TeamService teams;
    private final ApplicationService applications;
    private final CredentialService credentials;
    private final DatabaseService databases;
    private final DatasourceService datasources;
    private final AccessGrantService grants;
    private final CatalogueService catalogue;
    private final GovernanceService governance;
    private final DbTableRepository tables;
    private final DbColumnRepository columns;
    private final RoutineRepository routines;
    private final RelationshipRepository relationships;
    private final QueryStatRepository queryStats;
    private final DbpProperties props;

    public DemoSeedService(TeamService teams, ApplicationService applications, CredentialService credentials, DatabaseService databases,
                           DatasourceService datasources, AccessGrantService grants, CatalogueService catalogue, GovernanceService governance,
                           DbTableRepository tables, DbColumnRepository columns, RoutineRepository routines, RelationshipRepository relationships,
                           QueryStatRepository queryStats, DbpProperties props) {
        this.teams = teams; this.applications = applications; this.credentials = credentials; this.databases = databases; this.datasources = datasources;
        this.grants = grants; this.catalogue = catalogue; this.governance = governance; this.tables = tables; this.columns = columns;
        this.routines = routines; this.relationships = relationships; this.queryStats = queryStats; this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (props.getDemo().isSeedEnabled() && props.getDemo().isSeedOnStartup()) {
            log.info("DBP_SEED_ON_STARTUP=true: loading the demo dataset");
            seed();
        }
    }

    public boolean enabled() { return props.getDemo().isSeedEnabled(); }

    public record SeedResult(int teams, int applications, int databases, int datasources, int tables, int routines, int relationships) {}

    @Transactional
    public SeedResult seed() {
        // ---- teams
        Map<String, Team> t = new LinkedHashMap<>();
        t.put("sales-platform", team("sales-platform", "Sales Platform Team", "Owns the order-to-cash sales domain", "domain:sales"));
        t.put("inventory", team("inventory", "Inventory Team", "Stock levels and warehouse integration", "domain:inventory"));
        t.put("finance", team("finance", "Finance Team", "Payments, billing and ledgers", "domain:finance"));
        t.put("analytics", team("analytics", "Analytics Team", "Reporting and data products", "domain:analytics"));
        t.put("customer-experience", team("customer-experience", "Customer Experience Team", "Customer portal and profile data", "domain:customer"));

        // ---- applications
        Map<String, Application> a = new LinkedHashMap<>();
        a.put("orders-service", app("orders-service", "Orders Service", t.get("sales-platform"), Enums.ApplicationKind.SERVICE, Enums.Runtime.KUBERNETES,
                rules(List.of("10.20.0.0/16"), List.of("orders-service", "JDBC Thin Client/orders"), List.of("orders-service-*"), List.of("orders-service", "orders"), List.of("orders-service")), List.of("tier:core", "lang:java")));
        a.put("inventory-service", app("inventory-service", "Inventory Service", t.get("inventory"), Enums.ApplicationKind.SERVICE, Enums.Runtime.KUBERNETES,
                rules(List.of("10.21.0.0/16"), List.of("inventory-service"), List.of("inventory-*"), List.of("inventory-service", "inventory"), List.of("inventory-service")), List.of("tier:core", "lang:java")));
        a.put("payment-service", app("payment-service", "Payment Service", t.get("finance"), Enums.ApplicationKind.SERVICE, Enums.Runtime.CLOUD_RUN,
                rules(List.of("10.22.0.0/16"), List.of("payment-service"), List.of("payment-*"), List.of("payment-service", "payments"), List.of("payment-service")), List.of("tier:core", "pci")));
        a.put("reporting-batch", app("reporting-batch", "Reporting Batch", t.get("analytics"), Enums.ApplicationKind.BATCH, Enums.Runtime.VM,
                rules(List.of("10.30.0.0/16"), List.of("reporting-batch", "sqlplus@reporting*"), List.of("rpt-*"), List.of("reporting-batch", "reporting"), List.of("reporting-batch")), List.of("tier:batch", "pg-ready")));
        a.put("customer-portal", app("customer-portal", "Customer Portal", t.get("customer-experience"), Enums.ApplicationKind.UI, Enums.Runtime.KUBERNETES,
                rules(List.of("10.23.0.0/16"), List.of("customer-portal"), List.of("portal-*"), List.of("customer-portal", "portal"), List.of("customer-portal")), List.of("tier:edge", "lang:node")));
        a.put("legacy-billing", app("legacy-billing", "Legacy Billing", t.get("finance"), Enums.ApplicationKind.LEGACY, Enums.Runtime.VM,
                rules(List.of("10.40.0.0/16"), List.of("billing.exe", "JDBC Thin Client/billing"), List.of("billing-vm-*"), List.of("legacy-billing", "billing"), List.of()), List.of("tier:legacy", "no-change")));

        // ---- credentials (ENV refs: nothing secret in the repository)
        Credential cOra = credentials.upsertByName(new CredentialRequest("sales-oracle-app", "SALES_APP", Enums.CredentialProvider.ENV, "SALES_ORACLE_PASSWORD", null, "Oracle application account used by gateway pools and collectors"));
        Credential cPg = credentials.upsertByName(new CredentialRequest("sales-postgres-app", "sales_app", Enums.CredentialProvider.ENV, "SALES_POSTGRES_PASSWORD", null, "PostgreSQL application account used by gateway pools and collectors"));

        // ---- databases
        DatabaseInstance ora = database("sales-oracle", Enums.Engine.ORACLE, "oracle", 1521, "FREEPDB1", cOra, 60, List.of("SALES"), "Demo Oracle Free (sales schema)");
        DatabaseInstance pg = database("sales-postgres", Enums.Engine.POSTGRES, "postgres", 5432, "sales", cPg, 100, List.of("sales"), "Demo PostgreSQL (migration target)");

        // ---- datasources
        Datasource dsSales = datasource("sales", "Sales domain data", t.get("sales-platform"), Enums.DatasourceState.MIGRATING, ora, pg, 40,
                List.of(rule(10, a.get("reporting-batch").getId(), null, pg.getId(), true), rule(20, null, "pg-ready", pg.getId(), false)));
        Datasource dsInv = datasource("inventory", "Inventory and stock", t.get("inventory"), Enums.DatasourceState.ACTIVE, ora, null, 20, List.of());
        Datasource dsPay = datasource("payments", "Payments and billing", t.get("finance"), Enums.DatasourceState.ACTIVE, ora, null, 20, List.of());

        // ---- grants
        grant(a.get("orders-service"), dsSales, 50, 20, false);
        grant(a.get("orders-service"), dsInv, 10, 5, false);
        grant(a.get("orders-service"), dsPay, 10, 5, false);
        grant(a.get("inventory-service"), dsInv, 30, 10, false);
        grant(a.get("inventory-service"), dsSales, 10, 5, true);
        grant(a.get("payment-service"), dsPay, 30, 10, false);
        grant(a.get("payment-service"), dsSales, 10, 5, true);
        grant(a.get("reporting-batch"), dsSales, 8, 4, true);
        grant(a.get("customer-portal"), dsSales, 40, 20, false);
        grant(a.get("legacy-billing"), dsPay, 5, 10, false);

        // ---- tables (Oracle SALES)
        Map<String, DbTable> tb = new LinkedHashMap<>();
        tb.put("CUSTOMER", table(ora, "SALES", "CUSTOMER", Enums.TableKind.TABLE, t.get("customer-experience"), a.get("customer-portal"), Enums.Classification.PII, 120_000L, "Customers and their contact details",
                List.of(col("CUSTOMER_ID", "NUMBER", 10, 0, false, Enums.Classification.INTERNAL), col("EMAIL", "VARCHAR2", 255, null, false, Enums.Classification.PII), col("FULL_NAME", "VARCHAR2", 200, null, false, Enums.Classification.PII),
                        col("PHONE", "VARCHAR2", 40, null, true, Enums.Classification.PII), col("TIER", "VARCHAR2", 10, null, true, null), col("CREATED_AT", "TIMESTAMP(6)", null, null, false, null))));
        tb.put("PRODUCT", table(ora, "SALES", "PRODUCT", Enums.TableKind.TABLE, t.get("sales-platform"), a.get("orders-service"), Enums.Classification.INTERNAL, 8_500L, "Product catalogue",
                List.of(col("PRODUCT_ID", "NUMBER", 10, 0, false, null), col("SKU", "VARCHAR2", 64, null, false, null), col("NAME", "VARCHAR2", 200, null, false, null), col("PRICE", "NUMBER", 12, 2, false, null), col("ACTIVE", "CHAR", 1, null, false, null))));
        tb.put("ORDERS", table(ora, "SALES", "ORDERS", Enums.TableKind.TABLE, t.get("sales-platform"), a.get("orders-service"), Enums.Classification.CONFIDENTIAL, 2_400_000L, "Customer orders (header)",
                List.of(col("ORDER_ID", "NUMBER", 12, 0, false, null), col("CUSTOMER_ID", "NUMBER", 10, 0, false, null), col("STATUS", "VARCHAR2", 20, null, false, null), col("TOTAL_AMOUNT", "NUMBER", 14, 2, true, null), col("ORDERED_AT", "TIMESTAMP(6)", null, null, false, null))));
        tb.put("ORDER_ITEM", table(ora, "SALES", "ORDER_ITEM", Enums.TableKind.TABLE, t.get("sales-platform"), a.get("orders-service"), Enums.Classification.INTERNAL, 9_800_000L, "Order lines",
                List.of(col("ORDER_ITEM_ID", "NUMBER", 14, 0, false, null), col("ORDER_ID", "NUMBER", 12, 0, false, null), col("PRODUCT_ID", "NUMBER", 10, 0, false, null), col("QUANTITY", "NUMBER", 8, 0, false, null), col("UNIT_PRICE", "NUMBER", 12, 2, false, null))));
        tb.put("INVENTORY", table(ora, "SALES", "INVENTORY", Enums.TableKind.TABLE, t.get("inventory"), a.get("inventory-service"), Enums.Classification.INTERNAL, 8_500L, "Stock per product and warehouse",
                List.of(col("PRODUCT_ID", "NUMBER", 10, 0, false, null), col("WAREHOUSE", "VARCHAR2", 20, null, false, null), col("ON_HAND", "NUMBER", 10, 0, false, null), col("RESERVED", "NUMBER", 10, 0, false, null), col("UPDATED_AT", "TIMESTAMP(6)", null, null, false, null))));
        tb.put("PAYMENT", table(ora, "SALES", "PAYMENT", Enums.TableKind.TABLE, t.get("finance"), a.get("payment-service"), Enums.Classification.CONFIDENTIAL, 2_300_000L, "Payments against orders",
                List.of(col("PAYMENT_ID", "NUMBER", 12, 0, false, null), col("ORDER_ID", "NUMBER", 12, 0, false, null), col("AMOUNT", "NUMBER", 14, 2, false, null), col("METHOD", "VARCHAR2", 20, null, false, null), col("CARD_LAST4", "VARCHAR2", 4, null, true, Enums.Classification.PII), col("PAID_AT", "TIMESTAMP(6)", null, null, false, null))));
        tb.put("AUDIT_LOG", table(ora, "SALES", "AUDIT_LOG", Enums.TableKind.TABLE, t.get("sales-platform"), null, Enums.Classification.INTERNAL, 31_000_000L, "Change log written by triggers",
                List.of(col("AUDIT_ID", "NUMBER", 14, 0, false, null), col("TABLE_NAME", "VARCHAR2", 128, null, false, null), col("ROW_PK", "VARCHAR2", 64, null, false, null), col("ACTION", "VARCHAR2", 10, null, false, null), col("CHANGED_BY", "VARCHAR2", 128, null, false, null), col("CHANGED_AT", "TIMESTAMP(6)", null, null, false, null))));
        tb.put("V_ORDER_SUMMARY", table(ora, "SALES", "V_ORDER_SUMMARY", Enums.TableKind.VIEW, t.get("analytics"), null, Enums.Classification.CONFIDENTIAL, null, "Order header + customer + item count, used by reporting",
                List.of(col("ORDER_ID", "NUMBER", 12, 0, false, null), col("CUSTOMER_EMAIL", "VARCHAR2", 255, null, false, Enums.Classification.PII), col("ITEM_COUNT", "NUMBER", 8, 0, false, null), col("TOTAL_AMOUNT", "NUMBER", 14, 2, true, null))));
        // migration targets on PostgreSQL
        DbTable pgOrders = table(pg, "sales", "orders", Enums.TableKind.TABLE, t.get("sales-platform"), a.get("orders-service"), Enums.Classification.CONFIDENTIAL, 0L, "Migrated ORDERS",
                List.of(col("order_id", "bigint", null, null, false, null), col("customer_id", "bigint", null, null, false, null), col("status", "text", null, null, false, null), col("total_amount", "numeric", 14, 2, true, null), col("ordered_at", "timestamptz", null, null, false, null)));
        DbTable pgOrderItem = table(pg, "sales", "order_item", Enums.TableKind.TABLE, t.get("sales-platform"), a.get("orders-service"), Enums.Classification.INTERNAL, 0L, "Migrated ORDER_ITEM",
                List.of(col("order_item_id", "bigint", null, null, false, null), col("order_id", "bigint", null, null, false, null), col("product_id", "bigint", null, null, false, null), col("quantity", "integer", null, null, false, null), col("unit_price", "numeric", 12, 2, false, null)));
        DbTable pgCustomer = table(pg, "sales", "customer", Enums.TableKind.TABLE, t.get("customer-experience"), a.get("customer-portal"), Enums.Classification.PII, 0L, "Migrated CUSTOMER",
                List.of(col("customer_id", "bigint", null, null, false, null), col("email", "text", null, null, false, Enums.Classification.PII), col("full_name", "text", null, null, false, Enums.Classification.PII)));
        migration(tb.get("ORDERS"), pg, "sales", "orders", Enums.MigrationState.IN_PROGRESS);
        migration(tb.get("ORDER_ITEM"), pg, "sales", "order_item", Enums.MigrationState.IN_PROGRESS);
        migration(tb.get("CUSTOMER"), pg, "sales", "customer", Enums.MigrationState.PLANNED);
        pgDep(pgOrderItem, pgOrders); pgDep(pgOrders, pgCustomer);

        // ---- routines (Oracle SALES)
        Map<String, Routine> r = new LinkedHashMap<>();
        r.put("ORDER_PKG", routine(ora, "SALES", "ORDER_PKG", Enums.RoutineKind.PACKAGE, null, null, t.get("sales-platform")));
        r.put("ORDER_PKG.PLACE_ORDER", routine(ora, "SALES", "ORDER_PKG.PLACE_ORDER", Enums.RoutineKind.PROCEDURE, null, null, t.get("sales-platform")));
        r.put("ORDER_PKG.CALC_TOTAL", routine(ora, "SALES", "ORDER_PKG.CALC_TOTAL", Enums.RoutineKind.FUNCTION, null, null, t.get("sales-platform")));
        r.put("RESERVE_STOCK", routine(ora, "SALES", "RESERVE_STOCK", Enums.RoutineKind.PROCEDURE, null, null, t.get("inventory")));
        r.put("GET_CUSTOMER_TIER", routine(ora, "SALES", "GET_CUSTOMER_TIER", Enums.RoutineKind.FUNCTION, null, null, t.get("customer-experience")));
        r.put("TRG_ORDERS_AUDIT", routine(ora, "SALES", "TRG_ORDERS_AUDIT", Enums.RoutineKind.TRIGGER, tb.get("ORDERS"), "AFTER INSERT OR UPDATE", t.get("sales-platform")));
        r.put("TRG_ORDER_ITEM_STOCK", routine(ora, "SALES", "TRG_ORDER_ITEM_STOCK", Enums.RoutineKind.TRIGGER, tb.get("ORDER_ITEM"), "AFTER INSERT", t.get("inventory")));

        // ---- dictionary dependencies
        dep(r.get("ORDER_PKG"), r.get("ORDER_PKG.PLACE_ORDER"), DependencyKind.REFERENCES, 1.0);
        dep(r.get("ORDER_PKG"), r.get("ORDER_PKG.CALC_TOTAL"), DependencyKind.REFERENCES, 1.0);
        dep(r.get("ORDER_PKG.PLACE_ORDER"), tb.get("ORDERS"), DependencyKind.WRITES, 0.8);
        dep(r.get("ORDER_PKG.PLACE_ORDER"), tb.get("ORDER_ITEM"), DependencyKind.WRITES, 0.8);
        dep(r.get("ORDER_PKG.PLACE_ORDER"), tb.get("CUSTOMER"), DependencyKind.READS, 0.8);
        dep(r.get("ORDER_PKG.PLACE_ORDER"), tb.get("PRODUCT"), DependencyKind.READS, 0.8);
        dep(r.get("ORDER_PKG.PLACE_ORDER"), r.get("ORDER_PKG.CALC_TOTAL"), DependencyKind.CALLS, 1.0);
        dep(r.get("ORDER_PKG.PLACE_ORDER"), r.get("RESERVE_STOCK"), DependencyKind.CALLS, 1.0);
        dep(r.get("ORDER_PKG.CALC_TOTAL"), tb.get("ORDER_ITEM"), DependencyKind.READS, 0.8);
        dep(r.get("ORDER_PKG.CALC_TOTAL"), tb.get("PRODUCT"), DependencyKind.READS, 0.8);
        dep(r.get("RESERVE_STOCK"), tb.get("INVENTORY"), DependencyKind.WRITES, 0.8);
        dep(r.get("RESERVE_STOCK"), tb.get("PRODUCT"), DependencyKind.READS, 0.8);
        dep(r.get("GET_CUSTOMER_TIER"), tb.get("CUSTOMER"), DependencyKind.READS, 0.8);
        dep(r.get("GET_CUSTOMER_TIER"), tb.get("ORDERS"), DependencyKind.READS, 0.8);
        depT(tb.get("ORDERS"), r.get("TRG_ORDERS_AUDIT"), DependencyKind.TRIGGERS);
        dep(r.get("TRG_ORDERS_AUDIT"), tb.get("AUDIT_LOG"), DependencyKind.WRITES, 0.8);
        depT(tb.get("ORDER_ITEM"), r.get("TRG_ORDER_ITEM_STOCK"), DependencyKind.TRIGGERS);
        dep(r.get("TRG_ORDER_ITEM_STOCK"), tb.get("INVENTORY"), DependencyKind.WRITES, 0.8);
        fk(tb.get("ORDER_ITEM"), tb.get("ORDERS")); fk(tb.get("ORDER_ITEM"), tb.get("PRODUCT")); fk(tb.get("ORDERS"), tb.get("CUSTOMER"));
        fk(tb.get("PAYMENT"), tb.get("ORDERS")); fk(tb.get("INVENTORY"), tb.get("PRODUCT"));
        viewDep(tb.get("V_ORDER_SUMMARY"), tb.get("ORDERS")); viewDep(tb.get("V_ORDER_SUMMARY"), tb.get("ORDER_ITEM")); viewDep(tb.get("V_ORDER_SUMMARY"), tb.get("CUSTOMER"));

        // ---- runtime relationships (fixed counters so the seed is idempotent)
        Instant now = Instant.now();
        Application orders = a.get("orders-service"), inv = a.get("inventory-service"), pay = a.get("payment-service"), rpt = a.get("reporting-batch"), portal = a.get("customer-portal"), billing = a.get("legacy-billing");
        int n = 0;
        n += rel(orders, r.get("ORDER_PKG.PLACE_ORDER"), RelationshipKind.CALLS, RelationshipSource.GATEWAY, 5_200, now.minusSeconds(60), null);
        for (Map.Entry<String, RelationshipKind> ex : catalogue.expandRoutineToTables(r.get("ORDER_PKG.PLACE_ORDER").getId()).entrySet()) {
            n += relT(orders, ex.getKey(), ex.getValue(), RelationshipSource.GATEWAY, 5_200, now.minusSeconds(60), r.get("ORDER_PKG.PLACE_ORDER").getId());
        }
        n += rel(orders, tb.get("ORDERS"), RelationshipKind.READS, RelationshipSource.GATEWAY, 12_400, now.minusSeconds(30), null);
        n += rel(orders, tb.get("ORDERS"), RelationshipKind.WRITES, RelationshipSource.GATEWAY, 3_100, now.minusSeconds(45), null);
        n += rel(orders, tb.get("ORDER_ITEM"), RelationshipKind.READS, RelationshipSource.GATEWAY, 9_800, now.minusSeconds(30), null);
        n += rel(orders, tb.get("CUSTOMER"), RelationshipKind.READS, RelationshipSource.GATEWAY, 8_050, now.minusSeconds(90), null);
        n += rel(orders, tb.get("PRODUCT"), RelationshipKind.READS, RelationshipSource.GATEWAY, 4_200, now.minusSeconds(120), null);
        n += rel(inv, tb.get("INVENTORY"), RelationshipKind.READS, RelationshipSource.GATEWAY, 9_300, now.minusSeconds(20), null);
        n += rel(inv, tb.get("INVENTORY"), RelationshipKind.WRITES, RelationshipSource.GATEWAY, 2_500, now.minusSeconds(25), null);
        n += rel(inv, tb.get("PRODUCT"), RelationshipKind.READS, RelationshipSource.GATEWAY, 1_500, now.minusSeconds(300), null);
        n += rel(pay, tb.get("PAYMENT"), RelationshipKind.WRITES, RelationshipSource.GATEWAY, 2_200, now.minusSeconds(15), null);
        n += rel(pay, tb.get("PAYMENT"), RelationshipKind.READS, RelationshipSource.GATEWAY, 6_100, now.minusSeconds(15), null);
        n += rel(pay, tb.get("ORDERS"), RelationshipKind.READS, RelationshipSource.GATEWAY, 4_000, now.minusSeconds(50), null);
        n += rel(pay, tb.get("CUSTOMER"), RelationshipKind.READS, RelationshipSource.GATEWAY, 700, now.minusSeconds(400), null);        // cross-team, undeclared
        n += rel(rpt, tb.get("ORDERS"), RelationshipKind.READS, RelationshipSource.PROXY_CORRELATION, 640, now.minusSeconds(3600), null);
        n += rel(rpt, tb.get("ORDER_ITEM"), RelationshipKind.READS, RelationshipSource.PROXY_CORRELATION, 640, now.minusSeconds(3600), null);
        n += rel(rpt, tb.get("V_ORDER_SUMMARY"), RelationshipKind.READS, RelationshipSource.PROXY_CORRELATION, 310, now.minusSeconds(3600), null);
        n += rel(rpt, tb.get("PAYMENT"), RelationshipKind.READS, RelationshipSource.COLLECTOR_SESSION, 120, now.minusSeconds(7200), null);  // direct access, cross-team
        n += rel(rpt, tb.get("CUSTOMER"), RelationshipKind.READS, RelationshipSource.PROXY_CORRELATION, 300, now.minusSeconds(3600), null);
        n += rel(portal, tb.get("CUSTOMER"), RelationshipKind.READS, RelationshipSource.GATEWAY, 15_200, now.minusSeconds(5), null);
        n += rel(portal, tb.get("CUSTOMER"), RelationshipKind.WRITES, RelationshipSource.GATEWAY, 900, now.minusSeconds(200), null);
        n += rel(portal, tb.get("ORDERS"), RelationshipKind.READS, RelationshipSource.GATEWAY, 2_050, now.minusSeconds(10), null);
        n += rel(portal, r.get("GET_CUSTOMER_TIER"), RelationshipKind.CALLS, RelationshipSource.GATEWAY, 1_200, now.minusSeconds(10), null);
        for (Map.Entry<String, RelationshipKind> ex : catalogue.expandRoutineToTables(r.get("GET_CUSTOMER_TIER").getId()).entrySet()) {
            n += relT(portal, ex.getKey(), ex.getValue(), RelationshipSource.GATEWAY, 1_200, now.minusSeconds(10), r.get("GET_CUSTOMER_TIER").getId());
        }
        n += rel(billing, tb.get("PAYMENT"), RelationshipKind.WRITES, RelationshipSource.COLLECTOR_SESSION, 410, now.minusSeconds(900), null); // non-producer write, bypassing platform
        n += rel(billing, tb.get("ORDERS"), RelationshipKind.READS, RelationshipSource.COLLECTOR_SESSION, 820, now.minusSeconds(900), null);
        n += rel(billing, tb.get("ORDERS"), RelationshipKind.WRITES, RelationshipSource.COLLECTOR_AUDIT, 55, now.minusSeconds(86_400 * 2), null); // cross-team write
        // declared relationships (allowed cross-team accesses)
        declared(pay, tb.get("ORDERS"), RelationshipKind.READS);
        declared(rpt, tb.get("ORDERS"), RelationshipKind.READS);
        declared(rpt, tb.get("ORDER_ITEM"), RelationshipKind.READS);
        declared(rpt, tb.get("V_ORDER_SUMMARY"), RelationshipKind.READS);
        declared(orders, tb.get("CUSTOMER"), RelationshipKind.READS);

        // ---- hourly query statistics for the last hours
        stat("SELECT o.order_id, o.status, o.total_amount FROM sales.orders o WHERE o.customer_id = ?", "SELECT", orders, ora, "sales", List.of(tb.get("ORDERS")), List.of(), 1_240, 4, 1);
        stat("SELECT c.customer_id, c.email, c.full_name, c.tier FROM sales.customer c WHERE c.customer_id = ?", "SELECT", portal, ora, "sales", List.of(tb.get("CUSTOMER")), List.of(), 3_900, 2, 1);
        stat("INSERT INTO sales.orders (order_id, customer_id, status, total_amount, ordered_at) VALUES (?, ?, ?, ?, ?)", "INSERT", orders, ora, "sales", List.of(), List.of(tb.get("ORDERS")), 610, 7, 1);
        stat("UPDATE sales.inventory SET on_hand = on_hand - ?, updated_at = ? WHERE product_id = ? AND warehouse = ?", "UPDATE", inv, ora, "inventory", List.of(), List.of(tb.get("INVENTORY")), 505, 9, 1);
        stat("BEGIN sales.order_pkg.place_order(?, ?, ?); END;", "CALL", orders, ora, "sales", List.of(tb.get("CUSTOMER"), tb.get("PRODUCT")), List.of(tb.get("ORDERS"), tb.get("ORDER_ITEM"), tb.get("INVENTORY"), tb.get("AUDIT_LOG")), 520, 38, 1);
        stat("SELECT p.payment_id, p.amount, p.method FROM sales.payment p WHERE p.order_id = ?", "SELECT", pay, ora, "payments", List.of(tb.get("PAYMENT")), List.of(), 610, 3, 1);
        stat("SELECT o.order_id, o.ordered_at, SUM(i.quantity * i.unit_price) FROM sales.orders o JOIN sales.order_item i ON i.order_id = o.order_id WHERE o.ordered_at >= ? GROUP BY o.order_id, o.ordered_at", "SELECT", rpt, ora, "sales", List.of(tb.get("ORDERS"), tb.get("ORDER_ITEM")), List.of(), 64, 2_900, 1200);
        stat("SELECT * FROM sales.v_order_summary WHERE order_id = ?", "SELECT", rpt, ora, "sales", List.of(tb.get("V_ORDER_SUMMARY")), List.of(), 31, 240, 1);
        stat("SELECT c.email FROM sales.customer c WHERE c.customer_id = ?", "SELECT", pay, ora, "sales", List.of(tb.get("CUSTOMER")), List.of(), 70, 2, 1);

        governance.evaluate();
        log.info("Demo dataset loaded");
        return new SeedResult(t.size(), a.size(), 2, 3, tb.size() + 3, r.size(), n);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Team team(String name, String display, String description, String tag) {
        Team t = new Team();
        t.setName(name); t.setDisplayName(display); t.setDescription(description);
        t.setContacts(new ArrayList<>(List.of(name + "@example.org")));
        t.setTags(new ArrayList<>(List.of(tag)));
        return teams.upsertByName(t);
    }

    private static IdentityRules rules(List<String> cidrs, List<String> programs, List<String> machines, List<String> aliases, List<String> pgNames) {
        IdentityRules r = new IdentityRules();
        r.setCidrs(new ArrayList<>(cidrs)); r.setProgramNames(new ArrayList<>(programs)); r.setMachinePatterns(new ArrayList<>(machines));
        r.setServiceAliases(new ArrayList<>(aliases)); r.setPgApplicationNames(new ArrayList<>(pgNames));
        return r;
    }

    private Application app(String name, String display, Team team, Enums.ApplicationKind kind, Enums.Runtime runtime, IdentityRules rules, List<String> tags) {
        Application a = new Application();
        a.setName(name); a.setDisplayName(display); a.setTeamId(team.getId()); a.setKind(kind); a.setRuntime(runtime);
        a.setDescription(display + " (demo)"); a.setIdentityRules(rules); a.setTags(new ArrayList<>(tags));
        return applications.upsertByName(a);
    }

    private DatabaseInstance database(String name, Enums.Engine engine, String host, int port, String service, Credential cred, int maxPhysical, List<String> schemas, String description) {
        DatabaseInstance d = new DatabaseInstance();
        d.setName(name); d.setEngine(engine); d.setHost(host); d.setPort(port); d.setServiceName(service); d.setCredentialId(cred.getId());
        d.setMaxPhysicalConnections(maxPhysical);
        d.setJdbcProperties(new LinkedHashMap<>(engine == Enums.Engine.ORACLE ? Map.of("oracle.jdbc.ReadTimeout", "60000") : Map.of("ApplicationName", "dbp")));
        CollectorConfig c = new CollectorConfig();
        c.setEnabled(false); c.setDictionaryIntervalSeconds(3600); c.setRuntimeIntervalSeconds(15); c.setSchemas(new ArrayList<>(schemas)); c.setAuditTrail(false);
        d.setCollector(c);
        d.setDescription(description);
        d.setTags(new ArrayList<>(List.of("demo")));
        return databases.upsertByName(d);
    }

    private static RoutingRule rule(int priority, String appId, String tag, String dbId, boolean readOnly) {
        RoutingRule r = new RoutingRule();
        r.setPriority(priority); r.setApplicationId(appId); r.setTag(tag); r.setDatabaseId(dbId); r.setReadOnly(readOnly); r.setEnabled(true);
        return r;
    }

    private Datasource datasource(String name, String display, Team owner, Enums.DatasourceState state, DatabaseInstance current, DatabaseInstance target, int maxConn, List<RoutingRule> rules) {
        Datasource d = new Datasource();
        d.setName(name); d.setDisplayName(display); d.setOwnerTeamId(owner.getId()); d.setState(state);
        d.setCurrentDatabaseId(current.getId()); d.setTargetDatabaseId(target == null ? null : target.getId());
        PoolPolicy p = new PoolPolicy();
        p.setMaxConnections(maxConn); p.setMinIdle(2);
        d.setPoolPolicy(p);
        d.setRoutingRules(new ArrayList<>(rules));
        d.setDescription(display + " (demo)");
        d.setTags(new ArrayList<>(List.of("demo")));
        return datasources.upsertByName(d);
    }

    private void grant(Application app, Datasource ds, int logical, int proxy, boolean readOnly) {
        AccessGrant g = new AccessGrant();
        g.setApplicationId(app.getId()); g.setDatasourceId(ds.getId()); g.setMaxLogicalConnections(logical); g.setMaxProxyConnections(proxy);
        g.setReadOnly(readOnly); g.setEnabled(true); g.setNote("demo");
        grants.upsert(g);
    }

    private record Col(String name, String type, Integer len, Integer scale, boolean nullable, Enums.Classification cls) {}
    private static Col col(String name, String type, Integer len, Integer scale, boolean nullable, Enums.Classification cls) { return new Col(name, type, len, scale, nullable, cls); }

    private DbTable table(DatabaseInstance db, String schema, String name, Enums.TableKind kind, Team owner, Application producer, Enums.Classification cls, Long rows, String description, List<Col> cols) {
        DbTable t = tables.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(db.getId(), schema, name).orElseGet(() -> {
            DbTable n = new DbTable();
            n.setId(Ids.newId()); n.setDatabaseId(db.getId()); n.setSchema(schema); n.setName(name); n.setFirstSeenAt(Instant.now());
            return n;
        });
        t.setKind(kind);
        t.setOwnerTeamId(owner == null ? null : owner.getId());
        t.setOwnerSource(owner == null ? Enums.OwnerSource.NONE : Enums.OwnerSource.DECLARED);
        t.setOwnerConfirmed(owner != null);
        t.setProducerApplicationId(producer == null ? null : producer.getId());
        t.setProducerSource(producer == null ? null : Enums.OwnerSource.DECLARED);
        t.setClassification(cls);
        t.setRowCountEstimate(rows);
        t.setDescription(description);
        t.setLastSeenAt(Instant.now());
        t.setLastDdlAt(Instant.now().minus(Duration.ofDays(30)));
        t.setDiscovered(false);
        t.setTags(new ArrayList<>(List.of("demo")));
        t = tables.save(t);
        int pos = 1;
        for (Col c : cols) {
            final int p = pos++;
            DbColumn col = columns.findByTableIdAndNameIgnoreCase(t.getId(), c.name()).orElseGet(() -> { DbColumn n = new DbColumn(); n.setId(Ids.newId()); return n; });
            col.setTableId(t.getId()); col.setName(c.name()); col.setPosition(p); col.setDataType(c.type());
            if (c.len() != null && c.scale() != null) { col.setPrecision(c.len()); col.setScale(c.scale()); } else col.setLength(c.len());
            col.setNullable(c.nullable());
            if (c.cls() != null) col.setClassification(c.cls());
            columns.save(col);
        }
        return t;
    }

    private void migration(DbTable t, DatabaseInstance target, String schema, String name, Enums.MigrationState state) {
        TableMigration m = new TableMigration();
        m.setTargetDatabaseId(target.getId()); m.setTargetSchema(schema); m.setTargetName(name); m.setState(state);
        t.setMigration(m);
        tables.save(t);
    }

    private Routine routine(DatabaseInstance db, String schema, String name, Enums.RoutineKind kind, DbTable triggerTable, String event, Team owner) {
        Routine r = routines.findByDatabaseIdAndSchemaIgnoreCaseAndNameIgnoreCase(db.getId(), schema, name).orElseGet(() -> {
            Routine n = new Routine();
            n.setId(Ids.newId()); n.setDatabaseId(db.getId()); n.setSchema(schema); n.setName(name); n.setFirstSeenAt(Instant.now());
            return n;
        });
        r.setKind(kind);
        r.setTriggerTableId(triggerTable == null ? null : triggerTable.getId());
        r.setTriggerEvent(event);
        r.setOwnerTeamId(owner == null ? null : owner.getId());
        r.setStatus(Enums.RoutineStatus.VALID);
        r.setLastSeenAt(Instant.now());
        r.setLastDdlAt(Instant.now().minus(Duration.ofDays(30)));
        r.setDiscovered(false);
        return routines.save(r);
    }

    private void dep(Routine from, Routine to, DependencyKind kind, double confidence) {
        catalogue.upsertDependency(ObjectType.ROUTINE, from.getId(), ObjectType.ROUTINE, to.getId(), kind, DependencySource.DICTIONARY, confidence);
    }
    private void dep(Routine from, DbTable to, DependencyKind kind, double confidence) {
        catalogue.upsertDependency(ObjectType.ROUTINE, from.getId(), ObjectType.TABLE, to.getId(), kind, DependencySource.DICTIONARY, confidence);
    }
    private void depT(DbTable from, Routine to, DependencyKind kind) {
        catalogue.upsertDependency(ObjectType.TABLE, from.getId(), ObjectType.ROUTINE, to.getId(), kind, DependencySource.DICTIONARY, 1.0);
    }
    private void fk(DbTable from, DbTable to) {
        catalogue.upsertDependency(ObjectType.TABLE, from.getId(), ObjectType.TABLE, to.getId(), DependencyKind.FOREIGN_KEY, DependencySource.DICTIONARY, 1.0);
    }
    private void pgDep(DbTable from, DbTable to) { fk(from, to); }
    private void viewDep(DbTable view, DbTable base) {
        catalogue.upsertDependency(ObjectType.TABLE, view.getId(), ObjectType.TABLE, base.getId(), DependencyKind.REFERENCES, DependencySource.DICTIONARY, 1.0);
    }

    private int rel(Application app, DbTable t, RelationshipKind kind, RelationshipSource source, long count, Instant last, String via) {
        return relT(app, t.getId(), kind, source, count, last, via);
    }
    private int rel(Application app, Routine r, RelationshipKind kind, RelationshipSource source, long count, Instant last, String via) {
        return upsertRel(app.getId(), ObjectType.ROUTINE, r.getId(), kind, source, count, last, via);
    }
    private int relT(Application app, String tableId, RelationshipKind kind, RelationshipSource source, long count, Instant last, String via) {
        return upsertRel(app.getId(), ObjectType.TABLE, tableId, kind, source, count, last, via);
    }
    private int upsertRel(String appId, ObjectType type, String objectId, RelationshipKind kind, RelationshipSource source, long count, Instant last, String via) {
        Optional<Relationship> existing = relationships.findExisting(appId, type, objectId, kind, source, via);
        Relationship r = existing.orElseGet(() -> {
            Relationship n = new Relationship();
            n.setId(Ids.newId()); n.setApplicationId(appId); n.setObjectType(type); n.setObjectId(objectId); n.setKind(kind); n.setSource(source);
            n.setViaRoutineId(via); n.setFirstSeenAt(last.minus(Duration.ofDays(21))); n.setConfidence(Enums.confidenceOf(source));
            return n;
        });
        r.setQueryCount(count);
        r.setLastSeenAt(last);
        relationships.save(r);
        return existing.isPresent() ? 0 : 1;
    }
    private void declared(Application app, DbTable t, RelationshipKind kind) {
        Relationship r = new Relationship();
        r.setApplicationId(app.getId()); r.setObjectType(ObjectType.TABLE); r.setObjectId(t.getId()); r.setKind(kind);
        catalogue.declareRelationship(r);
    }

    private void stat(String sql, String op, Application app, DatabaseInstance db, String ds, List<DbTable> reads, List<DbTable> writes, long perHour, long avgMs, long rows) {
        String hash = SecretCipher.sha256Hex(sql);
        List<Map<String, String>> refs = new ArrayList<>();
        reads.forEach(t -> refs.add(Map.of("tableId", t.getId(), "access", "READ")));
        writes.forEach(t -> refs.add(Map.of("tableId", t.getId(), "access", "WRITE")));
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        for (int h = 0; h < 6; h++) {
            Instant bucket = hour.minus(Duration.ofHours(h));
            long cnt = Math.max(1, perHour - h * (perHour / 10));
            QueryStat s = queryStats.findBucket(hash, app.getId(), db.getId(), bucket).orElseGet(() -> {
                QueryStat n = new QueryStat();
                n.setId(Ids.newId()); n.setSqlHash(hash); n.setSqlNormalized(sql); n.setOperation(op); n.setApplicationId(app.getId()); n.setDatabaseId(db.getId());
                n.setDatasourceName(ds); n.setBucketStart(bucket);
                return n;
            });
            s.setExecCount(cnt); s.setTotalDurationMs(cnt * avgMs); s.setMaxDurationMs(avgMs * 12); s.setRowCount(cnt * rows); s.setErrorCount(h == 0 ? 0 : cnt / 500);
            s.setDurationSamples(Json.write(List.of(avgMs, avgMs, avgMs * 2, avgMs / 2 + 1, avgMs * 12, avgMs, avgMs, avgMs * 3, avgMs, avgMs)));
            s.setTablesJson(Json.write(refs));
            s.setLastSeenAt(h == 0 ? Instant.now().minusSeconds(30) : bucket.plus(Duration.ofMinutes(59)));
            queryStats.save(s);
        }
    }
}
