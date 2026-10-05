package org.dbplatform.jdbc;

import org.dbplatform.protocol.messages.Execute;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallableStatementTest extends GatewayTest {

    @Test
    void outAndInOutParameters() throws Exception {
        try (Connection c = connect()) {
            CallableStatement cs = c.prepareCall("{call add_one(?, ?, ?)}");
            cs.setInt(1, 10);
            cs.registerOutParameter(1, Types.INTEGER);
            cs.registerOutParameter(2, Types.VARCHAR);
            cs.registerOutParameter(3, Types.NULL);
            assertThat(cs.execute()).isFalse();

            Execute e = gateway.last(Execute.class);
            assertThat(e.kind().name()).isEqualTo("CALLABLE");
            assertThat(e.params()).containsExactly(10, null, null);
            assertThat(e.outParams()).extracting(Execute.OutParam::index).containsExactly(1, 2, 3);
            assertThat(e.outParams().get(0).jdbcType()).isEqualTo(Types.INTEGER);
            assertThat(e.outParams().get(0).scale()).isEqualTo(-1);

            assertThat(cs.getInt(1)).isEqualTo(11);
            assertThat(cs.wasNull()).isFalse();
            assertThat(cs.getString(2)).isEqualTo("out-2");
            assertThat(cs.getObject(2)).isEqualTo("out-2");
            assertThat(cs.getObject(3)).isNull();
            assertThat(cs.wasNull()).isTrue();
            assertThat(cs.getInt(3)).isZero();
            assertThat(cs.getUpdateCount()).isEqualTo(-1);
            assertThat(cs.getResultSet()).isNull();
            assertThatThrownBy(() -> cs.getInt(4)).satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo("07009"));
        }
    }

    @Test
    void typedOutParameters() throws Exception {
        try (Connection c = connect()) {
            CallableStatement cs = c.prepareCall("{call typed(?, ?, ?, ?, ?, ?)}");
            cs.registerOutParameter(1, Types.NUMERIC, 2);
            cs.registerOutParameter(2, Types.DATE);
            cs.registerOutParameter(3, Types.TIMESTAMP);
            cs.registerOutParameter(4, Types.BOOLEAN);
            cs.registerOutParameter(5, JDBCType.DOUBLE);
            cs.registerOutParameter(6, Types.BIGINT, "NUMBER");
            cs.execute();
            Execute e = gateway.last(Execute.class);
            assertThat(e.outParams().get(0).scale()).isEqualTo(2);
            assertThat(e.outParams().get(5).typeName()).isEqualTo("NUMBER");

            assertThat(cs.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("12.50"));
            assertThat(cs.getDouble(1)).isEqualTo(12.5d);
            assertThat(cs.getString(1)).isEqualTo("12.50");
            assertThat(cs.getDate(2)).isEqualTo(Date.valueOf("2024-01-15"));
            assertThat(cs.getObject(2, LocalDate.class)).isEqualTo(LocalDate.of(2024, 1, 15));
            assertThat(cs.getTimestamp(3)).isEqualTo(Timestamp.valueOf("2024-01-15 10:20:30"));
            assertThat(cs.getObject(3)).isInstanceOf(Timestamp.class);
            assertThat(cs.getBoolean(4)).isTrue();
            assertThat(cs.getDouble(5)).isEqualTo(2.5d);
            assertThat(cs.getLong(6)).isEqualTo(42L);
            assertThatThrownBy(() -> cs.getBlob(6)).satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo("22018"));
        }
    }

    @Test
    void cursorOutParameter() throws Exception {
        try (Connection c = connect()) {
            CallableStatement cs = c.prepareCall("{call get_customers(?)}");
            cs.registerOutParameter(1, Types.REF_CURSOR);
            assertThat(cs.execute()).isFalse();
            assertThat(cs.getUpdateCount()).isEqualTo(-1);
            assertThat(cs.getMoreResults()).isFalse();

            Object o = cs.getObject(1);
            assertThat(o).isInstanceOf(ResultSet.class);
            ResultSet rs = (ResultSet) o;
            assertThat(rs.getStatement()).isSameAs(cs);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("NAME")).isEqualTo("alice");
            assertThat(rs.next()).isTrue();
            assertThat(rs.next()).isFalse();
            assertThat(cs.getObject(1, ResultSet.class)).isSameAs(rs);
            cs.close();
            assertThat(rs.isClosed()).isTrue();
        }
    }

    @Test
    void oracleLegacyCursorCodeAndMixedResults() throws Exception {
        try (Connection c = connect(); CallableStatement cs = c.prepareCall("{call mixed(?, ?)}")) {
            cs.setString(1, "in");
            cs.registerOutParameter(2, -10);
            assertThat(cs.execute()).isTrue();
            ResultSet regular = cs.getResultSet();
            assertThat(regular.next()).isTrue();
            assertThat(regular.getInt(1)).isEqualTo(1);
            assertThat(regular.next()).isFalse();

            assertThat(cs.getMoreResults()).isFalse();
            assertThat(cs.getUpdateCount()).isEqualTo(3);
            assertThat(cs.getMoreResults()).isFalse();
            assertThat(cs.getUpdateCount()).isEqualTo(-1);

            ResultSet cursor = (ResultSet) cs.getObject(2);
            assertThat(cursor.isClosed()).isFalse();
            int n = 0;
            while (cursor.next()) {
                n++;
            }
            assertThat(n).isEqualTo(2);
        }
    }

    @Test
    void namedParametersAreRejected() throws Exception {
        try (Connection c = connect(); CallableStatement cs = c.prepareCall("{call p(?)}")) {
            assertThatThrownBy(() -> cs.setString("name", "x"))
                    .isInstanceOf(SQLFeatureNotSupportedException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("0A000"))
                    .hasMessageContaining("named parameters");
            assertThatThrownBy(() -> cs.getInt("name")).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> cs.registerOutParameter("name", Types.INTEGER)).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> cs.getObject("name", String.class)).isInstanceOf(SQLFeatureNotSupportedException.class);
        }
    }
}
