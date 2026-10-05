package org.dbplatform.controlplane.api;

import jakarta.validation.Valid;
import java.util.List;
import org.dbplatform.controlplane.domain.AccessGrant;
import org.dbplatform.controlplane.service.AccessGrantService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/access-grants")
public class AccessGrantController {
    private final AccessGrantService grants;

    public AccessGrantController(AccessGrantService grants) { this.grants = grants; }

    @GetMapping
    public List<AccessGrant> list(@RequestParam(required = false) String applicationId, @RequestParam(required = false) String datasourceId) {
        return grants.list(blank(applicationId), blank(datasourceId));
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public AccessGrant create(@Valid @RequestBody AccessGrant body) { return grants.create(body); }
    @GetMapping("/{id}") public AccessGrant get(@PathVariable String id) { return grants.get(id); }
    @PutMapping("/{id}") public AccessGrant update(@PathVariable String id, @Valid @RequestBody AccessGrant body) { return grants.update(id, body); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { grants.delete(id); }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}
