-- =====================================================================================
-- 01_users.sql  --  Demo accounts for the Database Access Platform (Oracle Database Free 23)
--
-- Executed by the gvenzl/oracle-free container entrypoint as SYS AS SYSDBA against the CDB,
-- therefore the first statement switches to the pluggable database FREEPDB1.
--
-- Accounts
--   SALES          schema owner: tables, PL/SQL, triggers (never used by applications)
--   SALES_APP      shared application account used by the gateway pools and by the
--                  example applications in "direct" and "proxy" mode
--   DBP_COLLECTOR  read-only account for the control-plane collector (data dictionary,
--                  V$ runtime views, unified audit trail)
--
-- Passwords below are demo defaults. 02_passwords.sh (run right after this file) replaces
-- them with the values of SALES_PASSWORD / SALES_APP_PASSWORD / DBP_COLLECTOR_PASSWORD
-- when those environment variables are set on the container.
-- =====================================================================================
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;

-- ---------------------------------------------------------------------------
-- Schema owner
-- ---------------------------------------------------------------------------
CREATE USER SALES IDENTIFIED BY "Sales#Demo2026"
    DEFAULT TABLESPACE USERS
    TEMPORARY TABLESPACE TEMP
    QUOTA UNLIMITED ON USERS;

GRANT CREATE SESSION,
      CREATE TABLE,
      CREATE SEQUENCE,
      CREATE VIEW,
      CREATE PROCEDURE,
      CREATE TRIGGER,
      CREATE TYPE,
      CREATE SYNONYM
   TO SALES;

-- ---------------------------------------------------------------------------
-- Shared application account (gateway pools, examples in direct/proxy mode)
-- Object privileges and synonyms are granted in 40_grants_app.sql.
-- ---------------------------------------------------------------------------
CREATE USER SALES_APP IDENTIFIED BY "SalesApp#Demo2026"
    DEFAULT TABLESPACE USERS
    TEMPORARY TABLESPACE TEMP
    QUOTA 0 ON USERS;

GRANT CREATE SESSION TO SALES_APP;

-- ---------------------------------------------------------------------------
-- Collector privileges, bundled in a role so they can be granted to more than one account.
--   SELECT_CATALOG_ROLE   ALL_*/DBA_* dictionary views (tables, columns, dependencies,
--                         triggers, constraints, source)
--   SELECT ANY DICTIONARY V$SESSION, V$SQL, V$SQL_PLAN, V$SQLAREA ... (the V_$ fixed views)
--   AUDIT_VIEWER          UNIFIED_AUDIT_TRAIL (only used when the audit collector is on)
-- See README.md in this directory for the explicit-grant alternative.
-- ---------------------------------------------------------------------------
CREATE ROLE DBP_COLLECTOR_ROLE;
GRANT SELECT_CATALOG_ROLE TO DBP_COLLECTOR_ROLE;
GRANT SELECT ANY DICTIONARY TO DBP_COLLECTOR_ROLE;
GRANT AUDIT_VIEWER TO DBP_COLLECTOR_ROLE;

CREATE USER DBP_COLLECTOR IDENTIFIED BY "Collector#Demo2026"
    DEFAULT TABLESPACE USERS
    TEMPORARY TABLESPACE TEMP
    QUOTA 0 ON USERS;

GRANT CREATE SESSION TO DBP_COLLECTOR;
GRANT DBP_COLLECTOR_ROLE TO DBP_COLLECTOR;

-- POC compromise: the control plane keeps ONE credential per physical database and uses it
-- for both the gateway pools and the collector (see docs/control-plane-api.md, section 3).
-- So the application account also receives the (read-only) collector role. In a production
-- deployment keep the two accounts separate and give the collector credential to the platform.
GRANT DBP_COLLECTOR_ROLE TO SALES_APP;

-- Demo accounts never expire (the default profile would lock them after 180 days).
CREATE PROFILE DBP_DEMO_PROFILE LIMIT
    PASSWORD_LIFE_TIME UNLIMITED
    FAILED_LOGIN_ATTEMPTS UNLIMITED;
ALTER USER SALES         PROFILE DBP_DEMO_PROFILE;
ALTER USER SALES_APP     PROFILE DBP_DEMO_PROFILE;
ALTER USER DBP_COLLECTOR PROFILE DBP_DEMO_PROFILE;

COMMIT;
EXIT
