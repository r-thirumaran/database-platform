-- =====================================================================================
-- 20_plsql.sql  --  Routines and triggers of the SALES demo schema
--
-- Lineage this creates for the dictionary crawler (DBA_DEPENDENCIES / DBA_TRIGGERS):
--
--   GET_CUSTOMER_TIER (function)      -> CUSTOMER, ORDERS, PAYMENT
--   RESERVE_STOCK (procedure)         -> INVENTORY
--   ORDER_PKG.CALC_TOTAL (function)   -> PRODUCT, GET_CUSTOMER_TIER
--   ORDER_PKG.PLACE_ORDER (procedure) -> RESERVE_STOCK, CALC_TOTAL, ORDERS, ORDER_ITEM, PAYMENT, ORDER_NO_SEQ
--   ORDER_PKG.CANCEL_ORDER            -> ORDERS, ORDER_ITEM, INVENTORY, PAYMENT
--   GET_ORDERS_FOR_CUSTOMER           -> ORDERS, ORDER_ITEM, PAYMENT  (SYS_REFCURSOR OUT parameter)
--   TRG_ORDERS_AUDIT      (ORDERS)    -> AUDIT_LOG
--   TRG_ORDER_ITEM_STOCK  (ORDER_ITEM)-> INVENTORY
--
-- An application that only calls ORDER_PKG.PLACE_ORDER therefore writes ORDERS, ORDER_ITEM,
-- PAYMENT, INVENTORY (via RESERVE_STOCK and the trigger) and AUDIT_LOG (via trigger).
-- =====================================================================================
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = SALES;

-- ---------------------------------------------------------------------------
-- GET_CUSTOMER_TIER: GOLD / SILVER / BRONZE from captured payments of the last 12 months
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION GET_CUSTOMER_TIER(p_customer_id IN NUMBER) RETURN VARCHAR2
IS
    l_exists NUMBER;
    l_spend  NUMBER;
BEGIN
    SELECT COUNT(*) INTO l_exists FROM CUSTOMER WHERE ID = p_customer_id;
    IF l_exists = 0 THEN
        RAISE_APPLICATION_ERROR(-20001, 'Unknown customer ' || p_customer_id);
    END IF;

    SELECT NVL(SUM(p.AMOUNT), 0)
      INTO l_spend
      FROM PAYMENT p
      JOIN ORDERS  o ON o.ID = p.ORDER_ID
     WHERE o.CUSTOMER_ID = p_customer_id
       AND p.STATUS = 'CAPTURED'
       AND p.PAID_AT >= ADD_MONTHS(SYSTIMESTAMP, -12);

    RETURN CASE
               WHEN l_spend >= 5000 THEN 'GOLD'
               WHEN l_spend >= 1000 THEN 'SILVER'
               ELSE 'BRONZE'
           END;
END GET_CUSTOMER_TIER;
/

-- ---------------------------------------------------------------------------
-- RESERVE_STOCK: lock the inventory row, check availability, increase the reservation
-- ---------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE RESERVE_STOCK(p_product_id IN NUMBER, p_qty IN NUMBER)
IS
    l_available NUMBER;
BEGIN
    IF p_qty IS NULL OR p_qty <= 0 THEN
        RAISE_APPLICATION_ERROR(-20012, 'Quantity must be positive');
    END IF;

    SELECT QTY_ON_HAND - QTY_RESERVED
      INTO l_available
      FROM INVENTORY
     WHERE PRODUCT_ID = p_product_id
       AND WAREHOUSE_CODE = 'MAIN'
       FOR UPDATE;

    IF l_available < p_qty THEN
        RAISE_APPLICATION_ERROR(-20010,
            'Insufficient stock for product ' || p_product_id || ': available ' || l_available || ', requested ' || p_qty);
    END IF;

    UPDATE INVENTORY
       SET QTY_RESERVED = QTY_RESERVED + p_qty,
           UPDATED_AT   = SYSTIMESTAMP
     WHERE PRODUCT_ID = p_product_id
       AND WAREHOUSE_CODE = 'MAIN';
EXCEPTION
    WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20011, 'No inventory row for product ' || p_product_id);
END RESERVE_STOCK;
/

