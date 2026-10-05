-- Database Access Platform control plane: metadata store schema.
-- Written in PostgreSQL-compatible SQL that also runs on H2 in PostgreSQL mode
-- (jdbc:h2:...;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE). No JSONB: JSON documents are stored as TEXT.
-- Primary keys are UUID strings generated in Java (VARCHAR(36)).

CREATE TABLE config_version (
    id          INTEGER PRIMARY KEY,
    version     BIGINT NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
INSERT INTO config_version (id, version, updated_at) VALUES (1, 1, CURRENT_TIMESTAMP);

CREATE TABLE team (
    id            VARCHAR(36) PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    display_name  VARCHAR(200),
    description   TEXT,
    contacts      TEXT,
    tags          TEXT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_team_name UNIQUE (name)
);

CREATE TABLE application (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    display_name    VARCHAR(200),
    team_id         VARCHAR(36),
    kind            VARCHAR(20) NOT NULL,
    description     TEXT,
    runtime         VARCHAR(20),
    identity_rules  TEXT,
    tags            TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_application_name UNIQUE (name)
);
CREATE INDEX ix_application_team ON application (team_id);

CREATE TABLE api_key (
    id              VARCHAR(36) PRIMARY KEY,
    application_id  VARCHAR(36) NOT NULL REFERENCES application (id) ON DELETE CASCADE,
    prefix          VARCHAR(16) NOT NULL,
    key_hash        VARCHAR(64) NOT NULL,
    label           VARCHAR(100),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at    TIMESTAMP WITH TIME ZONE,
    revoked_at      TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ix_api_key_hash ON api_key (key_hash);
CREATE INDEX ix_api_key_application ON api_key (application_id);

CREATE TABLE credential (
    id                VARCHAR(36) PRIMARY KEY,
    name              VARCHAR(100) NOT NULL,
    username          VARCHAR(200),
    provider          VARCHAR(30) NOT NULL,
    ref               VARCHAR(1000),
    encrypted_secret  TEXT,
    version           BIGINT NOT NULL DEFAULT 1,
    rotated_at        TIMESTAMP WITH TIME ZONE,
    description       TEXT,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_credential_name UNIQUE (name)
);

CREATE TABLE database_instance (
    id                        VARCHAR(36) PRIMARY KEY,
    name                      VARCHAR(100) NOT NULL,
    engine                    VARCHAR(20) NOT NULL,
    host                      VARCHAR(255) NOT NULL,
    port                      INTEGER NOT NULL,
    service_name              VARCHAR(255),
    credential_id             VARCHAR(36),
    max_physical_connections  INTEGER,
    jdbc_properties           TEXT,
    collector_config          TEXT,
    description               TEXT,
    tags                      TEXT,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_database_name UNIQUE (name)
);

CREATE TABLE datasource (
    id                   VARCHAR(36) PRIMARY KEY,
    name                 VARCHAR(100) NOT NULL,
    display_name         VARCHAR(200),
    owner_team_id        VARCHAR(36),
    state                VARCHAR(20) NOT NULL,
    current_database_id  VARCHAR(36),
    target_database_id   VARCHAR(36),
    pool_policy          TEXT,
    description          TEXT,
    tags                 TEXT,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_datasource_name UNIQUE (name)
);

CREATE TABLE routing_rule (
    id              VARCHAR(36) PRIMARY KEY,
    datasource_id   VARCHAR(36) NOT NULL REFERENCES datasource (id) ON DELETE CASCADE,
    priority        INTEGER NOT NULL DEFAULT 100,
    application_id  VARCHAR(36),
    tag             VARCHAR(100),
    database_id     VARCHAR(36) NOT NULL,
    read_only       BOOLEAN NOT NULL DEFAULT FALSE,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX ix_routing_rule_datasource ON routing_rule (datasource_id);

CREATE TABLE access_grant (
    id                       VARCHAR(36) PRIMARY KEY,
    application_id           VARCHAR(36) NOT NULL,
    datasource_id            VARCHAR(36) NOT NULL,
    max_logical_connections  INTEGER,
    max_proxy_connections    INTEGER,
    pool_mode_override       VARCHAR(20),
    read_only                BOOLEAN NOT NULL DEFAULT FALSE,
    enabled                  BOOLEAN NOT NULL DEFAULT TRUE,
    note                     TEXT,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_access_grant UNIQUE (application_id, datasource_id)
);

CREATE TABLE db_table (
    id                       VARCHAR(36) PRIMARY KEY,
    database_id              VARCHAR(36) NOT NULL,
    schema_name              VARCHAR(128) NOT NULL,
    name                     VARCHAR(128) NOT NULL,
    kind                     VARCHAR(20) NOT NULL,
    owner_team_id            VARCHAR(36),
    owner_confirmed          BOOLEAN NOT NULL DEFAULT FALSE,
    owner_source             VARCHAR(20) NOT NULL DEFAULT 'NONE',
    producer_application_id  VARCHAR(36),
    producer_source          VARCHAR(20),
    row_count_estimate       BIGINT,
    last_ddl_at              TIMESTAMP WITH TIME ZONE,
    last_seen_at             TIMESTAMP WITH TIME ZONE,
    first_seen_at            TIMESTAMP WITH TIME ZONE,
    migration                TEXT,
    description              TEXT,
    tags                     TEXT,
    classification           VARCHAR(20),
    discovered               BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_db_table UNIQUE (database_id, schema_name, name)
);
CREATE INDEX ix_db_table_owner ON db_table (owner_team_id);
CREATE INDEX ix_db_table_name ON db_table (name);

CREATE TABLE db_column (
    id                VARCHAR(36) PRIMARY KEY,
    table_id          VARCHAR(36) NOT NULL REFERENCES db_table (id) ON DELETE CASCADE,
    name              VARCHAR(128) NOT NULL,
    ordinal_position  INTEGER NOT NULL DEFAULT 0,
    data_type         VARCHAR(128),
    char_length       INTEGER,
    num_precision     INTEGER,
    num_scale         INTEGER,
    nullable          BOOLEAN NOT NULL DEFAULT TRUE,
    default_value     TEXT,
    comment           TEXT,
    classification    VARCHAR(20),
    CONSTRAINT uq_db_column UNIQUE (table_id, name)
);

CREATE TABLE routine (
    id                VARCHAR(36) PRIMARY KEY,
    database_id       VARCHAR(36) NOT NULL,
    schema_name       VARCHAR(128) NOT NULL,
    name              VARCHAR(256) NOT NULL,
    kind              VARCHAR(20) NOT NULL,
    trigger_table_id  VARCHAR(36),
    trigger_event     VARCHAR(100),
    owner_team_id     VARCHAR(36),
    status            VARCHAR(20),
    last_ddl_at       TIMESTAMP WITH TIME ZONE,
    last_seen_at      TIMESTAMP WITH TIME ZONE,
    first_seen_at     TIMESTAMP WITH TIME ZONE,
    description       TEXT,
    tags              TEXT,
    discovered        BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_routine UNIQUE (database_id, schema_name, name)
);
CREATE INDEX ix_routine_trigger_table ON routine (trigger_table_id);

CREATE TABLE dependency (
    id             VARCHAR(36) PRIMARY KEY,
    from_type      VARCHAR(20) NOT NULL,
    from_id        VARCHAR(36) NOT NULL,
    to_type        VARCHAR(20) NOT NULL,
    to_id          VARCHAR(36) NOT NULL,
    kind           VARCHAR(20) NOT NULL,
    source         VARCHAR(20) NOT NULL,
    confidence     DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    first_seen_at  TIMESTAMP WITH TIME ZONE,
    last_seen_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_dependency UNIQUE (from_type, from_id, to_type, to_id, kind, source)
);
CREATE INDEX ix_dependency_from ON dependency (from_id);
CREATE INDEX ix_dependency_to ON dependency (to_id);

CREATE TABLE relationship (
    id              VARCHAR(36) PRIMARY KEY,
    application_id  VARCHAR(36) NOT NULL,
    object_type     VARCHAR(20) NOT NULL,
    object_id       VARCHAR(36) NOT NULL,
    kind            VARCHAR(20) NOT NULL,
    source          VARCHAR(30) NOT NULL,
    query_count     BIGINT NOT NULL DEFAULT 0,
    last_seen_at    TIMESTAMP WITH TIME ZONE,
    first_seen_at   TIMESTAMP WITH TIME ZONE,
    confirmed       BOOLEAN NOT NULL DEFAULT FALSE,
    via_routine_id  VARCHAR(36),
    confidence      DOUBLE PRECISION NOT NULL DEFAULT 1.0
);
CREATE INDEX ix_relationship_application ON relationship (application_id);
CREATE INDEX ix_relationship_object ON relationship (object_id);

CREATE TABLE query_stat (
    id                 VARCHAR(36) PRIMARY KEY,
    sql_hash           VARCHAR(64) NOT NULL,
    sql_normalized     TEXT,
    operation          VARCHAR(20),
    application_id     VARCHAR(36),
    database_id        VARCHAR(36),
    datasource_name    VARCHAR(100),
    bucket_start       TIMESTAMP WITH TIME ZONE NOT NULL,
    exec_count         BIGINT NOT NULL DEFAULT 0,
    total_duration_ms  BIGINT NOT NULL DEFAULT 0,
    max_duration_ms    BIGINT NOT NULL DEFAULT 0,
    duration_samples   TEXT,
    row_count          BIGINT NOT NULL DEFAULT 0,
    error_count        BIGINT NOT NULL DEFAULT 0,
    tables_json        TEXT,
    last_seen_at       TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ix_query_stat_bucket ON query_stat (bucket_start);
CREATE INDEX ix_query_stat_hash ON query_stat (sql_hash);

CREATE TABLE query_event_raw (
    event_id          VARCHAR(64) PRIMARY KEY,
    received_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    event_time        TIMESTAMP WITH TIME ZONE,
    gateway_id        VARCHAR(100),
    session_id        VARCHAR(100),
    application_id    VARCHAR(36),
    application_name  VARCHAR(100),
    team_name         VARCHAR(100),
    datasource_name   VARCHAR(100),
    database_id       VARCHAR(36),
    engine            VARCHAR(20),
    sql_hash          VARCHAR(64),
    sql_normalized    TEXT,
    operation         VARCHAR(20),
    tables_json       TEXT,
    routines_json     TEXT,
    columns_json      TEXT,
    duration_ms       BIGINT,
    row_count         BIGINT,
    success           BOOLEAN,
    sql_state         VARCHAR(20),
    error_code        INTEGER,
    error_message     TEXT,
    pinned            BOOLEAN,
    pool_mode         VARCHAR(20),
    client_info       TEXT,
    default_schema    VARCHAR(128)
);
CREATE INDEX ix_query_event_received ON query_event_raw (received_at);

CREATE TABLE connection_event_raw (
    event_id           VARCHAR(64) PRIMARY KEY,
    received_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    event_time         TIMESTAMP WITH TIME ZONE,
    proxy_id           VARCHAR(100),
    event_type         VARCHAR(20),
    listener           VARCHAR(100),
    engine             VARCHAR(20),
    connection_id      VARCHAR(100),
    client_addr        VARCHAR(100),
    client_port        INTEGER,
    proxy_local_addr   VARCHAR(100),
    proxy_local_port   INTEGER,
    backend_host       VARCHAR(255),
    backend_port       INTEGER,
    requested_service  VARCHAR(255),
    resolved_service   VARCHAR(255),
    application_id     VARCHAR(36),
    application_name   VARCHAR(100),
    identity_source    VARCHAR(30),
    datasource_id      VARCHAR(36),
    datasource_name    VARCHAR(100),
    program            VARCHAR(255),
    client_host        VARCHAR(255),
    os_user            VARCHAR(100),
    db_user            VARCHAR(100),
    opened_at          TIMESTAMP WITH TIME ZONE,
    closed_at          TIMESTAMP WITH TIME ZONE,
    duration_ms        BIGINT,
    bytes_in           BIGINT,
    bytes_out          BIGINT,
    reason             TEXT
);
CREATE INDEX ix_connection_event_received ON connection_event_raw (received_at);

CREATE TABLE pool_stats_snapshot (
    id                  VARCHAR(36) PRIMARY KEY,
    recorded_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    event_time          TIMESTAMP WITH TIME ZONE,
    gateway_id          VARCHAR(100),
    datasource_name     VARCHAR(100),
    datasource_id       VARCHAR(36),
    database_id         VARCHAR(36),
    engine              VARCHAR(20),
    active_connections  INTEGER,
    idle_connections    INTEGER,
    waiting_threads     INTEGER,
    total_connections   INTEGER,
    max_connections     INTEGER,
    logical_sessions    INTEGER,
    pinned_sessions     INTEGER,
    credential_version  BIGINT
);
CREATE INDEX ix_pool_stats_recorded ON pool_stats_snapshot (recorded_at);

CREATE TABLE component (
    id                     VARCHAR(36) PRIMARY KEY,
    component_type         VARCHAR(20) NOT NULL,
    component_id           VARCHAR(100) NOT NULL,
    version                VARCHAR(50),
    host                   VARCHAR(255),
    started_at             TIMESTAMP WITH TIME ZONE,
    last_heartbeat         TIMESTAMP WITH TIME ZONE NOT NULL,
    config_version         BIGINT,
    stats_json             TEXT,
    live_connections_json  TEXT,
    CONSTRAINT uq_component UNIQUE (component_type, component_id)
);

CREATE TABLE migration_event (
    id                VARCHAR(36) PRIMARY KEY,
    datasource_id     VARCHAR(36) NOT NULL,
    from_database_id  VARCHAR(36),
    to_database_id    VARCHAR(36),
    occurred_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    actor             VARCHAR(100),
    note              TEXT
);

CREATE TABLE policy (
    id           VARCHAR(36) PRIMARY KEY,
    kind         VARCHAR(50) NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    severity     VARCHAR(10) NOT NULL,
    description  TEXT,
    CONSTRAINT uq_policy_kind UNIQUE (kind)
);

CREATE TABLE violation (
    id              VARCHAR(36) PRIMARY KEY,
    fingerprint     VARCHAR(64) NOT NULL,
    policy_kind     VARCHAR(50) NOT NULL,
    severity        VARCHAR(10) NOT NULL,
    application_id  VARCHAR(36),
    team_id         VARCHAR(36),
    object_type     VARCHAR(20),
    object_id       VARCHAR(36),
    label           VARCHAR(500),
    detail          TEXT,
    first_seen_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    status          VARCHAR(20) NOT NULL,
    CONSTRAINT uq_violation_fingerprint UNIQUE (fingerprint)
);
CREATE INDEX ix_violation_status ON violation (status);

CREATE TABLE collector_state (
    database_id         VARCHAR(36) PRIMARY KEY,
    last_dictionary_run TIMESTAMP WITH TIME ZONE,
    last_runtime_run    TIMESTAMP WITH TIME ZONE,
    last_audit_run      TIMESTAMP WITH TIME ZONE,
    last_error          TEXT,
    tables_seen         INTEGER NOT NULL DEFAULT 0,
    routines_seen       INTEGER NOT NULL DEFAULT 0,
    sessions_seen       INTEGER NOT NULL DEFAULT 0,
    audit_cursor        TIMESTAMP WITH TIME ZONE
);

-- Schema-wide ownership defaults (import "ownership" rows with table = null, POST /tables/bulk-ownership);
-- applied to tables of that schema discovered later by crawlers or telemetry.
CREATE TABLE schema_ownership (
    id           VARCHAR(36) PRIMARY KEY,
    database_id  VARCHAR(36) NOT NULL,
    schema_name  VARCHAR(128) NOT NULL,
    team_id      VARCHAR(36) NOT NULL,
    confirmed    BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_schema_ownership UNIQUE (database_id, schema_name)
);
