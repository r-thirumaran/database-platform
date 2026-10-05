-- =====================================================================================
-- 30_data.sql  --  Demo data for the "sales" schema (PostgreSQL 17), same shape as the Oracle set
-- =====================================================================================
\set ON_ERROR_STOP on
\connect sales
SET ROLE sales;
SET search_path TO sales, public;

DO $$
DECLARE
    l_first      text[] := ARRAY['Ada', 'Bruno', 'Chloe', 'Dario', 'Elena', 'Farid', 'Greta', 'Hugo', 'Iris', 'Jonas',
                                 'Karin', 'Luca', 'Mira', 'Nils', 'Olga', 'Pavel', 'Quinn', 'Rosa', 'Sven', 'Tara'];
    l_last       text[] := ARRAY['Alvarez', 'Bergmann', 'Costa', 'Dubois', 'Eriksen', 'Fischer', 'Garcia', 'Hansen',
                                 'Ivanova', 'Jensen', 'Kowalski', 'Lindqvist', 'Moreau', 'Novak', 'Oliveira', 'Petrov',
                                 'Quist', 'Rossi', 'Schmidt', 'Tanaka'];
    l_countries  text[] := ARRAY['DE', 'FR', 'NL', 'ES', 'IT', 'SE', 'PL', 'PT', 'AT', 'BE'];
    l_categories text[] := ARRAY['AUDIO', 'KITCHEN', 'OUTDOOR', 'OFFICE', 'TOYS', 'GARDEN'];
    l_adjectives text[] := ARRAY['Compact', 'Classic', 'Premium', 'Eco', 'Smart', 'Travel', 'Family', 'Pro'];
    l_nouns      text[] := ARRAY['Speaker', 'Kettle', 'Tent', 'Desk Lamp', 'Puzzle', 'Planter', 'Headphones',
                                 'Blender', 'Backpack', 'Notebook', 'Board Game', 'Watering Can'];
    l_product_id bigint;
BEGIN
    PERFORM setseed(0.20261005);

    FOR i IN 1 .. 200 LOOP
        INSERT INTO sales.customer (email, first_name, last_name, country_code, status, created_at)
        VALUES ('customer' || lpad(i::text, 4, '0') || '@example.org',
                l_first[(i % array_length(l_first, 1)) + 1],
                l_last[((i * 7) % array_length(l_last, 1)) + 1],
                l_countries[((i * 3) % array_length(l_countries, 1)) + 1],
                CASE WHEN i % 37 = 0 THEN 'BLOCKED' WHEN i % 53 = 0 THEN 'CLOSED' ELSE 'ACTIVE' END,
                now() - make_interval(days => floor(30 + random() * 870)::int));
    END LOOP;

    FOR i IN 1 .. 60 LOOP
        INSERT INTO sales.product (sku, name, category, unit_price, active)
        VALUES ('SKU-' || lpad(i::text, 5, '0'),
                l_adjectives[(i % array_length(l_adjectives, 1)) + 1] || ' ' ||
                l_nouns[((i * 5) % array_length(l_nouns, 1)) + 1] || ' ' || i,
                l_categories[(i % array_length(l_categories, 1)) + 1],
                round((5 + random() * 395)::numeric, 2),
                CASE WHEN i % 29 = 0 THEN 0 ELSE 1 END)
        RETURNING id INTO l_product_id;

        INSERT INTO sales.inventory (product_id, warehouse_code, qty_on_hand, qty_reserved)
        VALUES (l_product_id, 'MAIN', floor(2000 + random() * 4000)::int, 0);
    END LOOP;
    RAISE NOTICE 'customers/products/inventory loaded';
END;
$$;

DO $$
DECLARE
    l_customer_ids bigint[];
    l_product_ids  bigint[];
    l_order_id     bigint;
    l_customer_id  bigint;
    l_product_id   bigint;
    l_price        numeric;
    l_qty          integer;
    l_lines        integer;
    l_total        numeric;
    l_age_days     integer;
    l_status       varchar;
    l_order_date   timestamptz;
    l_method       varchar;
