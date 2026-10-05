package org.dbplatform.controlplane.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.dbplatform.controlplane.domain.ApiKey;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.service.ApplicationService;
import org.dbplatform.controlplane.service.SummaryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {
    private final ApplicationService applications;
    private final SummaryService summaries;

    public ApplicationController(ApplicationService applications, SummaryService summaries) { this.applications = applications; this.summaries = summaries; }

    @GetMapping public List<Application> list() { return applications.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Application create(@Valid @RequestBody Application body) { return applications.create(body); }
    @GetMapping("/{id}") public Application get(@PathVariable String id) { return applications.get(id); }
    @PutMapping("/{id}") public Application update(@PathVariable String id, @Valid @RequestBody Application body) { return applications.update(id, body); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { applications.delete(id); }
    @GetMapping("/{id}/summary") public SummaryService.ApplicationSummary summary(@PathVariable String id) { return summaries.application(id); }

    @GetMapping("/{id}/api-keys") public List<ApiKey> keys(@PathVariable String id) { return applications.listKeys(id); }

    @PostMapping("/{id}/api-keys") @ResponseStatus(HttpStatus.CREATED)
    public ApplicationService.IssuedKey issue(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return applications.issueKey(id, body == null ? null : body.get("label"));
    }

    @DeleteMapping("/{id}/api-keys/{keyId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, @PathVariable String keyId) { applications.revokeKey(id, keyId); }
}
