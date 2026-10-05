package org.dbplatform.proxy.postgres;

/** A PostgreSQL startup message could not be parsed. */
public class PgProtocolException extends RuntimeException {
    public PgProtocolException(String message) {
        super(message);
    }
}
