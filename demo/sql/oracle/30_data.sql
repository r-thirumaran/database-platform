-- =====================================================================================
-- 30_data.sql  --  Demo data for the SALES schema (a few hundred rows per table)
--
-- Everything is generated with PL/SQL loops and a fixed DBMS_RANDOM seed, so the data set is
-- reproducible. The last block places a handful of orders through ORDER_PKG.PLACE_ORDER so the
-- routines are exercised at least once before any application connects.
-- =====================================================================================
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = SALES;
SET SERVEROUTPUT ON

-- ---------------------------------------------------------------------------
-- Customers (200) and products (60) with inventory
-- ---------------------------------------------------------------------------
DECLARE
    TYPE t_names IS TABLE OF VARCHAR2(40);
    l_first     t_names := t_names('Ada', 'Bruno', 'Chloe', 'Dario', 'Elena', 'Farid', 'Greta', 'Hugo', 'Iris', 'Jonas',
                                   'Karin', 'Luca', 'Mira', 'Nils', 'Olga', 'Pavel', 'Quinn', 'Rosa', 'Sven', 'Tara');
    l_last      t_names := t_names('Alvarez', 'Bergmann', 'Costa', 'Dubois', 'Eriksen', 'Fischer', 'Garcia', 'Hansen',
                                   'Ivanova', 'Jensen', 'Kowalski', 'Lindqvist', 'Moreau', 'Novak', 'Oliveira', 'Petrov',
                                   'Quist', 'Rossi', 'Schmidt', 'Tanaka');
    l_countries t_names := t_names('DE', 'FR', 'NL', 'ES', 'IT', 'SE', 'PL', 'PT', 'AT', 'BE');
    l_categories t_names := t_names('AUDIO', 'KITCHEN', 'OUTDOOR', 'OFFICE', 'TOYS', 'GARDEN');
    l_adjectives t_names := t_names('Compact', 'Classic', 'Premium', 'Eco', 'Smart', 'Travel', 'Family', 'Pro');
    l_nouns      t_names := t_names('Speaker', 'Kettle', 'Tent', 'Desk Lamp', 'Puzzle', 'Planter', 'Headphones',
                                    'Blender', 'Backpack', 'Notebook', 'Board Game', 'Watering Can');
    l_product_id NUMBER;
BEGIN
    DBMS_RANDOM.SEED(20261005);

    FOR i IN 1 .. 200 LOOP
        INSERT INTO CUSTOMER (EMAIL, FIRST_NAME, LAST_NAME, COUNTRY_CODE, STATUS, CREATED_AT)
        VALUES ('customer' || LPAD(i, 4, '0') || '@example.org',
                l_first(MOD(i, l_first.COUNT) + 1),
                l_last(MOD(i * 7, l_last.COUNT) + 1),
                l_countries(MOD(i * 3, l_countries.COUNT) + 1),
                CASE WHEN MOD(i, 37) = 0 THEN 'BLOCKED' WHEN MOD(i, 53) = 0 THEN 'CLOSED' ELSE 'ACTIVE' END,
                SYSTIMESTAMP - NUMTODSINTERVAL(TRUNC(DBMS_RANDOM.VALUE(30, 900)), 'DAY'));
    END LOOP;

    FOR i IN 1 .. 60 LOOP
        INSERT INTO PRODUCT (SKU, NAME, CATEGORY, UNIT_PRICE, ACTIVE)
        VALUES ('SKU-' || LPAD(i, 5, '0'),
                l_adjectives(MOD(i, l_adjectives.COUNT) + 1) || ' ' || l_nouns(MOD(i * 5, l_nouns.COUNT) + 1) || ' ' || i,
                l_categories(MOD(i, l_categories.COUNT) + 1),
                ROUND(DBMS_RANDOM.VALUE(5, 400), 2),
                CASE WHEN MOD(i, 29) = 0 THEN 0 ELSE 1 END)
        RETURNING ID INTO l_product_id;

        INSERT INTO INVENTORY (PRODUCT_ID, WAREHOUSE_CODE, QTY_ON_HAND, QTY_RESERVED)
        VALUES (l_product_id, 'MAIN', TRUNC(DBMS_RANDOM.VALUE(2000, 6000)), 0);
    END LOOP;

    COMMIT;
    DBMS_OUTPUT.PUT_LINE('customers/products/inventory loaded');
END;
/

-- ---------------------------------------------------------------------------
-- Orders (600) with 1-3 lines each and payments
-- ---------------------------------------------------------------------------
DECLARE
    TYPE t_ids IS TABLE OF NUMBER;
    l_customer_ids t_ids;
    l_product_ids  t_ids;
    l_order_id     NUMBER;
    l_customer_id  NUMBER;
    l_product_id   NUMBER;
    l_price        NUMBER;
    l_qty          NUMBER;
    l_lines        NUMBER;
    l_total        NUMBER;
    l_age_days     NUMBER;
    l_status       VARCHAR2(20);
    l_order_date   TIMESTAMP;
    l_method       VARCHAR2(20);
