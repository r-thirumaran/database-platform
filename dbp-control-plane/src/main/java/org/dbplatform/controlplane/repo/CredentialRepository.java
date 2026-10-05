package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Credential;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CredentialRepository extends JpaRepository<Credential, String> {
    Optional<Credential> findByName(String name);
    List<Credential> findAllByOrderByNameAsc();
}