BEGIN
    PERFORM setseed(0.20261006);
    SELECT array_agg(id ORDER BY id) INTO l_customer_ids FROM sales.customer WHERE status = 'ACTIVE';
    SELECT array_agg(id ORDER BY id) INTO l_product_ids  FROM sales.product  WHERE active = 1;

    FOR i IN 1 .. 600 LOOP
        l_customer_id := l_customer_ids[1 + floor(random() * array_length(l_customer_ids, 1))::int];
        l_age_days    := floor(random() * 540)::int;
        l_order_date  := now() - make_interval(days => l_age_days, secs => floor(random() * 86400));
        l_status      := CASE
                             WHEN l_age_days > 10 THEN CASE WHEN i % 23 = 0 THEN 'CANCELLED' ELSE 'DELIVERED' END
                             WHEN l_age_days > 3  THEN 'SHIPPED'
                             WHEN l_age_days > 1  THEN 'PAID'
                             ELSE 'NEW'
                         END;

        INSERT INTO sales.orders (order_no, customer_id, order_date, status, total_amount, updated_at)
        VALUES ('SO-' || nextval('sales.order_no_seq'), l_customer_id, l_order_date, l_status, 0, l_order_date)
        RETURNING id INTO l_order_id;

        l_total := 0;
        l_lines := 1 + floor(random() * 3)::int;
        FOR line IN 1 .. l_lines LOOP
            l_product_id := l_product_ids[1 + floor(random() * array_length(l_product_ids, 1))::int];
            l_qty        := 1 + floor(random() * 5)::int;
            SELECT unit_price INTO l_price FROM sales.product WHERE id = l_product_id;
            BEGIN
                INSERT INTO sales.order_item (order_id, product_id, qty, unit_price, line_total)
                VALUES (l_order_id, l_product_id, l_qty, l_price, round(l_price * l_qty, 2));
                l_total := l_total + round(l_price * l_qty, 2);
            EXCEPTION
                WHEN unique_violation THEN NULL; -- same product twice in one order: skip the line
            END;
        END LOOP;

        UPDATE sales.orders SET total_amount = l_total WHERE id = l_order_id;

        l_method := CASE i % 4 WHEN 0 THEN 'CARD' WHEN 1 THEN 'TRANSFER' WHEN 2 THEN 'WALLET' ELSE 'INVOICE' END;
        IF l_status IN ('PAID', 'SHIPPED', 'DELIVERED') THEN
            INSERT INTO sales.payment (order_id, amount, method, status, paid_at, created_at)
            VALUES (l_order_id, l_total, l_method, 'CAPTURED',
                    l_order_date + make_interval(secs => floor(60 + random() * 7140)), l_order_date);
        ELSIF l_status = 'CANCELLED' THEN
            INSERT INTO sales.payment (order_id, amount, method, status, paid_at, created_at)
            VALUES (l_order_id, l_total, l_method, 'REFUNDED', l_order_date + interval '1 day', l_order_date);
        ELSE
            INSERT INTO sales.payment (order_id, amount, method, status, created_at)
            VALUES (l_order_id, l_total, l_method, 'PENDING', l_order_date);
        END IF;
    END LOOP;
    RAISE NOTICE 'orders/order items/payments loaded';
END;
$$;

-- Exercise the PL/pgSQL API once: 12 orders through order_pkg_place_order, one of them cancelled
DO $$
DECLARE
    l_order_id bigint;
    l_first_id bigint;
BEGIN
    FOR i IN 1 .. 12 LOOP
        l_order_id := NULL;
        CALL sales.order_pkg_place_order(i, (i * 3) % 25 + 1, i % 4 + 1, l_order_id);
        IF i = 1 THEN
            l_first_id := l_order_id;
        END IF;
    END LOOP;
    CALL sales.order_pkg_cancel_order(l_first_id);
    RAISE NOTICE 'place_order exercised, customer 1 tier = %', sales.get_customer_tier(1);
END;
$$;

ANALYZE sales.customer;
ANALYZE sales.product;
ANALYZE sales.inventory;
ANALYZE sales.orders;
ANALYZE sales.order_item;
ANALYZE sales.payment;
ANALYZE sales.audit_log;

SELECT 'customer'   AS table_name, count(*) AS rows_loaded FROM sales.customer
UNION ALL SELECT 'product',    count(*) FROM sales.product
UNION ALL SELECT 'inventory',  count(*) FROM sales.inventory
UNION ALL SELECT 'orders',     count(*) FROM sales.orders
UNION ALL SELECT 'order_item', count(*) FROM sales.order_item
UNION ALL SELECT 'payment',    count(*) FROM sales.payment
UNION ALL SELECT 'audit_log',  count(*) FROM sales.audit_log;

RESET ROLE;
