-- =====================================================================================
-- 20_plpgsql.sql  --  Routines and triggers of the "sales" schema (PostgreSQL 17)
--
-- Equivalents of demo/sql/oracle/20_plsql.sql. PostgreSQL has no packages, so the package members
-- are prefixed: ORDER_PKG.PLACE_ORDER -> sales.order_pkg_place_order.
--
--   sales.get_customer_tier(bigint)                      function  -> customer, orders, payment
--   sales.reserve_stock(bigint, int)                      procedure -> inventory
--   sales.calc_total(bigint, bigint, int)                 function  -> product, get_customer_tier
--   sales.order_pkg_place_order(bigint, bigint, int, INOUT bigint) procedure -> reserve_stock, calc_total, orders, order_item, payment
--   sales.order_pkg_cancel_order(bigint)                  procedure -> orders, order_item, inventory, payment
--   sales.get_orders_for_customer(bigint) RETURNS refcursor          -> orders, order_item, payment
--   sales.get_orders_for_customer_rows(bigint) RETURNS TABLE (SETOF variant of the same query)
--   trigger trg_orders_audit      ON sales.orders     -> audit_log
--   trigger trg_order_item_stock  ON sales.order_item -> inventory
--
-- Trigger functions are SECURITY DEFINER (owner sales) so that, like Oracle definer-rights
-- triggers, the application role does not need INSERT on audit_log.
-- =====================================================================================
\set ON_ERROR_STOP on
\connect sales
SET ROLE sales;
SET search_path TO sales, public;

-- ---------------------------------------------------------------------------
-- get_customer_tier
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sales.get_customer_tier(p_customer_id bigint)
RETURNS varchar
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    l_exists integer;
    l_spend  numeric;
BEGIN
    SELECT count(*) INTO l_exists FROM sales.customer WHERE id = p_customer_id;
    IF l_exists = 0 THEN
        RAISE EXCEPTION 'Unknown customer %', p_customer_id USING ERRCODE = 'P0002';
    END IF;

    SELECT coalesce(sum(p.amount), 0)
      INTO l_spend
      FROM sales.payment p
      JOIN sales.orders  o ON o.id = p.order_id
     WHERE o.customer_id = p_customer_id
       AND p.status = 'CAPTURED'
       AND p.paid_at >= now() - interval '12 months';

    RETURN CASE
               WHEN l_spend >= 5000 THEN 'GOLD'
               WHEN l_spend >= 1000 THEN 'SILVER'
               ELSE 'BRONZE'
           END;
END;
$$;

-- ---------------------------------------------------------------------------
-- reserve_stock
-- ---------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sales.reserve_stock(p_product_id bigint, p_qty integer)
LANGUAGE plpgsql
AS $$
DECLARE
    l_available integer;
BEGIN
    IF p_qty IS NULL OR p_qty <= 0 THEN
        RAISE EXCEPTION 'Quantity must be positive' USING ERRCODE = '22023';
    END IF;

    SELECT qty_on_hand - qty_reserved
      INTO l_available
      FROM sales.inventory
     WHERE product_id = p_product_id
       AND warehouse_code = 'MAIN'
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'No inventory row for product %', p_product_id USING ERRCODE = 'P0002';
    END IF;
    IF l_available < p_qty THEN
        RAISE EXCEPTION 'Insufficient stock for product %: available %, requested %',
            p_product_id, l_available, p_qty USING ERRCODE = 'P0001';
    END IF;

    UPDATE sales.inventory
       SET qty_reserved = qty_reserved + p_qty,
           updated_at   = now()
     WHERE product_id = p_product_id
       AND warehouse_code = 'MAIN';
END;
$$;

-- ---------------------------------------------------------------------------
-- calc_total  (ORDER_PKG.CALC_TOTAL)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sales.calc_total(p_customer_id bigint, p_product_id bigint, p_qty integer)
RETURNS numeric
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    l_price    numeric;
    l_tier     varchar;
    l_discount numeric;
BEGIN
    SELECT unit_price INTO l_price FROM sales.product WHERE id = p_product_id AND active = 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Unknown or inactive product %', p_product_id USING ERRCODE = 'P0002';
    END IF;

    l_tier     := sales.get_customer_tier(p_customer_id);
    l_discount := CASE l_tier WHEN 'GOLD' THEN 0.10 WHEN 'SILVER' THEN 0.05 ELSE 0 END;
    RETURN round(l_price * p_qty * (1 - l_discount), 2);
END;
$$;

-- ---------------------------------------------------------------------------
-- order_pkg_place_order  (ORDER_PKG.PLACE_ORDER) -- INOUT p_order_id plays the OUT parameter
-- ---------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sales.order_pkg_place_order(p_customer_id bigint,
                                                        p_product_id  bigint,
                                                        p_qty         integer,
                                                        INOUT p_order_id bigint)
LANGUAGE plpgsql
AS $$
DECLARE
    l_total numeric;
    l_price numeric;
