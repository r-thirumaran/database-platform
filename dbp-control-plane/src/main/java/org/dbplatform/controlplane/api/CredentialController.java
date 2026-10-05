package org.dbplatform.controlplane.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.dbplatform.controlplane.api.dto.CredentialRequest;
import org.dbplatform.controlplane.domain.Credential;
import org.dbplatform.controlplane.service.CredentialService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/credentials")
public class CredentialController {
    private final CredentialService credentials;

    public CredentialController(CredentialService credentials) { this.credentials = credentials; }

    @GetMapping public List<Credential> list() { return credentials.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Credential create(@Valid @RequestBody CredentialRequest body) { return credentials.create(body); }
    @GetMapping("/{id}") public Credential get(@PathVariable String id) { return credentials.get(id); }
    @PutMapping("/{id}") public Credential update(@PathVariable String id, @Valid @RequestBody CredentialRequest body) { return credentials.update(id, body); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { credentials.delete(id); }

    @PostMapping("/{id}/rotate")
    public Credential rotate(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return credentials.rotate(id, body == null ? null : body.get("secret"));
    }
}
