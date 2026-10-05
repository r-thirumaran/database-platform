package org.dbplatform.controlplane.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.Datasource;
import org.dbplatform.controlplane.domain.RoutingRule;
import org.dbplatform.controlplane.service.DatasourceService;
import org.dbplatform.controlplane.service.SummaryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/datasources")
public class DatasourceController {
    private final DatasourceService datasources;
    private final SummaryService summaries;

    public DatasourceController(DatasourceService datasources, SummaryService summaries) { this.datasources = datasources; this.summaries = summaries; }

    @GetMapping public List<Datasource> list() { return datasources.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Datasource create(@Valid @RequestBody Datasource body) { return datasources.create(body); }
    @GetMapping("/{id}") public Datasource get(@PathVariable String id) { return datasources.get(id); }
    @PutMapping("/{id}") public Datasource update(@PathVariable String id, @Valid @RequestBody Datasource body) { return datasources.update(id, body); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { datasources.delete(id); }

    @PutMapping("/{id}/routing-rules") public Datasource replaceRules(@PathVariable String id, @RequestBody List<@Valid RoutingRule> rules) { return datasources.replaceRules(id, rules); }
    @PostMapping("/{id}/routing-rules") @ResponseStatus(HttpStatus.CREATED) public Datasource addRule(@PathVariable String id, @Valid @RequestBody RoutingRule rule) { return datasources.addRule(id, rule); }
    @DeleteMapping("/{id}/routing-rules/{ruleId}") public Datasource deleteRule(@PathVariable String id, @PathVariable String ruleId) { return datasources.deleteRule(id, ruleId); }

    @PostMapping("/{id}/switch")
    public Datasource switchDatabase(@PathVariable String id, @RequestBody Map<String, String> body) {
        String dbId = body.get("databaseId");
        if (dbId == null || dbId.isBlank()) throw new ApiException.BadRequest("databaseId is required");
        return datasources.switchDatabase(id, dbId, body.getOrDefault("by", "api"), body.get("note"));
    }

    @GetMapping("/{id}/summary") public SummaryService.DatasourceSummary summary(@PathVariable String id) { return summaries.datasource(id); }
}