-- ---------------------------------------------------------------------------
-- ORDER_PKG
-- ---------------------------------------------------------------------------
CREATE OR REPLACE PACKAGE ORDER_PKG AS
    -- Price of p_qty units of p_product_id for p_customer_id, after the tier discount.
    FUNCTION CALC_TOTAL(p_customer_id IN NUMBER, p_product_id IN NUMBER, p_qty IN NUMBER) RETURN NUMBER;

    -- Creates an order with one line and a pending payment; returns the new ORDERS.ID.
    PROCEDURE PLACE_ORDER(p_customer_id IN  NUMBER,
                          p_product_id  IN  NUMBER,
                          p_qty         IN  NUMBER,
                          p_order_id    OUT NUMBER);

    -- Cancels a NEW or PAID order and returns its stock.
    PROCEDURE CANCEL_ORDER(p_order_id IN NUMBER);
END ORDER_PKG;
/

CREATE OR REPLACE PACKAGE BODY ORDER_PKG AS

    FUNCTION TIER_DISCOUNT(p_tier IN VARCHAR2) RETURN NUMBER
    IS
    BEGIN
        RETURN CASE p_tier
                   WHEN 'GOLD'   THEN 0.10
                   WHEN 'SILVER' THEN 0.05
                   ELSE 0
               END;
    END TIER_DISCOUNT;

    FUNCTION CALC_TOTAL(p_customer_id IN NUMBER, p_product_id IN NUMBER, p_qty IN NUMBER) RETURN NUMBER
    IS
        l_price NUMBER;
        l_tier  VARCHAR2(10);
    BEGIN
        SELECT UNIT_PRICE
          INTO l_price
          FROM PRODUCT
         WHERE ID = p_product_id
           AND ACTIVE = 1;

        l_tier := GET_CUSTOMER_TIER(p_customer_id);
        RETURN ROUND(l_price * p_qty * (1 - TIER_DISCOUNT(l_tier)), 2);
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20020, 'Unknown or inactive product ' || p_product_id);
    END CALC_TOTAL;

    PROCEDURE PLACE_ORDER(p_customer_id IN  NUMBER,
                          p_product_id  IN  NUMBER,
                          p_qty         IN  NUMBER,
                          p_order_id    OUT NUMBER)
    IS
        l_total NUMBER;
        l_price NUMBER;
    BEGIN
        RESERVE_STOCK(p_product_id, p_qty);
        l_total := CALC_TOTAL(p_customer_id, p_product_id, p_qty);

        SELECT UNIT_PRICE INTO l_price FROM PRODUCT WHERE ID = p_product_id;

        INSERT INTO ORDERS (ORDER_NO, CUSTOMER_ID, STATUS, TOTAL_AMOUNT)
        VALUES ('SO-' || ORDER_NO_SEQ.NEXTVAL, p_customer_id, 'NEW', l_total)
        RETURNING ID INTO p_order_id;

        -- fires TRG_ORDER_ITEM_STOCK which converts the reservation into an on-hand decrement
        INSERT INTO ORDER_ITEM (ORDER_ID, PRODUCT_ID, QTY, UNIT_PRICE, LINE_TOTAL)
        VALUES (p_order_id, p_product_id, p_qty, l_price, l_total);

        INSERT INTO PAYMENT (ORDER_ID, AMOUNT, METHOD, STATUS)
        VALUES (p_order_id, l_total, 'CARD', 'PENDING');
    END PLACE_ORDER;

    PROCEDURE CANCEL_ORDER(p_order_id IN NUMBER)
    IS
    BEGIN
        UPDATE ORDERS
           SET STATUS     = 'CANCELLED',
               UPDATED_AT = SYSTIMESTAMP
         WHERE ID = p_order_id
           AND STATUS IN ('NEW', 'PAID');

        IF SQL%ROWCOUNT = 0 THEN
            RAISE_APPLICATION_ERROR(-20030, 'Order ' || p_order_id || ' does not exist or cannot be cancelled');
        END IF;

        UPDATE INVENTORY i
           SET QTY_ON_HAND = QTY_ON_HAND + (SELECT NVL(SUM(oi.QTY), 0)
                                              FROM ORDER_ITEM oi
                                             WHERE oi.ORDER_ID   = p_order_id
                                               AND oi.PRODUCT_ID = i.PRODUCT_ID),
               UPDATED_AT  = SYSTIMESTAMP
         WHERE i.WAREHOUSE_CODE = 'MAIN'
           AND i.PRODUCT_ID IN (SELECT PRODUCT_ID FROM ORDER_ITEM WHERE ORDER_ID = p_order_id);

        UPDATE PAYMENT SET STATUS = 'REFUNDED' WHERE ORDER_ID = p_order_id AND STATUS = 'CAPTURED';
        UPDATE PAYMENT SET STATUS = 'FAILED'   WHERE ORDER_ID = p_order_id AND STATUS = 'PENDING';
    END CANCEL_ORDER;

