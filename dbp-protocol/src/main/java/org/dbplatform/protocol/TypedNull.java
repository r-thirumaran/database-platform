package org.dbplatform.protocol;

/**
 * A typed SQL NULL parameter ({@code Value} tag 16), produced by {@code PreparedStatement.setNull(i, jdbcType)}.
 *
 * @param jdbcType the {@link java.sql.Types} constant
 */
public record TypedNull(int jdbcType) {
}