BEGIN
    DBMS_RANDOM.SEED(20261006);
    SELECT ID BULK COLLECT INTO l_customer_ids FROM CUSTOMER WHERE STATUS = 'ACTIVE' ORDER BY ID;
    SELECT ID BULK COLLECT INTO l_product_ids  FROM PRODUCT  WHERE ACTIVE = 1 ORDER BY ID;

    FOR i IN 1 .. 600 LOOP
        l_customer_id := l_customer_ids(TRUNC(DBMS_RANDOM.VALUE(1, l_customer_ids.COUNT + 1)));
        l_age_days    := TRUNC(DBMS_RANDOM.VALUE(0, 540));
        l_order_date  := SYSTIMESTAMP - NUMTODSINTERVAL(l_age_days, 'DAY')
                                      - NUMTODSINTERVAL(TRUNC(DBMS_RANDOM.VALUE(0, 86400)), 'SECOND');
        l_status      := CASE
                             WHEN l_age_days > 10 THEN CASE WHEN MOD(i, 23) = 0 THEN 'CANCELLED' ELSE 'DELIVERED' END
                             WHEN l_age_days > 3  THEN 'SHIPPED'
                             WHEN l_age_days > 1  THEN 'PAID'
                             ELSE 'NEW'
                         END;

        INSERT INTO ORDERS (ORDER_NO, CUSTOMER_ID, ORDER_DATE, STATUS, TOTAL_AMOUNT, UPDATED_AT)
        VALUES ('SO-' || ORDER_NO_SEQ.NEXTVAL, l_customer_id, l_order_date, l_status, 0, l_order_date)
        RETURNING ID INTO l_order_id;

        l_total := 0;
        l_lines := TRUNC(DBMS_RANDOM.VALUE(1, 4));
        FOR line IN 1 .. l_lines LOOP
            l_product_id := l_product_ids(TRUNC(DBMS_RANDOM.VALUE(1, l_product_ids.COUNT + 1)));
            l_qty        := TRUNC(DBMS_RANDOM.VALUE(1, 6));
            SELECT UNIT_PRICE INTO l_price FROM PRODUCT WHERE ID = l_product_id;
            BEGIN
                -- fires TRG_ORDER_ITEM_STOCK (inventory decrement)
                INSERT INTO ORDER_ITEM (ORDER_ID, PRODUCT_ID, QTY, UNIT_PRICE, LINE_TOTAL)
                VALUES (l_order_id, l_product_id, l_qty, l_price, ROUND(l_price * l_qty, 2));
                l_total := l_total + ROUND(l_price * l_qty, 2);
            EXCEPTION
                WHEN DUP_VAL_ON_INDEX THEN NULL; -- same product twice in one order: skip the line
            END;
        END LOOP;

        UPDATE ORDERS SET TOTAL_AMOUNT = l_total WHERE ID = l_order_id;

        l_method := CASE MOD(i, 4) WHEN 0 THEN 'CARD' WHEN 1 THEN 'TRANSFER' WHEN 2 THEN 'WALLET' ELSE 'INVOICE' END;
        IF l_status IN ('PAID', 'SHIPPED', 'DELIVERED') THEN
            INSERT INTO PAYMENT (ORDER_ID, AMOUNT, METHOD, STATUS, PAID_AT, CREATED_AT)
            VALUES (l_order_id, l_total, l_method, 'CAPTURED',
                    l_order_date + NUMTODSINTERVAL(TRUNC(DBMS_RANDOM.VALUE(60, 7200)), 'SECOND'), l_order_date);
        ELSIF l_status = 'CANCELLED' THEN
            INSERT INTO PAYMENT (ORDER_ID, AMOUNT, METHOD, STATUS, PAID_AT, CREATED_AT)
            VALUES (l_order_id, l_total, l_method, 'REFUNDED', l_order_date + INTERVAL '1' DAY, l_order_date);
        ELSE
            INSERT INTO PAYMENT (ORDER_ID, AMOUNT, METHOD, STATUS, CREATED_AT)
            VALUES (l_order_id, l_total, l_method, 'PENDING', l_order_date);
        END IF;
    END LOOP;

    COMMIT;
    DBMS_OUTPUT.PUT_LINE('orders/order items/payments loaded');
END;
/

-- ---------------------------------------------------------------------------
-- Exercise the PL/SQL API once: 12 orders through ORDER_PKG, one of them cancelled
-- ---------------------------------------------------------------------------
DECLARE
    l_order_id NUMBER;
    l_tier     VARCHAR2(10);
    l_first_id NUMBER;
BEGIN
    FOR i IN 1 .. 12 LOOP
        ORDER_PKG.PLACE_ORDER(p_customer_id => i,
                              p_product_id  => MOD(i * 3, 25) + 1,
                              p_qty         => MOD(i, 4) + 1,
                              p_order_id    => l_order_id);
        IF i = 1 THEN
            l_first_id := l_order_id;
        END IF;
    END LOOP;
    ORDER_PKG.CANCEL_ORDER(l_first_id);

    l_tier := GET_CUSTOMER_TIER(1);
    DBMS_OUTPUT.PUT_LINE('PLACE_ORDER exercised, customer 1 tier = ' || l_tier);
    COMMIT;
END;
/

-- Fresh optimizer statistics so collector-side row-count estimates are meaningful.
BEGIN
    DBMS_STATS.GATHER_SCHEMA_STATS(ownname => 'SALES', cascade => TRUE);
END;
/

SELECT 'CUSTOMER'   AS TABLE_NAME, COUNT(*) AS ROWS_LOADED FROM CUSTOMER
UNION ALL SELECT 'PRODUCT',    COUNT(*) FROM PRODUCT
UNION ALL SELECT 'INVENTORY',  COUNT(*) FROM INVENTORY
UNION ALL SELECT 'ORDERS',     COUNT(*) FROM ORDERS
UNION ALL SELECT 'ORDER_ITEM', COUNT(*) FROM ORDER_ITEM
UNION ALL SELECT 'PAYMENT',    COUNT(*) FROM PAYMENT
UNION ALL SELECT 'AUDIT_LOG',  COUNT(*) FROM AUDIT_LOG;

EXIT
