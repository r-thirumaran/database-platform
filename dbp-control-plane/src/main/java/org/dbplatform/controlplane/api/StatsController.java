package org.dbplatform.controlplane.api;

import java.util.List;
import org.dbplatform.controlplane.api.dto.Refs.TableRef;
import org.dbplatform.controlplane.domain.MigrationEvent;
import org.dbplatform.controlplane.domain.PoolStatsSnapshot;
import org.dbplatform.controlplane.repo.MigrationEventRepository;
import org.dbplatform.controlplane.service.StatsService;
import org.dbplatform.controlplane.service.telemetry.ComponentService;
import org.dbplatform.controlplane.service.telemetry.LiveConnection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Observability (docs/control-plane-api.md §11–12). */
@RestController
@RequestMapping("/api/v1")
public class StatsController {
    private final StatsService stats;
    private final ComponentService components;
    private final MigrationEventRepository migrationEvents;

    public StatsController(StatsService stats, ComponentService components, MigrationEventRepository migrationEvents) {
        this.stats = stats; this.components = components; this.migrationEvents = migrationEvents;
    }

    @GetMapping("/stats/overview") public StatsService.Overview overview() { return stats.overview(); }

    @GetMapping("/stats/connections")
    public List<StatsService.GroupedConnections> connections(@RequestParam(required = false, defaultValue = "application") String groupBy) { return stats.connectionsGroupedBy(groupBy); }

    @GetMapping("/stats/queries/top")
    public List<StatsService.QueryStatView> topQueries(@RequestParam(required = false, defaultValue = "count") String by, @RequestParam(required = false, defaultValue = "24h") String window,
                                                       @RequestParam(required = false) String databaseId, @RequestParam(required = false) String applicationId,
                                                       @RequestParam(required = false, defaultValue = "50") int limit) {
        return stats.topQueries(by, window, databaseId, applicationId, limit);
    }

    @GetMapping("/stats/tables/hot")
    public List<StatsService.HotTable> hot(@RequestParam(required = false, defaultValue = "24h") String window, @RequestParam(required = false, defaultValue = "50") int limit) {
        return stats.hotTables(window, limit);
    }

    @GetMapping("/stats/tables/unused")
    public List<TableRef> unused(@RequestParam(required = false, defaultValue = "30") int days) { return stats.unusedTables(days); }

    @GetMapping("/stats/pools") public List<PoolStatsSnapshot> pools() { return stats.pools(); }

    @GetMapping("/connections/live") public List<LiveConnection> live() { return stats.liveConnections(); }

    @GetMapping("/migration-events")
    public List<MigrationEvent> migrationEvents(@RequestParam(required = false) String datasourceId) {
        return datasourceId == null || datasourceId.isBlank() ? migrationEvents.findAllByOrderByAtDesc() : migrationEvents.findByDatasourceIdOrderByAtDesc(datasourceId);
    }

    @GetMapping("/components") public List<ComponentService.ComponentView> components() { return components.list(); }
}