END ORDER_PKG;
/

-- ---------------------------------------------------------------------------
-- GET_ORDERS_FOR_CUSTOMER: ref-cursor OUT parameter (exercised by the orders-service example)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE GET_ORDERS_FOR_CUSTOMER(p_customer_id IN  NUMBER,
                                                    p_cursor      OUT SYS_REFCURSOR)
IS
BEGIN
    OPEN p_cursor FOR
        SELECT o.ID,
               o.ORDER_NO,
               o.ORDER_DATE,
               o.STATUS,
               o.TOTAL_AMOUNT,
               o.CURRENCY_CODE,
               (SELECT COUNT(*)      FROM ORDER_ITEM oi WHERE oi.ORDER_ID = o.ID) AS ITEM_COUNT,
               (SELECT MAX(p.STATUS) FROM PAYMENT    p  WHERE p.ORDER_ID  = o.ID) AS PAYMENT_STATUS
          FROM ORDERS o
         WHERE o.CUSTOMER_ID = p_customer_id
         ORDER BY o.ORDER_DATE DESC;
END GET_ORDERS_FOR_CUSTOMER;
/

-- ---------------------------------------------------------------------------
-- Triggers
-- ---------------------------------------------------------------------------
CREATE OR REPLACE TRIGGER TRG_ORDERS_AUDIT
AFTER INSERT OR UPDATE ON ORDERS
FOR EACH ROW
BEGIN
    IF INSERTING OR NVL(:OLD.STATUS, '~') <> NVL(:NEW.STATUS, '~') THEN
        INSERT INTO AUDIT_LOG (TABLE_NAME, ROW_ID, ACTION, OLD_STATUS, NEW_STATUS,
                               CHANGED_BY, CLIENT_PROGRAM, CLIENT_MODULE)
        VALUES ('ORDERS',
                :NEW.ID,
                CASE WHEN INSERTING THEN 'INSERT' ELSE 'UPDATE' END,
                :OLD.STATUS,
                :NEW.STATUS,
                SYS_CONTEXT('USERENV', 'SESSION_USER'),
                SYS_CONTEXT('USERENV', 'CLIENT_PROGRAM_NAME'),
                SYS_CONTEXT('USERENV', 'MODULE'));
    END IF;
END TRG_ORDERS_AUDIT;
/

CREATE OR REPLACE TRIGGER TRG_ORDER_ITEM_STOCK
AFTER INSERT ON ORDER_ITEM
FOR EACH ROW
BEGIN
    UPDATE INVENTORY
       SET QTY_ON_HAND  = QTY_ON_HAND - :NEW.QTY,
           QTY_RESERVED = GREATEST(QTY_RESERVED - :NEW.QTY, 0),
           UPDATED_AT   = SYSTIMESTAMP
     WHERE PRODUCT_ID = :NEW.PRODUCT_ID
       AND WAREHOUSE_CODE = 'MAIN';
END TRG_ORDER_ITEM_STOCK;
/

-- Make sure everything compiled; list anything invalid so it shows up in the container log.
SET LINESIZE 200
SELECT OBJECT_TYPE, OBJECT_NAME, STATUS
  FROM ALL_OBJECTS
 WHERE OWNER = 'SALES'
   AND OBJECT_TYPE IN ('FUNCTION', 'PROCEDURE', 'PACKAGE', 'PACKAGE BODY', 'TRIGGER', 'VIEW')
 ORDER BY OBJECT_TYPE, OBJECT_NAME;

EXIT
