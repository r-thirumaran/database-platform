package org.dbplatform.examples.batch;

import java.util.List;

/**
 * The statements one worker iteration runs. Portable between Oracle and PostgreSQL: unqualified
 * names (synonyms / search_path), ANSI joins, {@code FETCH FIRST}, {@code CURRENT_TIMESTAMP}.
 */
public final class Workload {

    private Workload() {
    }

    /** Aggregations and point lookups; {@code ?} parameters are filled from {@link Params}. */
    public static final List<String> SELECTS = List.of(
            // 1. orders per status
            "SELECT STATUS, COUNT(*) AS CNT, SUM(TOTAL_AMOUNT) AS TOTAL FROM ORDERS GROUP BY STATUS",
            // 2. revenue per category (3-way join)
            """
            SELECT p.CATEGORY, SUM(oi.LINE_TOTAL) AS REVENUE, COUNT(DISTINCT o.ID) AS ORDERS_CNT
              FROM ORDER_ITEM oi
              JOIN PRODUCT p ON p.ID = oi.PRODUCT_ID
              JOIN ORDERS  o ON o.ID = oi.ORDER_ID
             WHERE o.STATUS <> 'CANCELLED'
             GROUP BY p.CATEGORY
             ORDER BY REVENUE DESC""",
            // 3. customer lookup (customer id)
            "SELECT c.ID, c.EMAIL, c.COUNTRY_CODE, c.STATUS FROM CUSTOMER c WHERE c.ID = ?",
            // 4. latest orders of a customer (customer id)
            """
            SELECT o.ID, o.ORDER_NO, o.STATUS, o.TOTAL_AMOUNT
              FROM ORDERS o
             WHERE o.CUSTOMER_ID = ?
             ORDER BY o.ORDER_DATE DESC
             FETCH FIRST 10 ROWS ONLY""",
            // 5. captured payments since a timestamp (timestamp)
            "SELECT COUNT(*) AS CNT, COALESCE(SUM(AMOUNT), 0) AS TOTAL FROM PAYMENT WHERE STATUS = 'CAPTURED' AND PAID_AT >= ?",
            // 6. the reporting view (customer id)
            "SELECT CUSTOMER_ID, ORDER_COUNT, LIFETIME_VALUE FROM V_CUSTOMER_ORDER_SUMMARY WHERE CUSTOMER_ID = ?",
            // 7. stock below reservation (no parameters)
            "SELECT i.PRODUCT_ID, i.QTY_ON_HAND, i.QTY_RESERVED FROM INVENTORY i WHERE i.QTY_ON_HAND - i.QTY_RESERVED < 100");

    /** Which parameter each SELECT takes. */
    public enum Params { NONE, CUSTOMER_ID, TIMESTAMP }

    public static final List<Params> SELECT_PARAMS = List.of(
            Params.NONE, Params.NONE, Params.CUSTOMER_ID, Params.CUSTOMER_ID, Params.TIMESTAMP, Params.CUSTOMER_ID, Params.NONE);

    /** Batched inside one transaction; touches a row without changing business data. */
    public static final String UPDATE = "UPDATE INVENTORY SET UPDATED_AT = CURRENT_TIMESTAMP WHERE PRODUCT_ID = ?";

    public static final String MAX_CUSTOMER_ID = "SELECT MAX(ID) FROM CUSTOMER";
    public static final String MAX_PRODUCT_ID = "SELECT MAX(ID) FROM PRODUCT";
}
