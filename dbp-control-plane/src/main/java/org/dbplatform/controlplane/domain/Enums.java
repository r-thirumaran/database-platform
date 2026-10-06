package org.dbplatform.controlplane.domain;

/** Enumerations of the metadata model (names are the exact wire values of the API contract). */
public final class Enums {
    private Enums() {}

    /**
     * Engine of a physical database. {@code H2} (TCP server, e.g. for developers) and {@code OTHER} (any JDBC database, the full
     * URL comes from {@code jdbcProperties.url}) can be routed to by the gateway but have no proxy listener and no collectors.
     */
    public enum Engine { ORACLE, POSTGRES, MSSQL, H2, OTHER }
    public enum ApplicationKind { SERVICE, BATCH, UI, LEGACY, TOOL }
    public enum Runtime { CLOUD_RUN, KUBERNETES, VM, OTHER }
    public enum CredentialProvider { INLINE, ENV, FILE, VAULT, GCP_SECRET_MANAGER, AWS_SECRETS_MANAGER }
    public enum DatasourceState { ACTIVE, MIGRATING, RETIRED }
    public enum PoolMode { TRANSACTION, SESSION }
    public enum TableKind { TABLE, VIEW, MATERIALIZED_VIEW }
    public enum OwnerSource { DECLARED, INFERRED, NONE }
    public enum Classification { PII, CONFIDENTIAL, INTERNAL, PUBLIC }
    public enum MigrationState { NOT_PLANNED, PLANNED, IN_PROGRESS, DONE }
    public enum RoutineKind { PROCEDURE, FUNCTION, PACKAGE, PACKAGE_BODY, TRIGGER }
    public enum RoutineStatus { VALID, INVALID }
    public enum ObjectType { TABLE, ROUTINE }
    public enum DependencyKind { REFERENCES, READS, WRITES, FOREIGN_KEY, TRIGGERS, CALLS }
    public enum DependencySource { DICTIONARY, RUNTIME, DECLARED }
    public enum RelationshipKind { READS, WRITES, CALLS }
    public enum RelationshipSource { GATEWAY, PROXY_CORRELATION, COLLECTOR_SESSION, COLLECTOR_AUDIT, DECLARED }
    public enum ComponentType { GATEWAY, PROXY }
    public enum PolicyKind { CROSS_TEAM_DIRECT_ACCESS, UNOWNED_TABLE, UNDECLARED_CONSUMER, WRITE_BY_NON_PRODUCER, DIRECT_DB_ACCESS_BYPASSING_PLATFORM }
    public enum Severity { LOW, MEDIUM, HIGH }
    public enum ViolationStatus { OPEN, ACKNOWLEDGED, RESOLVED }
    public enum CollectWhat { DICTIONARY, RUNTIME, AUDIT }

    /** Confidence per relationship source, see docs/telemetry-events.md. */
    public static double confidenceOf(RelationshipSource source) {
        return switch (source) {
            case GATEWAY, COLLECTOR_AUDIT, DECLARED -> 1.0;
            case PROXY_CORRELATION -> 0.9;
            case COLLECTOR_SESSION -> 0.6;
        };
    }
}
