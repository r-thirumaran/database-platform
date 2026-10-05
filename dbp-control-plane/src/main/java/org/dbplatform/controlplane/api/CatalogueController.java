package org.dbplatform.controlplane.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.dbplatform.controlplane.api.dto.Paged;
import org.dbplatform.controlplane.api.dto.RoutineUpdateRequest;
import org.dbplatform.controlplane.api.dto.TableUpdateRequest;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.DbColumn;
import org.dbplatform.controlplane.domain.DbTable;
import org.dbplatform.controlplane.domain.Dependency;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Relationship;
import org.dbplatform.controlplane.domain.Routine;
import org.dbplatform.controlplane.service.CatalogueService;
import org.dbplatform.controlplane.service.SummaryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Tables, columns, routines, dependencies and relationships (docs/control-plane-api.md §7). */
@RestController
@RequestMapping("/api/v1")
public class CatalogueController {
    private final CatalogueService catalogue;
    private final SummaryService summaries;

    public CatalogueController(CatalogueService catalogue, SummaryService summaries) { this.catalogue = catalogue; this.summaries = summaries; }

    // ---- tables
    @GetMapping("/tables")
    public Object tables(@RequestParam(required = false) String databaseId, @RequestParam(required = false) String schema,
                         @RequestParam(required = false) String q, @RequestParam(required = false) String ownerTeamId,
                         @RequestParam(required = false, defaultValue = "false") boolean unowned,
                         @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paged.of(catalogue.searchTables(databaseId, schema, q, ownerTeamId, unowned), page, size);
    }
    @GetMapping("/tables/{id}") public DbTable table(@PathVariable String id) { return catalogue.getTable(id); }
    @PutMapping("/tables/{id}") public DbTable updateTable(@PathVariable String id, @RequestBody TableUpdateRequest body) { return catalogue.updateTable(id, body); }
    @GetMapping("/tables/{id}/columns") public List<DbColumn> columns(@PathVariable String id) { return catalogue.columns(id); }
    @PutMapping("/tables/{id}/columns/{columnId}")
    public DbColumn updateColumn(@PathVariable String id, @PathVariable String columnId, @RequestBody Map<String, String> body) {
        return catalogue.updateColumn(id, columnId, body.get("comment"), body.get("classification"));
    }
    @GetMapping("/tables/{id}/summary") public SummaryService.TableSummary tableSummary(@PathVariable String id) { return summaries.table(id); }
    @PostMapping("/tables/{id}/ownership")
    public DbTable ownership(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object confirmed = body.get("confirmed");
        return catalogue.setOwnership(id, (String) body.get("teamId"), confirmed == null ? null : Boolean.valueOf(confirmed.toString()));
    }
    @PostMapping("/tables/bulk-ownership")
    public Map<String, Object> bulkOwnership(@RequestBody Map<String, String> body) {
        String db = body.get("databaseId"), schema = body.get("schema"), team = body.get("teamId");
        if (db == null || schema == null || team == null) throw new ApiException.BadRequest("databaseId, schema and teamId are required");
        int n = catalogue.bulkOwnership(db, schema, team);
        return Map.of("updated", n);
    }

    // ---- routines
    @GetMapping("/routines")
    public Object routines(@RequestParam(required = false) String databaseId, @RequestParam(required = false) String schema,
                           @RequestParam(required = false) Enums.RoutineKind kind, @RequestParam(required = false) String q,
                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paged.of(catalogue.searchRoutines(databaseId, schema, kind, q), page, size);
    }
    @GetMapping("/routines/{id}") public Routine routine(@PathVariable String id) { return catalogue.getRoutine(id); }
    @PutMapping("/routines/{id}") public Routine updateRoutine(@PathVariable String id, @RequestBody RoutineUpdateRequest body) { return catalogue.updateRoutine(id, body); }
    @PostMapping("/routines/{id}/ownership")
    public Routine routineOwnership(@PathVariable String id, @RequestBody Map<String, String> body) { return catalogue.setRoutineOwnership(id, body.get("teamId")); }
    @GetMapping("/routines/{id}/summary") public SummaryService.RoutineSummary routineSummary(@PathVariable String id) { return summaries.routine(id); }

    // ---- dependencies
    @GetMapping("/dependencies")
    public List<Dependency> dependencies(@RequestParam(required = false) String fromId, @RequestParam(required = false) String toId,
                                         @RequestParam(required = false) Enums.DependencyKind kind) {
        return catalogue.searchDependencies(fromId, toId, kind);
    }
    @PostMapping("/dependencies") @ResponseStatus(HttpStatus.CREATED)
    public Dependency declareDependency(@Valid @RequestBody Dependency body) { return catalogue.declareDependency(body); }
    @DeleteMapping("/dependencies/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDependency(@PathVariable String id) { catalogue.deleteDependency(id); }

    // ---- relationships
    @GetMapping("/relationships")
    public List<Relationship> relationships(@RequestParam(required = false) String applicationId, @RequestParam(required = false) String objectId,
                                            @RequestParam(required = false) Enums.RelationshipKind kind, @RequestParam(required = false) Enums.RelationshipSource source) {
        return catalogue.searchRelationships(applicationId, objectId, kind, source);
    }
    @PostMapping("/relationships") @ResponseStatus(HttpStatus.CREATED)
    public Relationship declareRelationship(@Valid @RequestBody Relationship body) { return catalogue.declareRelationship(body); }
    @PutMapping("/relationships/{id}")
    public Relationship updateRelationship(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object c = body.get("confirmed");
        if (c == null) throw new ApiException.BadRequest("'confirmed' is required");
        return catalogue.setRelationshipConfirmed(id, Boolean.parseBoolean(c.toString()));
    }
    @DeleteMapping("/relationships/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRelationship(@PathVariable String id) { catalogue.deleteRelationship(id); }
}
