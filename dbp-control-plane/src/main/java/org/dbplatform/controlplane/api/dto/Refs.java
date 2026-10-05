package org.dbplatform.controlplane.api.dto;

import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Routine;

/** Compact references used inside summaries. */
public final class Refs {
    private Refs() {}

    public record TableRef(String id, String databaseId, String schema, String name, Enums.TableKind kind, String label) {
        public static TableRef of(DbTable t) {
            return t == null ? null : new TableRef(t.getId(), t.getDatabaseId(), t.getSchema(), t.getName(), t.getKind(), t.label());
        }
    }

    public record RoutineRef(String id, String databaseId, String schema, String name, Enums.RoutineKind kind, String label) {
        public static RoutineRef of(Routine r) {
            return r == null ? null : new RoutineRef(r.getId(), r.getDatabaseId(), r.getSchema(), r.getName(), r.getKind(), r.label());
        }
    }

    public record DatasourceRef(String id, String name, Enums.DatasourceState state) {
        public static DatasourceRef of(Datasource d) {
            return d == null ? null : new DatasourceRef(d.getId(), d.getName(), d.getState());
        }
    }
}
