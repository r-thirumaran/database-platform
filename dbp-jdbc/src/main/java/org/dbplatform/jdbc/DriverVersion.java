package org.dbplatform.jdbc;

import java.io.InputStream;
import java.util.Properties;

/**
 * Driver version: read from the jar manifest ({@code Implementation-Version}), falling back to the filtered
 * {@code version.properties} resource (exploded classpath during tests) and finally {@code "0.0.0"}.
 */
final class DriverVersion {

    static final String DRIVER_NAME = "DBP JDBC Driver";
    static final String CLIENT_NAME = "dbp-jdbc";
    static final String VERSION = resolve();
    static final int MAJOR;
    static final int MINOR;

    static {
        int major = 0;
        int minor = 0;
        String[] parts = VERSION.split("[.\\-]");
        try {
            major = Integer.parseInt(parts[0]);
            if (parts.length > 1) {
                minor = Integer.parseInt(parts[1]);
            }
        } catch (NumberFormatException ignored) {
            // leave 0
        }
        MAJOR = major;
        MINOR = minor;
    }

    private DriverVersion() {
    }

    private static String resolve() {
        Package pkg = DriverVersion.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        if (v == null || v.isBlank()) {
            try (InputStream in = DriverVersion.class.getResourceAsStream("version.properties")) {
                if (in != null) {
                    Properties p = new Properties();
                    p.load(in);
                    v = p.getProperty("version");
                }
            } catch (Exception ignored) {
                v = null;
            }
        }
        if (v == null || v.isBlank() || v.startsWith("${")) {
            v = "0.0.0";
        }
        return v.trim();
    }
}
