package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Application;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, String> {
    Optional<Application> findByName(String name);
    List<Application> findAllByOrderByNameAsc();
    List<Application> findByTeamId(String teamId);
    long countByTeamId(String teamId);
}
