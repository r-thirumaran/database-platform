package org.dbplatform.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.service.ImportExportService;
import org.dbplatform.controlplane.service.seed.DemoSeedService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Import / export / seed (docs/control-plane-api.md §13). */
@RestController
@RequestMapping("/api/v1")
public class ImportExportController {
    private final ImportExportService importExport;
    private final DemoSeedService seed;

    public ImportExportController(ImportExportService importExport, DemoSeedService seed) { this.importExport = importExport; this.seed = seed; }

    @GetMapping("/export") public ObjectNode export() { return importExport.export(); }

    @PostMapping("/import")
    public Map<String, Object> importDoc(@RequestBody JsonNode doc) {
        ImportExportService.ImportResult r = importExport.importDocument(doc);
        return Map.of("ok", true, "imported", r);
    }

    @PostMapping("/seed/demo")
    public Map<String, Object> seedDemo() {
        if (!seed.enabled()) throw new ApiException.Forbidden("Demo seed is disabled (DBP_DEMO_SEED_ENABLED=false)");
        DemoSeedService.SeedResult r = seed.seed();
        return Map.of("ok", true, "seeded", true, "teams", r.teams(), "applications", r.applications(), "databases", r.databases(),
                "datasources", r.datasources(), "tables", r.tables(), "routines", r.routines(), "relationships", r.relationships());
    }
}
