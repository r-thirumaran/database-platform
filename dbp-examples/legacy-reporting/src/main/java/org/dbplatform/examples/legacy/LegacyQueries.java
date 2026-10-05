package org.dbplatform.examples.legacy;

/**
 * Hand-written Oracle SQL as found in applications written long before ANSI joins were fashionable.
 * Deliberately not portable: {@code (+)} outer joins, {@code NVL}, {@code SYSDATE}, {@code TO_CHAR},
 * {@code ADD_MONTHS}, {@code TRUNC(date)}, {@code ROWNUM}.
 */
public final class LegacyQueries {

    private LegacyQueries() {
    }

    /** Customers of a country with their order count and last order date (outer join with (+)). */
    public static final String CUSTOMERS_BY_COUNTRY = """
            SELECT c.ID, c.EMAIL, c.FIRST_NAME, c.LAST_NAME, c.COUNTRY_CODE, c.STATUS,
                   COUNT(o.ID)                    AS ORDER_COUNT,
                   NVL(SUM(o.TOTAL_AMOUNT), 0)    AS TOTAL_SPENT,
                   NVL(MAX(o.ORDER_DATE), c.CREATED_AT) AS LAST_ACTIVITY,
                   TRUNC(SYSDATE) - TRUNC(NVL(MAX(o.ORDER_DATE), c.CREATED_AT)) AS DAYS_SINCE_ACTIVITY
              FROM CUSTOMER c, ORDERS o
             WHERE o.CUSTOMER_ID (+) = c.ID
               AND o.STATUS (+) <> 'CANCELLED'
               AND c.COUNTRY_CODE = ?
             GROUP BY c.ID, c.EMAIL, c.FIRST_NAME, c.LAST_NAME, c.COUNTRY_CODE, c.STATUS, c.CREATED_AT
             ORDER BY TOTAL_SPENT DESC""";

    /** Captured revenue per month for the last n months. */
    public static final String REVENUE_BY_MONTH = """
            SELECT TO_CHAR(TRUNC(p.PAID_AT, 'MM'), 'YYYY-MM') AS MONTH,
                   COUNT(DISTINCT o.ID)                       AS ORDERS_CNT,
                   SUM(p.AMOUNT)                              AS REVENUE,
                   ROUND(AVG(p.AMOUNT), 2)                    AS AVG_PAYMENT
              FROM PAYMENT p, ORDERS o
             WHERE o.ID = p.ORDER_ID
               AND p.STATUS = 'CAPTURED'
               AND p.PAID_AT >= ADD_MONTHS(TRUNC(SYSDATE, 'MM'), -?)
             GROUP BY TRUNC(p.PAID_AT, 'MM')
             ORDER BY 1""";

    /** Orders without a captured payment, oldest first (outer join on PAYMENT with (+)). */
    public static final String OPEN_ORDERS = """
            SELECT *
              FROM (SELECT o.ID, o.ORDER_NO, o.ORDER_DATE, o.STATUS, o.TOTAL_AMOUNT,
                           NVL(p.STATUS, 'NONE')       AS PAYMENT_STATUS,
                           NVL(p.METHOD, 'N/A')        AS PAYMENT_METHOD,
                           ROUND(SYSDATE - o.ORDER_DATE) AS AGE_DAYS
                      FROM ORDERS o, PAYMENT p
                     WHERE p.ORDER_ID (+) = o.ID
                       AND p.STATUS (+) = 'CAPTURED'
                       AND o.STATUS IN ('NEW', 'PAID')
                       AND p.ID IS NULL
                     ORDER BY o.ORDER_DATE)
             WHERE ROWNUM <= ?""";

    public static final String PING = "SELECT SYSDATE FROM DUAL";
}
