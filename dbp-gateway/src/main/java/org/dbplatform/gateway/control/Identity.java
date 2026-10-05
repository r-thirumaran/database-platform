package org.dbplatform.gateway.control;

/**
 * The application identity of a logical session.
 *
 * @param applicationId control plane id ({@code null} in static mode)
 * @param application   application name
 * @param teamId        team id (nullable)
 * @param team          team name (nullable)
 */
public record Identity(String applicationId, String application, String teamId, String team) {

    public static Identity anonymous(String hint) {
        return new Identity(null, hint == null || hint.isBlank() ? "unknown" : hint, null, null);
    }
}
