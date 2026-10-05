package org.dbplatform.examples.orders;

import java.sql.Types;
import java.util.Locale;

/**
 * The few places where Oracle and PostgreSQL SQL differ for this example. Everything else is
 * portable SQL (unqualified names resolved by synonyms on Oracle and search_path on PostgreSQL,
 * {@code FETCH FIRST n ROWS ONLY}, JDBC call escapes).
 * <p>
 * Selected by the {@code DBP_DEMO_ENGINE} environment variable ({@code ORACLE} default,
 * {@code POSTGRES}); the {@code postgres-direct} profile sets it automatically.
 */
public enum SqlDialect {
    ORACLE, POSTGRES;

    public static SqlDialect fromEngine(String engine) {
        if (engine == null || engine.isBlank()) {
            return ORACLE;
        }
        return switch (engine.trim().toUpperCase(Locale.ROOT)) {
            case "ORACLE", "ORA" -> ORACLE;
            case "POSTGRES", "POSTGRESQL", "PG" -> POSTGRES;
            default -> throw new IllegalArgumentException("Unsupported DBP_DEMO_ENGINE '" + engine
                    + "' (expected ORACLE or POSTGRES)");
        };
    }

    /** Cheapest round trip for the health endpoint. */
    public String healthQuery() {
        return this == ORACLE ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }

    /** Latest orders with the customer e-mail; identical on both engines. */
    public String listOrdersSql() {
        return """
                SELECT o.ID, o.ORDER_NO, o.CUSTOMER_ID, c.EMAIL, o.ORDER_DATE, o.STATUS, o.TOTAL_AMOUNT, o.CURRENCY_CODE
                  FROM ORDERS o
                  JOIN CUSTOMER c ON c.ID = o.CUSTOMER_ID
                 ORDER BY o.ID DESC
                 FETCH FIRST ? ROWS ONLY""";
    }

    public String orderByIdSql() {
        return """
                SELECT o.ID, o.ORDER_NO, o.CUSTOMER_ID, c.EMAIL, o.ORDER_DATE, o.STATUS, o.TOTAL_AMOUNT, o.CURRENCY_CODE
                  FROM ORDERS o
                  JOIN CUSTOMER c ON c.ID = o.CUSTOMER_ID
                 WHERE o.ID = ?""";
    }

    /** Procedure with an OUT parameter (4th). PostgreSQL has no packages, so the member is prefixed. */
    public String placeOrderCall() {
        return this == ORACLE
                ? "{call ORDER_PKG.PLACE_ORDER(?, ?, ?, ?)}"
                : "{call order_pkg_place_order(?, ?, ?, ?)}";
    }

    /** JDBC type to register for the OUT order id (Oracle NUMBER travels as NUMERIC). */
    public int orderIdOutType() {
        return this == ORACLE ? Types.NUMERIC : Types.BIGINT;
    }

    /**
     * PostgreSQL procedures only have INOUT parameters, which pgjdbc requires to be bound as input as
     * well as registered as output. Oracle ignores an input value on a pure OUT parameter, but we do
     * not rely on that.
     */
    public boolean outParametersNeedInputValue() {
        return this == POSTGRES;
    }

    /** Function call with a return value. */
    public String customerTierCall() {
        return this == ORACLE
                ? "{? = call GET_CUSTOMER_TIER(?)}"
                : "{? = call get_customer_tier(?)}";
    }

    /**
     * Ref cursor: Oracle returns it through an OUT parameter, PostgreSQL through the function result.
     * {@link #ordersForCustomerIdIndex()} / {@link #ordersForCustomerCursorIndex()} give the positions.
     */
    public String ordersForCustomerCall() {
        return this == ORACLE
                ? "{call GET_ORDERS_FOR_CUSTOMER(?, ?)}"
                : "{? = call get_orders_for_customer(?)}";
    }

    public int ordersForCustomerIdIndex() {
        return this == ORACLE ? 1 : 2;
    }

    public int ordersForCustomerCursorIndex() {
        return this == ORACLE ? 2 : 1;
    }

    /** PostgreSQL cursors only live inside a transaction. */
    public boolean refCursorNeedsTransaction() {
        return this == POSTGRES;
    }

    /** Plain query equivalent to the ref-cursor routine, used when the driver cannot return a cursor. */
    public String ordersForCustomerFallbackSql() {
        return """
                SELECT o.ID, o.ORDER_NO, o.ORDER_DATE, o.STATUS, o.TOTAL_AMOUNT, o.CURRENCY_CODE,
                       (SELECT COUNT(*)      FROM ORDER_ITEM oi WHERE oi.ORDER_ID = o.ID) AS ITEM_COUNT,
                       (SELECT MAX(p.STATUS) FROM PAYMENT    p  WHERE p.ORDER_ID  = o.ID) AS PAYMENT_STATUS
                  FROM ORDERS o
                 WHERE o.CUSTOMER_ID = ?
                 ORDER BY o.ORDER_DATE DESC""";
    }
}
