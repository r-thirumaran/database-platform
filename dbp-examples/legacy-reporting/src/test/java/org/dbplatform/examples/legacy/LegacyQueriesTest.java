package org.dbplatform.examples.legacy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the "legacy" character of the SQL: this app must keep its Oracle-only constructs. */
class LegacyQueriesTest {

    @Test
    void customersQueryUsesOracleOuterJoinAndNvl() {
        assertThat(LegacyQueries.CUSTOMERS_BY_COUNTRY).contains("(+)").contains("NVL(").contains("SYSDATE");
        assertThat(LegacyQueries.CUSTOMERS_BY_COUNTRY).doesNotContainIgnoringCase("LEFT JOIN");
        assertThat(LegacyQueries.CUSTOMERS_BY_COUNTRY.chars().filter(c -> c == '?').count()).isEqualTo(1);
    }

    @Test
    void revenueQueryUsesOracleDateFunctions() {
        assertThat(LegacyQueries.REVENUE_BY_MONTH).contains("ADD_MONTHS(").contains("TO_CHAR(").contains("TRUNC(");
        assertThat(LegacyQueries.REVENUE_BY_MONTH.chars().filter(c -> c == '?').count()).isEqualTo(1);
    }

    @Test
    void openOrdersQueryUsesRownum() {
        assertThat(LegacyQueries.OPEN_ORDERS).contains("ROWNUM <= ?").contains("(+)");
    }

    @Test
    void pingUsesDual() {
        assertThat(LegacyQueries.PING).isEqualTo("SELECT SYSDATE FROM DUAL");
    }
}
