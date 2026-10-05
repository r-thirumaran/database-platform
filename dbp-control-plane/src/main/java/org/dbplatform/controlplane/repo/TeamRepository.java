package org.dbplatform.controlplane.repo;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, String> {
    Optional<Team> findByName(String name);
    List<Team> findAllByOrderByNameAsc();
}
