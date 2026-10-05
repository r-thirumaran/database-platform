package org.dbplatform.controlplane.service;

import java.time.Instant;
import java.util.List;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.domain.Team;
import org.dbplatform.controlplane.repo.ApplicationRepository;
import org.dbplatform.controlplane.repo.DatasourceRepository;
import org.dbplatform.controlplane.repo.DbTableRepository;
import org.dbplatform.controlplane.repo.RoutineRepository;
import org.dbplatform.controlplane.repo.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TeamService {
    private final TeamRepository teams;
    private final ApplicationRepository applications;
    private final DbTableRepository tables;
    private final RoutineRepository routines;
    private final DatasourceRepository datasources;
    private final ConfigVersionService configVersion;

    public TeamService(TeamRepository teams, ApplicationRepository applications, DbTableRepository tables,
                       RoutineRepository routines, DatasourceRepository datasources, ConfigVersionService configVersion) {
        this.teams = teams; this.applications = applications; this.tables = tables;
        this.routines = routines; this.datasources = datasources; this.configVersion = configVersion;
    }

    @Transactional(readOnly = true)
    public List<Team> list() { return teams.findAllByOrderByNameAsc(); }

    @Transactional(readOnly = true)
    public Team get(String id) { return teams.findById(id).orElseThrow(() -> new ApiException.NotFound("Team", id)); }

    public Team create(Team in) {
        teams.findByName(in.getName()).ifPresent(t -> { throw new ApiException.Conflict("Team '" + in.getName() + "' already exists"); });
        Team t = new Team();
        t.setId(Ids.newId());
        t.setCreatedAt(Instant.now());
        copy(in, t);
        configVersion.bump();
        return teams.save(t);
    }

    public Team update(String id, Team in) {
        Team t = get(id);
        teams.findByName(in.getName()).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> { throw new ApiException.Conflict("Team '" + in.getName() + "' already exists"); });
        copy(in, t);
        configVersion.bump();
        return teams.save(t);
    }

    /** Upsert by name (import / seed). */
    public Team upsertByName(Team in) {
        return teams.findByName(in.getName()).map(existing -> update(existing.getId(), in)).orElseGet(() -> create(in));
    }

    public void delete(String id) {
        Team t = get(id);
        if (applications.countByTeamId(id) > 0) {
            throw new ApiException.Conflict("Team '" + t.getName() + "' still has applications; move or delete them first");
        }
        tables.findByOwnerTeamId(id).forEach(tb -> { tb.setOwnerTeamId(null); tb.setOwnerConfirmed(false); tb.setOwnerSource(org.dbplatform.controlplane.domain.Enums.OwnerSource.NONE); });
        routines.findByOwnerTeamId(id).forEach(r -> r.setOwnerTeamId(null));
        datasources.findByOwnerTeamId(id).forEach(d -> d.setOwnerTeamId(null));
        teams.delete(t);
        configVersion.bump();
    }

    private static void copy(Team in, Team t) {
        t.setName(in.getName());
        t.setDisplayName(in.getDisplayName() != null ? in.getDisplayName() : in.getName());
        t.setDescription(in.getDescription());
        t.setContacts(in.getContacts());
        t.setTags(in.getTags());
        t.setUpdatedAt(Instant.now());
    }
}
