package org.dbplatform.examples.orders;

import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlDialectTest {

    @Test
    void defaultsToOracleWhenEngineIsMissing() {
        assertThat(SqlDialect.fromEngine(null)).isEqualTo(SqlDialect.ORACLE);
        assertThat(SqlDialect.fromEngine("  ")).isEqualTo(SqlDialect.ORACLE);
    }

    @Test
    void acceptsCommonSpellings() {
        assertThat(SqlDialect.fromEngine("oracle")).isEqualTo(SqlDialect.ORACLE);
        assertThat(SqlDialect.fromEngine("POSTGRES")).isEqualTo(SqlDialect.POSTGRES);
        assertThat(SqlDialect.fromEngine("postgresql")).isEqualTo(SqlDialect.POSTGRES);
        assertThat(SqlDialect.fromEngine("pg")).isEqualTo(SqlDialect.POSTGRES);
    }

    @Test
    void rejectsUnknownEngine() {
        assertThatThrownBy(() -> SqlDialect.fromEngine("mssql"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mssql");
    }

    @Test
    void oracleUsesPackageAndOutParameter() {
        SqlDialect d = SqlDialect.ORACLE;
        assertThat(d.placeOrderCall()).isEqualTo("{call ORDER_PKG.PLACE_ORDER(?, ?, ?, ?)}");
        assertThat(d.orderIdOutType()).isEqualTo(Types.NUMERIC);
        assertThat(d.outParametersNeedInputValue()).isFalse();
        assertThat(d.ordersForCustomerIdIndex()).isEqualTo(1);
        assertThat(d.ordersForCustomerCursorIndex()).isEqualTo(2);
        assertThat(d.refCursorNeedsTransaction()).isFalse();
        assertThat(d.healthQuery()).isEqualTo("SELECT 1 FROM DUAL");
    }

    @Test
    void postgresUsesPrefixedRoutineAndFunctionResult() {
        SqlDialect d = SqlDialect.POSTGRES;
        assertThat(d.placeOrderCall()).isEqualTo("{call order_pkg_place_order(?, ?, ?, ?)}");
        assertThat(d.orderIdOutType()).isEqualTo(Types.BIGINT);
        assertThat(d.outParametersNeedInputValue()).isTrue();
        assertThat(d.ordersForCustomerCall()).startsWith("{? = call");
        assertThat(d.ordersForCustomerIdIndex()).isEqualTo(2);
        assertThat(d.ordersForCustomerCursorIndex()).isEqualTo(1);
        assertThat(d.refCursorNeedsTransaction()).isTrue();
        assertThat(d.healthQuery()).isEqualTo("SELECT 1");
    }

    @Test
    void sharedSqlIsIdenticalOnBothEngines() {
        assertThat(SqlDialect.ORACLE.listOrdersSql()).isEqualTo(SqlDialect.POSTGRES.listOrdersSql());
        assertThat(SqlDialect.ORACLE.listOrdersSql()).contains("FETCH FIRST ? ROWS ONLY");
        assertThat(SqlDialect.ORACLE.ordersForCustomerFallbackSql())
                .isEqualTo(SqlDialect.POSTGRES.ordersForCustomerFallbackSql());
    }

    @Test
    void urlMaskingHidesQueryString() {
        assertThat(DemoConfig.maskUrl("jdbc:dbp://gateway:7420/sales?apiKey=secret")).isEqualTo("jdbc:dbp://gateway:7420/sales?...");
        assertThat(DemoConfig.maskUrl("jdbc:oracle:thin:@//oracle:1521/FREEPDB1")).isEqualTo("jdbc:oracle:thin:@//oracle:1521/FREEPDB1");
        assertThat(DemoConfig.maskUrl(null)).isEmpty();
    }

    @Test
    void businessErrorsAreRecognised() {
        assertThat(ApiErrorHandler.isBusinessError("ORA-20010: Insufficient stock", "72000")).isTrue();
        assertThat(ApiErrorHandler.isBusinessError("Insufficient stock", "P0001")).isTrue();
        assertThat(ApiErrorHandler.isBusinessError("ORA-00942: table or view does not exist", "42000")).isFalse();
        assertThat(ApiErrorHandler.isBusinessError("connection refused", null)).isFalse();
    }
}
