-- =====================================================================================
-- 01_schema.sql  --  SQL Server variant of the retail demo (schema only, for completeness)
--
-- Not wired into deploy/docker-compose.yml by default. To use it:
--   docker run -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD='YourStrong!Passw0rd' -p 1434:1433 \
--          mcr.microsoft.com/mssql/server:2022-latest
--   sqlcmd -S localhost,1434 -U sa -P 'YourStrong!Passw0rd' -i 01_schema.sql
-- then register the database in the control plane (engine MSSQL, port 1433, database "sales")
-- and point the proxy's SQL Server listener (1433) at it.
-- =====================================================================================
IF DB_ID('sales') IS NULL
    CREATE DATABASE sales;
GO
USE sales;
GO

IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = 'sales')
    EXEC('CREATE SCHEMA sales');
GO

IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = 'sales_app')
    CREATE LOGIN sales_app WITH PASSWORD = 'SalesApp#Demo2026', CHECK_POLICY = OFF;
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = 'sales_app')
    CREATE USER sales_app FOR LOGIN sales_app WITH DEFAULT_SCHEMA = sales;
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = 'dbp_collector')
    CREATE LOGIN dbp_collector WITH PASSWORD = 'Collector#Demo2026', CHECK_POLICY = OFF;
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = 'dbp_collector')
    CREATE USER dbp_collector FOR LOGIN dbp_collector;
-- collector: dictionary + DMVs (sys.dm_exec_sessions, sys.dm_exec_requests, sys.dm_exec_sql_text)
GRANT VIEW DEFINITION TO dbp_collector;
GRANT VIEW SERVER STATE TO dbp_collector;
GO

CREATE TABLE sales.customer (
    id            bigint IDENTITY(1,1) NOT NULL CONSTRAINT customer_pk PRIMARY KEY,
    email         nvarchar(200) NOT NULL CONSTRAINT customer_email_uk UNIQUE,
    first_name    nvarchar(100) NOT NULL,
    last_name     nvarchar(100) NOT NULL,
    country_code  char(2)       NOT NULL,
    status        varchar(20)   NOT NULL CONSTRAINT customer_status_df DEFAULT 'ACTIVE'
                  CONSTRAINT customer_status_ck CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    created_at    datetime2(3)  NOT NULL CONSTRAINT customer_created_df DEFAULT SYSUTCDATETIME()
);

CREATE TABLE sales.product (
    id            bigint IDENTITY(1,1) NOT NULL CONSTRAINT product_pk PRIMARY KEY,
    sku           varchar(40)   NOT NULL CONSTRAINT product_sku_uk UNIQUE,
    name          nvarchar(200) NOT NULL,
    category      varchar(60)   NOT NULL,
    unit_price    decimal(12,2) NOT NULL CONSTRAINT product_price_ck CHECK (unit_price >= 0),
    active        bit           NOT NULL CONSTRAINT product_active_df DEFAULT 1,
    created_at    datetime2(3)  NOT NULL CONSTRAINT product_created_df DEFAULT SYSUTCDATETIME()
);

CREATE SEQUENCE sales.order_no_seq AS bigint START WITH 100000 INCREMENT BY 1 CACHE 100;

