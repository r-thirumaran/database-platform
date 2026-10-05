package org.dbplatform.proxy.identity;

import java.net.InetAddress;

/**
 * Everything observable about a new connection before the backend is involved.
 *
 * @param serviceAlias      suffix of {@code <match>.<alias>} in the requested service, or null
 * @param program           Oracle {@code CID.PROGRAM} (null for PostgreSQL)
 * @param pgApplicationName PostgreSQL {@code application_name} startup parameter (null for Oracle)
 * @param machine           Oracle {@code CID.HOST}
 * @param clientAddress     the TCP peer address
 */
public record IdentityInput(String serviceAlias, String program, String pgApplicationName, String machine, InetAddress clientAddress) {
}
