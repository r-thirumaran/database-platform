package org.dbplatform.proxy.oracle;

/** A TNS descriptor or packet could not be parsed. */
public class TnsParseException extends RuntimeException {
    public TnsParseException(String message) {
        super(message);
    }
}
