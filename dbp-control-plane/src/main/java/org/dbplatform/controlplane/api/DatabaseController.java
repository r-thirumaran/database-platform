package org.dbplatform.controlplane.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.collector.CollectorScheduler;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.service.DatabaseService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/databases")
public class DatabaseController {
    private final DatabaseService databases;
    private final CollectorScheduler collectors;

    public DatabaseController(DatabaseService databases, CollectorScheduler collectors) { this.databases = databases; this.collectors = collectors; }

    @GetMapping public List<DatabaseInstance> list() { return databases.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public DatabaseInstance create(@Valid @RequestBody DatabaseInstance body) { return databases.create(body); }
    @GetMapping("/{id}") public DatabaseInstance get(@PathVariable String id) { return databases.get(id); }
    @PutMapping("/{id}") public DatabaseInstance update(@PathVariable String id, @Valid @RequestBody DatabaseInstance body) { return databases.update(id, body); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { databases.delete(id); }

    @PostMapping("/{id}/test-connection") public DatabaseService.TestResult test(@PathVariable String id) { return databases.testConnection(id); }

    @PostMapping("/{id}/collect")
    public Map<String, Object> collect(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        String what = body == null ? null : body.get("what");
        Enums.CollectWhat w;
        try {
            w = what == null ? Enums.CollectWhat.DICTIONARY : Enums.CollectWhat.valueOf(what.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException.BadRequest("what must be DICTIONARY, RUNTIME or AUDIT");
        }
        boolean started = collectors.trigger(databases.get(id), w);
        return Map.of("started", started, "what", w.name());
    }

    @GetMapping("/{id}/schemas") public List<DatabaseService.SchemaInfo> schemas(@PathVariable String id) { return databases.schemas(id); }

    @GetMapping("/{id}/collector-status") public CollectorScheduler.Status status(@PathVariable String id) { return collectors.status(databases.get(id)); }
}
