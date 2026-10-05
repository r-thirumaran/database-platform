package org.dbplatform.protocol;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Parsed DBP JDBC URL (section 8 of the specification):
 *
 * <pre>jdbc:dbp://host[:port][,host2[:port2]...]/datasource[?prop=value&amp;prop2=value2]</pre>
 *
 * <ul>
 *   <li>The default port is {@link ProtocolConstants#DEFAULT_PORT}.</li>
 *   <li>IPv6 literals are written in brackets: {@code jdbc:dbp://[::1]:7420/sales}.</li>
 *   <li>Property names and values are percent-decoded ({@code +} is also decoded to a space); a key without
 *       {@code =} is stored with the value {@code "true"}; later duplicates overwrite earlier ones.</li>
 *   <li>Parsing is strict: a missing or empty host, port or datasource raises {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * @param hosts      gateway hosts in failover order, never empty
 * @param datasource logical datasource name, never empty
 * @param properties URL properties in order of appearance (unmodifiable)
 */
public record JdbcUrl(List<HostPort> hosts, String datasource, Map<String, String> properties) {

    /** URL prefix (lower case). */
    public static final String PREFIX = ProtocolConstants.JDBC_URL_PREFIX;

    /**
     * One gateway endpoint.
     *
     * @param host host name or IP literal (IPv6 without brackets)
     * @param port TCP port, {@code 1..65535}
     */
    public record HostPort(String host, int port) {
        /**
         * Validates the endpoint.
         *
         * @param host non-empty host
         * @param port port in range
         */
        public HostPort {
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("host must not be empty");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
        }

        @Override
        public String toString() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }
    }

    /**
     * Normalises and validates the components.
     *
     * @param hosts      non-empty host list
     * @param datasource non-empty datasource
     * @param properties properties ({@code null} = empty)
     */
    public JdbcUrl {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("at least one host is required");
        }
        if (datasource == null || datasource.isEmpty()) {
            throw new IllegalArgumentException("datasource is required");
        }
        hosts = List.copyOf(hosts);
        properties = properties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /**
     * Returns whether the URL starts with {@code jdbc:dbp://} (case-insensitive prefix match, as
     * {@code java.sql.Driver.acceptsURL} requires). Does not validate the rest of the URL.
     *
     * @param url candidate URL, may be {@code null}
     * @return {@code true} if this driver should handle it
     */
    public static boolean acceptsUrl(String url) {
        return url != null && url.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /**
     * Parses a URL.
     *
     * @param url the URL
     * @return the parsed components
     * @throws IllegalArgumentException if the URL is not a well-formed DBP URL
     */
    public static JdbcUrl parse(String url) {
        if (!acceptsUrl(url)) {
            throw new IllegalArgumentException("not a DBP JDBC URL (expected prefix " + PREFIX + "): " + url);
        }
        String rest = url.substring(PREFIX.length());

        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("missing '/<datasource>' in JDBC URL: " + url);
        }
        String authority = rest.substring(0, slash);
        String pathAndQuery = rest.substring(slash + 1);

        String path;
        String query;
        int q = pathAndQuery.indexOf('?');
        if (q >= 0) {
            path = pathAndQuery.substring(0, q);
            query = pathAndQuery.substring(q + 1);
        } else {
            path = pathAndQuery;
            query = null;
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException("datasource is empty in JDBC URL: " + url);
        }
        if (path.indexOf('/') >= 0) {
            throw new IllegalArgumentException("datasource must not contain '/' in JDBC URL: " + url);
        }

        List<HostPort> hosts = parseHosts(authority, url);
        Map<String, String> props = parseQuery(query, url);
        return new JdbcUrl(hosts, decode(path, url), props);
    }

    /**
     * Returns the URL properties overlaid on {@code info} (URL values win), as a plain string map. Non-string
     * entries of {@code info} are ignored.
     *
     * @param info connection properties passed to {@code Driver.connect}, may be {@code null}
     * @return merged properties (mutable copy)
     */
    public Map<String, String> mergedProperties(Properties info) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (info != null) {
            for (String name : info.stringPropertyNames()) {
                merged.put(name, info.getProperty(name));
            }
        }
        merged.putAll(properties);
        return merged;
    }

    /**
     * Returns a property value.
     *
     * @param name property name
     * @return the value or {@code null}
     */
    public String property(String name) {
        return properties.get(name);
    }

    /**
     * Re-renders the URL in canonical form (explicit ports, properties percent-encoded).
     *
     * @return the URL string
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < hosts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(hosts.get(i));
        }
        sb.append('/').append(encode(datasource));
        boolean first = true;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            sb.append(first ? '?' : '&');
            first = false;
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return sb.toString();
    }

    private static List<HostPort> parseHosts(String authority, String url) {
        if (authority.isEmpty()) {
            throw new IllegalArgumentException("missing gateway host in JDBC URL: " + url);
        }
        List<HostPort> hosts = new ArrayList<>();
        for (String part : authority.split(",", -1)) {
            String hp = part.trim();
            if (hp.isEmpty()) {
                throw new IllegalArgumentException("empty host entry in JDBC URL: " + url);
            }
            String host;
            String portText;
            if (hp.startsWith("[")) {
                int close = hp.indexOf(']');
                if (close < 0) {
                    throw new IllegalArgumentException("unterminated IPv6 literal in JDBC URL: " + url);
                }
                host = hp.substring(1, close);
                String after = hp.substring(close + 1);
                if (after.isEmpty()) {
                    portText = null;
                } else if (after.charAt(0) == ':') {
                    portText = after.substring(1);
                } else {
                    throw new IllegalArgumentException("unexpected characters after IPv6 literal in JDBC URL: " + url);
                }
            } else {
                int colon = hp.lastIndexOf(':');
                if (colon >= 0 && hp.indexOf(':') != colon) {
                    throw new IllegalArgumentException("IPv6 literals must be enclosed in [] in JDBC URL: " + url);
                }
                host = colon < 0 ? hp : hp.substring(0, colon);
                portText = colon < 0 ? null : hp.substring(colon + 1);
            }
            int port = ProtocolConstants.DEFAULT_PORT;
            if (portText != null) {
                if (portText.isEmpty()) {
                    throw new IllegalArgumentException("empty port in JDBC URL: " + url);
                }
                try {
                    port = Integer.parseInt(portText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid port '" + portText + "' in JDBC URL: " + url, e);
                }
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("port out of range '" + portText + "' in JDBC URL: " + url);
                }
            }
            if (host.isEmpty()) {
                throw new IllegalArgumentException("empty host in JDBC URL: " + url);
            }
            hosts.add(new HostPort(host, port));
        }
        return hosts;
    }

    private static Map<String, String> parseQuery(String query, String url) {
        Map<String, String> props = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return props;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq), url);
            String value = eq < 0 ? "true" : decode(pair.substring(eq + 1), url);
            if (key.isEmpty()) {
                throw new IllegalArgumentException("empty property name in JDBC URL: " + url);
            }
            props.put(key, value);
        }
        return props;
    }

    private static String decode(String s, String url) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid percent-encoding '" + s + "' in JDBC URL: " + url, e);
        }
    }

    private static String encode(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }
}
