-- =====================================================================================
-- 40_grants_app.sql  --  Privileges for the shared application role and the collector role
--
-- search_path is set per role in the database, so applications can use unqualified names
-- ("SELECT ... FROM orders", "CALL order_pkg_place_order(...)") exactly like the Oracle synonyms.
-- =====================================================================================
\set ON_ERROR_STOP on
\connect sales

GRANT USAGE ON SCHEMA sales TO sales_app, dbp_collector;

-- Application role: DML on the tables, the sequence, and the routines
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA sales TO sales_app;
REVOKE INSERT, UPDATE, DELETE ON sales.audit_log FROM sales_app;          -- written by SECURITY DEFINER trigger
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA sales TO sales_app;
GRANT EXECUTE ON ALL ROUTINES IN SCHEMA sales TO sales_app;

-- Collector role: dictionary (pg_catalog/information_schema, readable by everyone) and
-- pg_stat_activity / pg_stat_statements through pg_monitor (00_databases.sql). Row-count estimates
-- come from pg_class.reltuples, so no table access is needed. Like the Oracle demo, give it the
-- audit log for comparison with pg_stat_statements.
GRANT SELECT ON sales.audit_log TO dbp_collector;

-- Future objects created by the owner inherit the application grants
ALTER DEFAULT PRIVILEGES FOR ROLE sales IN SCHEMA sales GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES    TO sales_app;
ALTER DEFAULT PRIVILEGES FOR ROLE sales IN SCHEMA sales GRANT USAGE, SELECT                   ON SEQUENCES TO sales_app;
ALTER DEFAULT PRIVILEGES FOR ROLE sales IN SCHEMA sales GRANT EXECUTE                         ON ROUTINES  TO sales_app;

-- Unqualified names resolve to the sales schema (equivalent of Oracle synonyms / CURRENT_SCHEMA)
ALTER ROLE sales_app     IN DATABASE sales SET search_path = sales, public;
ALTER ROLE dbp_collector IN DATABASE sales SET search_path = sales, public;
ALTER ROLE sales         IN DATABASE sales SET search_path = sales, public;
