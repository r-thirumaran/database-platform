package org.dbplatform.controlplane.api;

import jakarta.validation.Valid;
import java.util.List;
import org.dbplatform.controlplane.domain.Team;
import org.dbplatform.controlplane.service.SummaryService;
import org.dbplatform.controlplane.service.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {
    private final TeamService teams;
    private final SummaryService summaries;

    public TeamController(TeamService teams, SummaryService summaries) { this.teams = teams; this.summaries = summaries; }

    @GetMapping public List<Team> list() { return teams.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Team create(@Valid @RequestBody Team body) { return teams.create(body); }
    @GetMapping("/{id}") public Team get(@PathVariable String id) { return teams.get(id); }
    @PutMapping("/{id}") public Team update(@PathVariable String id, @Valid @RequestBody Team body) { return teams.update(id, body); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { teams.delete(id); }
    @GetMapping("/{id}/summary") public SummaryService.TeamSummary summary(@PathVariable String id) { return summaries.team(id); }
}
