-- =====================================================================================
-- 00_databases.sql  --  Roles and databases for the PostgreSQL demo (postgres:17 image)
--
-- Executed by the official image entrypoint (psql, superuser, database $POSTGRES_DB) once, when
-- the data volume is empty. Files run in name order.
--
--   database dbp    control-plane metadata store (Spring profile "postgres", DBP_DB_URL)
--   database sales  retail demo schema (schema "sales"), equivalent of the Oracle SALES schema
--
--   role dbp            owner of database dbp
--   role sales          owner of schema sales (never used by applications)
--   role sales_app      shared application account (gateway pools, examples in postgres-direct mode)
--   role dbp_collector  read-only collector account: pg_monitor (pg_stat_activity, pg_stat_statements)
--
-- Passwords are demo defaults; 05_passwords.sh overrides them from the container environment.
-- =====================================================================================
\set ON_ERROR_STOP on

CREATE ROLE dbp           LOGIN PASSWORD 'dbp'                 NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE sales         LOGIN PASSWORD 'Sales#Demo2026'      NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE sales_app     LOGIN PASSWORD 'SalesApp#Demo2026'   NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE dbp_collector LOGIN PASSWORD 'Collector#Demo2026'  NOSUPERUSER NOCREATEDB NOCREATEROLE;

-- pg_monitor = pg_read_all_settings + pg_read_all_stats + pg_stat_scan_tables:
-- full pg_stat_activity (query text, client_addr, client_port, application_name) and
-- pg_stat_statements. The information_schema / pg_catalog dictionary is readable by any role.
GRANT pg_monitor TO dbp_collector;

CREATE DATABASE dbp   OWNER dbp   ENCODING 'UTF8' TEMPLATE template0;
CREATE DATABASE sales OWNER sales ENCODING 'UTF8' TEMPLATE template0;

-- Make the application and collector roles able to connect to the demo database.
GRANT CONNECT ON DATABASE sales TO sales_app, dbp_collector;
REVOKE CONNECT ON DATABASE sales FROM PUBLIC;

-- Statement statistics are handy for the collector (requires shared_preload_libraries, see compose).
\connect sales
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
