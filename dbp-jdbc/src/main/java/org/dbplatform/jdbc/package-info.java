/**
 * Drop-in JDBC 4.3 driver for the Database Access Platform gateway.
 *
 * <p>Driver class {@link org.dbplatform.jdbc.DbpDriver}, URL {@code jdbc:dbp://gateway-host:7420/<datasource>?apiKey=...},
 * {@link org.dbplatform.jdbc.DbpDataSource} for bean-style configuration. Everything on the wire goes through
 * {@code dbp-protocol}; this package implements the JDBC semantics (conversions, cursors, batches, metadata)
 * on top of it.</p>
 */
package org.dbplatform.jdbc;