BEGIN
    CALL sales.reserve_stock(p_product_id, p_qty);
    l_total := sales.calc_total(p_customer_id, p_product_id, p_qty);

    SELECT unit_price INTO l_price FROM sales.product WHERE id = p_product_id;

    INSERT INTO sales.orders (order_no, customer_id, status, total_amount)
    VALUES ('SO-' || nextval('sales.order_no_seq'), p_customer_id, 'NEW', l_total)
    RETURNING id INTO p_order_id;

    -- fires trg_order_item_stock
    INSERT INTO sales.order_item (order_id, product_id, qty, unit_price, line_total)
    VALUES (p_order_id, p_product_id, p_qty, l_price, l_total);

    INSERT INTO sales.payment (order_id, amount, method, status)
    VALUES (p_order_id, l_total, 'CARD', 'PENDING');
END;
$$;

-- ---------------------------------------------------------------------------
-- order_pkg_cancel_order  (ORDER_PKG.CANCEL_ORDER)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sales.order_pkg_cancel_order(p_order_id bigint)
LANGUAGE plpgsql
AS $$
DECLARE
    l_rows integer;
BEGIN
    UPDATE sales.orders
       SET status = 'CANCELLED', updated_at = now()
     WHERE id = p_order_id AND status IN ('NEW', 'PAID');
    GET DIAGNOSTICS l_rows = ROW_COUNT;
    IF l_rows = 0 THEN
        RAISE EXCEPTION 'Order % does not exist or cannot be cancelled', p_order_id USING ERRCODE = 'P0001';
    END IF;

    UPDATE sales.inventory i
       SET qty_on_hand = i.qty_on_hand + oi.qty,
           updated_at  = now()
      FROM (SELECT product_id, sum(qty) AS qty FROM sales.order_item WHERE order_id = p_order_id GROUP BY product_id) oi
     WHERE i.product_id = oi.product_id AND i.warehouse_code = 'MAIN';

    UPDATE sales.payment SET status = 'REFUNDED' WHERE order_id = p_order_id AND status = 'CAPTURED';
    UPDATE sales.payment SET status = 'FAILED'   WHERE order_id = p_order_id AND status = 'PENDING';
END;
$$;

-- ---------------------------------------------------------------------------
-- get_orders_for_customer: refcursor (needs an open transaction on the client side)
-- and a SETOF variant for clients that prefer plain result sets.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sales.get_orders_for_customer(p_customer_id bigint)
RETURNS refcursor
LANGUAGE plpgsql
AS $$
DECLARE
    c refcursor;
BEGIN
    OPEN c FOR
        SELECT o.id,
               o.order_no,
               o.order_date,
               o.status,
               o.total_amount,
               o.currency_code,
               (SELECT count(*)      FROM sales.order_item oi WHERE oi.order_id = o.id) AS item_count,
               (SELECT max(p.status) FROM sales.payment    p  WHERE p.order_id  = o.id) AS payment_status
          FROM sales.orders o
         WHERE o.customer_id = p_customer_id
         ORDER BY o.order_date DESC;
    RETURN c;
END;
$$;

CREATE OR REPLACE FUNCTION sales.get_orders_for_customer_rows(p_customer_id bigint)
RETURNS TABLE (id bigint, order_no varchar, order_date timestamptz, status varchar,
               total_amount numeric, currency_code char(3), item_count bigint, payment_status varchar)
LANGUAGE sql
STABLE
AS $$
    SELECT o.id, o.order_no, o.order_date, o.status, o.total_amount, o.currency_code,
           (SELECT count(*)      FROM sales.order_item oi WHERE oi.order_id = o.id),
           (SELECT max(p.status) FROM sales.payment    p  WHERE p.order_id  = o.id)
      FROM sales.orders o
     WHERE o.customer_id = p_customer_id
     ORDER BY o.order_date DESC;
$$;

-- ---------------------------------------------------------------------------
-- Triggers
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sales.fn_orders_audit()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = sales, pg_temp
AS $$
BEGIN
    IF TG_OP = 'INSERT' OR coalesce(OLD.status, '~') <> coalesce(NEW.status, '~') THEN
        INSERT INTO sales.audit_log (table_name, row_id, action, old_status, new_status,
                                     changed_by, client_program, client_module)
        VALUES ('ORDERS',
                NEW.id,
                TG_OP,
                CASE WHEN TG_OP = 'UPDATE' THEN OLD.status END,
                NEW.status,
                session_user,
                current_setting('application_name', true),
                coalesce(inet_client_addr()::text, 'local'));
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_orders_audit
AFTER INSERT OR UPDATE ON sales.orders
FOR EACH ROW EXECUTE FUNCTION sales.fn_orders_audit();

CREATE OR REPLACE FUNCTION sales.fn_order_item_stock()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = sales, pg_temp
AS $$
BEGIN
    UPDATE sales.inventory
       SET qty_on_hand  = qty_on_hand - NEW.qty,
           qty_reserved = greatest(qty_reserved - NEW.qty, 0),
           updated_at   = now()
     WHERE product_id = NEW.product_id
       AND warehouse_code = 'MAIN';
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_order_item_stock
AFTER INSERT ON sales.order_item
FOR EACH ROW EXECUTE FUNCTION sales.fn_order_item_stock();

RESET ROLE;
