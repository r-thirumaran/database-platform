package org.dbplatform.controlplane.api;

import java.util.List;
import java.util.Map;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Policy;
import org.dbplatform.controlplane.domain.Violation;
import org.dbplatform.controlplane.service.governance.GovernanceService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/governance")
public class GovernanceController {
    private final GovernanceService governance;

    public GovernanceController(GovernanceService governance) { this.governance = governance; }

    @GetMapping("/policies") public List<Policy> policies() { return governance.listPolicies(); }
    @PutMapping("/policies/{id}") public Policy updatePolicy(@PathVariable String id, @RequestBody Policy body) { return governance.updatePolicy(id, body); }

    @GetMapping("/violations")
    public List<Violation> violations(@RequestParam(required = false) Enums.ViolationStatus status) { return governance.listViolations(status); }

    @PutMapping("/violations/{id}")
    public Violation updateViolation(@PathVariable String id, @RequestBody Map<String, String> body) {
        String s = body.get("status");
        if (s == null) throw new ApiException.BadRequest("'status' is required (OPEN, ACKNOWLEDGED or RESOLVED)");
        try {
            return governance.updateViolation(id, Enums.ViolationStatus.valueOf(s.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new ApiException.BadRequest("status must be OPEN, ACKNOWLEDGED or RESOLVED");
        }
    }

    @PostMapping("/evaluate") public GovernanceService.EvaluationResult evaluate() { return governance.evaluate(); }
}
