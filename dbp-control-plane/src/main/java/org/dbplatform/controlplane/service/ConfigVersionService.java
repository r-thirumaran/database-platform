package org.dbplatform.controlplane.service;

import org.dbplatform.controlplane.repo.ConfigVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Single-row counter bumped on every configuration change; gateways/proxies poll it. */
@Service
public class ConfigVersionService {
    private final ConfigVersionRepository repo;

    public ConfigVersionService(ConfigVersionRepository repo) { this.repo = repo; }

    @Transactional(propagation = Propagation.REQUIRED)
    public void bump() { repo.bump(); }

    @Transactional(readOnly = true)
    public long current() {
        Long v = repo.current();
        return v == null ? 0 : v;
    }
}
