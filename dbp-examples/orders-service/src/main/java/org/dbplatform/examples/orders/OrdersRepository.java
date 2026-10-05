package org.dbplatform.examples.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * All database access of the example. Plain JDBC through {@link JdbcTemplate}; the only engine
 * specific parts come from {@link SqlDialect}. Nothing here knows whether the connection goes to the
 * database directly, through the proxy or through the gateway.
 */
@Repository
public class OrdersRepository {

    private static final Logger log = LoggerFactory.getLogger(OrdersRepository.class);

    private static final RowMapper<OrderView> ORDER_VIEW = (rs, i) -> new OrderView(
            rs.getLong("ID"),
            rs.getString("ORDER_NO"),
            rs.getLong("CUSTOMER_ID"),
            rs.getString("EMAIL"),
            toOffset(rs.getTimestamp("ORDER_DATE")),
            rs.getString("STATUS"),
            rs.getBigDecimal("TOTAL_AMOUNT"),
            rs.getString("CURRENCY_CODE"));

    private final JdbcTemplate jdbc;
    private final SqlDialect dialect;

    public OrdersRepository(JdbcTemplate jdbc, SqlDialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public SqlDialect dialect() {
        return dialect;
    }

    /** Plain SELECT through JdbcTemplate. */
    public List<OrderView> listOrders(int limit) {
        return jdbc.query(dialect.listOrdersSql(), ORDER_VIEW, limit);
    }

    public Optional<OrderView> findOrder(long id) {
        return jdbc.query(dialect.orderByIdSql(), ORDER_VIEW, id).stream().findFirst();
    }

    /** {@code {call ORDER_PKG.PLACE_ORDER(?,?,?,?)}} with an OUT parameter. */
    public long placeOrder(long customerId, long productId, int qty) {
        Long orderId = jdbc.execute((Connection con) -> {
            try (CallableStatement cs = con.prepareCall(dialect.placeOrderCall())) {
                cs.setLong(1, customerId);
                cs.setLong(2, productId);
                cs.setInt(3, qty);
                if (dialect.outParametersNeedInputValue()) {
                    cs.setNull(4, Types.BIGINT);
                }
                cs.registerOutParameter(4, dialect.orderIdOutType());
                cs.execute();
                return cs.getLong(4);
            }
        });
        if (orderId == null) {
            throw new IllegalStateException("PLACE_ORDER returned no order id");
        }
        return orderId;
    }

    /** {@code {? = call GET_CUSTOMER_TIER(?)}}: function call with a return value. */
    public String customerTier(long customerId) {
        return jdbc.execute((Connection con) -> {
            try (CallableStatement cs = con.prepareCall(dialect.customerTierCall())) {
                cs.registerOutParameter(1, Types.VARCHAR);
                cs.setLong(2, customerId);
                cs.execute();
                return cs.getString(1);
            }
        });
    }

    /**
     * Ref-cursor OUT parameter. Falls back to the equivalent query when the driver in use cannot
     * materialise a cursor (the fallback is reported through {@link CursorResult#source()}).
     */
    public CursorResult ordersForCustomer(long customerId) {
        return jdbc.execute((Connection con) -> {
            boolean previousAutoCommit = con.getAutoCommit();
            boolean switched = false;
            if (dialect.refCursorNeedsTransaction() && previousAutoCommit) {
                con.setAutoCommit(false);
                switched = true;
            }
            try (CallableStatement cs = con.prepareCall(dialect.ordersForCustomerCall())) {
                cs.setLong(dialect.ordersForCustomerIdIndex(), customerId);
                cs.registerOutParameter(dialect.ordersForCustomerCursorIndex(), Types.REF_CURSOR);
                cs.execute();
                Object cursor = cs.getObject(dialect.ordersForCustomerCursorIndex());
                if (cursor instanceof ResultSet rs) {
                    try (rs) {
                        return new CursorResult("REF_CURSOR", readCustomerOrders(rs));
                    }
                }
                log.warn("Driver returned {} instead of a ResultSet for the ref cursor; using the fallback query",
                        cursor == null ? "null" : cursor.getClass().getName());
            } catch (SQLException e) {
                log.warn("Ref cursor call failed ({}); using the fallback query", e.getMessage());
            } finally {
                if (switched) {
                    con.commit();
                    con.setAutoCommit(previousAutoCommit);
                }
            }
            try (PreparedStatement ps = con.prepareStatement(dialect.ordersForCustomerFallbackSql())) {
                ps.setLong(1, customerId);
                try (ResultSet rs = ps.executeQuery()) {
                    return new CursorResult("QUERY_FALLBACK", readCustomerOrders(rs));
                }
            }
        });
    }

    private static List<CustomerOrder> readCustomerOrders(ResultSet rs) throws SQLException {
        List<CustomerOrder> rows = new ArrayList<>();
        while (rs.next()) {
            BigDecimal total = rs.getBigDecimal("TOTAL_AMOUNT");
            rows.add(new CustomerOrder(
                    rs.getLong("ID"),
                    rs.getString("ORDER_NO"),
                    toOffset(rs.getTimestamp("ORDER_DATE")),
                    rs.getString("STATUS"),
                    total,
                    rs.getString("CURRENCY_CODE"),
                    rs.getLong("ITEM_COUNT"),
                    rs.getString("PAYMENT_STATUS")));
        }
        return rows;
    }

    /** Health probe: one trivial round trip. */
    public boolean ping() {
        Integer one = jdbc.queryForObject(dialect.healthQuery(), Integer.class);
        return one != null && one == 1;
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    public record CursorResult(String source, List<CustomerOrder> orders) {
    }
}