CREATE TABLE sales.orders (
    id            bigint IDENTITY(1,1) NOT NULL CONSTRAINT orders_pk PRIMARY KEY,
    order_no      varchar(20)   NOT NULL CONSTRAINT orders_no_uk UNIQUE,
    customer_id   bigint        NOT NULL CONSTRAINT orders_customer_fk REFERENCES sales.customer (id),
    order_date    datetime2(3)  NOT NULL CONSTRAINT orders_date_df DEFAULT SYSUTCDATETIME(),
    status        varchar(20)   NOT NULL CONSTRAINT orders_status_df DEFAULT 'NEW'
                  CONSTRAINT orders_status_ck CHECK (status IN ('NEW', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    currency_code char(3)       NOT NULL CONSTRAINT orders_currency_df DEFAULT 'EUR',
    total_amount  decimal(14,2) NOT NULL CONSTRAINT orders_total_df DEFAULT 0,
    updated_at    datetime2(3)  NOT NULL CONSTRAINT orders_updated_df DEFAULT SYSUTCDATETIME()
);
CREATE INDEX orders_customer_ix ON sales.orders (customer_id);
CREATE INDEX orders_date_ix     ON sales.orders (order_date);

CREATE TABLE sales.order_item (
    id            bigint IDENTITY(1,1) NOT NULL CONSTRAINT order_item_pk PRIMARY KEY,
    order_id      bigint        NOT NULL CONSTRAINT order_item_order_fk REFERENCES sales.orders (id) ON DELETE CASCADE,
    product_id    bigint        NOT NULL CONSTRAINT order_item_product_fk REFERENCES sales.product (id),
    qty           int           NOT NULL CONSTRAINT order_item_qty_ck CHECK (qty > 0),
    unit_price    decimal(12,2) NOT NULL,
    line_total    decimal(14,2) NOT NULL,
    CONSTRAINT order_item_order_product_uk UNIQUE (order_id, product_id)
);
CREATE INDEX order_item_product_ix ON sales.order_item (product_id);

CREATE TABLE sales.inventory (
    id             bigint IDENTITY(1,1) NOT NULL CONSTRAINT inventory_pk PRIMARY KEY,
    product_id     bigint       NOT NULL CONSTRAINT inventory_product_fk REFERENCES sales.product (id),
    warehouse_code varchar(10)  NOT NULL CONSTRAINT inventory_wh_df DEFAULT 'MAIN',
    qty_on_hand    int          NOT NULL CONSTRAINT inventory_onhand_df DEFAULT 0,
    qty_reserved   int          NOT NULL CONSTRAINT inventory_reserved_df DEFAULT 0,
    updated_at     datetime2(3) NOT NULL CONSTRAINT inventory_updated_df DEFAULT SYSUTCDATETIME(),
    CONSTRAINT inventory_product_uk UNIQUE (product_id, warehouse_code),
    CONSTRAINT inventory_qty_ck CHECK (qty_on_hand >= 0 AND qty_reserved >= 0)
);

CREATE TABLE sales.payment (
    id            bigint IDENTITY(1,1) NOT NULL CONSTRAINT payment_pk PRIMARY KEY,
    order_id      bigint        NOT NULL CONSTRAINT payment_order_fk REFERENCES sales.orders (id),
    amount        decimal(14,2) NOT NULL,
    method        varchar(20)   NOT NULL CONSTRAINT payment_method_ck CHECK (method IN ('CARD', 'TRANSFER', 'WALLET', 'INVOICE')),
    status        varchar(20)   NOT NULL CONSTRAINT payment_status_df DEFAULT 'PENDING'
                  CONSTRAINT payment_status_ck CHECK (status IN ('PENDING', 'CAPTURED', 'REFUNDED', 'FAILED')),
    paid_at       datetime2(3),
    created_at    datetime2(3)  NOT NULL CONSTRAINT payment_created_df DEFAULT SYSUTCDATETIME()
);
CREATE INDEX payment_order_ix ON sales.payment (order_id);

CREATE TABLE sales.audit_log (
    id             bigint IDENTITY(1,1) NOT NULL CONSTRAINT audit_log_pk PRIMARY KEY,
    table_name     varchar(30)   NOT NULL,
    row_id         bigint        NOT NULL,
    action         varchar(10)   NOT NULL,
    old_status     varchar(20),
    new_status     varchar(20),
    changed_by     nvarchar(128) NOT NULL,
    client_program nvarchar(128),
    client_module  nvarchar(128),
    changed_at     datetime2(3)  NOT NULL CONSTRAINT audit_log_changed_df DEFAULT SYSUTCDATETIME()
);
CREATE INDEX audit_log_changed_ix ON sales.audit_log (changed_at);
GO

-- ---------------------------------------------------------------------------
-- Trigger: ORDERS -> AUDIT_LOG (statement-level trigger, SQL Server has no row triggers)
-- ---------------------------------------------------------------------------
CREATE OR ALTER TRIGGER sales.trg_orders_audit
ON sales.orders
AFTER INSERT, UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    INSERT INTO sales.audit_log (table_name, row_id, action, old_status, new_status, changed_by, client_program, client_module)
    SELECT 'ORDERS',
           i.id,
           CASE WHEN d.id IS NULL THEN 'INSERT' ELSE 'UPDATE' END,
           d.status,
           i.status,
           SUSER_SNAME(),
           APP_NAME(),
           HOST_NAME()
      FROM inserted i
      LEFT JOIN deleted d ON d.id = i.id
     WHERE d.id IS NULL OR ISNULL(d.status, '~') <> ISNULL(i.status, '~');
END;
GO

-- ---------------------------------------------------------------------------
-- Trigger: ORDER_ITEM -> INVENTORY
-- ---------------------------------------------------------------------------
CREATE OR ALTER TRIGGER sales.trg_order_item_stock
ON sales.order_item
AFTER INSERT
AS
BEGIN
    SET NOCOUNT ON;
    UPDATE inv
       SET qty_on_hand  = inv.qty_on_hand - i.qty,
           qty_reserved = CASE WHEN inv.qty_reserved - i.qty < 0 THEN 0 ELSE inv.qty_reserved - i.qty END,
           updated_at   = SYSUTCDATETIME()
      FROM sales.inventory inv
      JOIN inserted i ON i.product_id = inv.product_id
     WHERE inv.warehouse_code = 'MAIN';
END;
GO

-- ---------------------------------------------------------------------------
-- Stored procedure: equivalent of ORDER_PKG.PLACE_ORDER
-- ---------------------------------------------------------------------------
CREATE OR ALTER PROCEDURE sales.usp_place_order
    @customer_id bigint,
    @product_id  bigint,
    @qty         int,
    @order_id    bigint OUTPUT
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    DECLARE @price decimal(12,2), @available int, @total decimal(14,2), @spend decimal(14,2), @discount decimal(4,2);

    IF @qty IS NULL OR @qty <= 0
        THROW 50012, 'Quantity must be positive', 1;

    BEGIN TRANSACTION;

    SELECT @available = qty_on_hand - qty_reserved
      FROM sales.inventory WITH (UPDLOCK, ROWLOCK)
     WHERE product_id = @product_id AND warehouse_code = 'MAIN';
    IF @available IS NULL
        THROW 50011, 'No inventory row for product', 1;
    IF @available < @qty
        THROW 50010, 'Insufficient stock', 1;

    UPDATE sales.inventory SET qty_reserved = qty_reserved + @qty, updated_at = SYSUTCDATETIME()
     WHERE product_id = @product_id AND warehouse_code = 'MAIN';

    SELECT @price = unit_price FROM sales.product WHERE id = @product_id AND active = 1;
    IF @price IS NULL
        THROW 50020, 'Unknown or inactive product', 1;

    SELECT @spend = ISNULL(SUM(p.amount), 0)
      FROM sales.payment p JOIN sales.orders o ON o.id = p.order_id
     WHERE o.customer_id = @customer_id AND p.status = 'CAPTURED'
       AND p.paid_at >= DATEADD(month, -12, SYSUTCDATETIME());
    SET @discount = CASE WHEN @spend >= 5000 THEN 0.10 WHEN @spend >= 1000 THEN 0.05 ELSE 0 END;
    SET @total = ROUND(@price * @qty * (1 - @discount), 2);

    INSERT INTO sales.orders (order_no, customer_id, status, total_amount)
    VALUES ('SO-' + CAST(NEXT VALUE FOR sales.order_no_seq AS varchar(20)), @customer_id, 'NEW', @total);
    SET @order_id = SCOPE_IDENTITY();

    INSERT INTO sales.order_item (order_id, product_id, qty, unit_price, line_total)
    VALUES (@order_id, @product_id, @qty, @price, @total);

    INSERT INTO sales.payment (order_id, amount, method, status)
    VALUES (@order_id, @total, 'CARD', 'PENDING');

    COMMIT TRANSACTION;
END;
GO

GRANT SELECT, INSERT, UPDATE, DELETE ON SCHEMA::sales TO sales_app;
GRANT EXECUTE ON SCHEMA::sales TO sales_app;
GO
