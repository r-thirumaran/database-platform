-- =====================================================================================
-- 40_grants_app.sql  --  Object privileges and synonyms for the shared application account
--
-- SALES_APP never owns objects. It receives DML on the SALES tables and EXECUTE on the PL/SQL
-- API, plus private synonyms so that applications can use unqualified names
-- ("SELECT ... FROM ORDERS", "{call ORDER_PKG.PLACE_ORDER(?,?,?,?)}").
--
-- Alternative to synonyms: run  ALTER SESSION SET CURRENT_SCHEMA = SALES  right after connecting.
--   * HikariCP:  spring.datasource.hikari.connection-init-sql=ALTER SESSION SET CURRENT_SCHEMA = SALES
--   * dbp-jdbc:  jdbc:dbp://gateway:7420/sales?schema=SALES  (the gateway re-applies the schema to
--                every physical connection it pins for the session)
-- Both approaches work with the examples; the demo uses synonyms so that no connection
-- initialisation is needed in any mode.
-- =====================================================================================
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;

-- Tables
GRANT SELECT, INSERT, UPDATE, DELETE ON SALES.CUSTOMER   TO SALES_APP;
GRANT SELECT, INSERT, UPDATE, DELETE ON SALES.PRODUCT    TO SALES_APP;
GRANT SELECT, INSERT, UPDATE, DELETE ON SALES.ORDERS     TO SALES_APP;
GRANT SELECT, INSERT, UPDATE, DELETE ON SALES.ORDER_ITEM TO SALES_APP;
GRANT SELECT, INSERT, UPDATE, DELETE ON SALES.INVENTORY  TO SALES_APP;
GRANT SELECT, INSERT, UPDATE, DELETE ON SALES.PAYMENT    TO SALES_APP;
GRANT SELECT                         ON SALES.AUDIT_LOG  TO SALES_APP;   -- written by trigger (definer rights)
GRANT SELECT                         ON SALES.V_CUSTOMER_ORDER_SUMMARY TO SALES_APP;
GRANT SELECT                         ON SALES.ORDER_NO_SEQ TO SALES_APP;

-- PL/SQL API
GRANT EXECUTE ON SALES.ORDER_PKG               TO SALES_APP;
GRANT EXECUTE ON SALES.RESERVE_STOCK           TO SALES_APP;
GRANT EXECUTE ON SALES.GET_CUSTOMER_TIER       TO SALES_APP;
GRANT EXECUTE ON SALES.GET_ORDERS_FOR_CUSTOMER TO SALES_APP;

-- Private synonyms in the SALES_APP schema (created by SYS, owned by SALES_APP)
CREATE OR REPLACE SYNONYM SALES_APP.CUSTOMER                 FOR SALES.CUSTOMER;
CREATE OR REPLACE SYNONYM SALES_APP.PRODUCT                  FOR SALES.PRODUCT;
CREATE OR REPLACE SYNONYM SALES_APP.ORDERS                   FOR SALES.ORDERS;
CREATE OR REPLACE SYNONYM SALES_APP.ORDER_ITEM               FOR SALES.ORDER_ITEM;
CREATE OR REPLACE SYNONYM SALES_APP.INVENTORY                FOR SALES.INVENTORY;
CREATE OR REPLACE SYNONYM SALES_APP.PAYMENT                  FOR SALES.PAYMENT;
CREATE OR REPLACE SYNONYM SALES_APP.AUDIT_LOG                FOR SALES.AUDIT_LOG;
CREATE OR REPLACE SYNONYM SALES_APP.V_CUSTOMER_ORDER_SUMMARY FOR SALES.V_CUSTOMER_ORDER_SUMMARY;
CREATE OR REPLACE SYNONYM SALES_APP.ORDER_NO_SEQ             FOR SALES.ORDER_NO_SEQ;
CREATE OR REPLACE SYNONYM SALES_APP.ORDER_PKG                FOR SALES.ORDER_PKG;
CREATE OR REPLACE SYNONYM SALES_APP.RESERVE_STOCK            FOR SALES.RESERVE_STOCK;
CREATE OR REPLACE SYNONYM SALES_APP.GET_CUSTOMER_TIER        FOR SALES.GET_CUSTOMER_TIER;
CREATE OR REPLACE SYNONYM SALES_APP.GET_ORDERS_FOR_CUSTOMER  FOR SALES.GET_ORDERS_FOR_CUSTOMER;

-- The collector reads only dictionary and V$ views (role DBP_COLLECTOR_ROLE from 01_users.sql).
-- Give it read access to the audit log table too, so the audit collector can be compared with the
-- unified audit trail when 50_audit_policy.sql.optional is enabled.
GRANT SELECT ON SALES.AUDIT_LOG TO DBP_COLLECTOR;

COMMIT;
EXIT
