package org.dbplatform.controlplane.api.internal;

import java.util.List;
import java.util.Map;
import org.dbplatform.common.controlplane.ConfigVersion;
import org.dbplatform.common.telemetry.ConnectionEvent;
import org.dbplatform.common.telemetry.PoolStats;
import org.dbplatform.common.telemetry.QueryEvent;
import org.dbplatform.controlplane.service.telemetry.ComponentService;
import org.dbplatform.controlplane.service.telemetry.TelemetryIngestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Telemetry ingestion and heartbeats (docs/control-plane-api.md §9). */
@RestController
@RequestMapping("/api/v1/internal")
public class InternalTelemetryController {
    private final TelemetryIngestService ingest;
    private final ComponentService components;

    public InternalTelemetryController(TelemetryIngestService ingest, ComponentService components) { this.ingest = ingest; this.components = components; }

    @PostMapping("/telemetry/queries")
    public ResponseEntity<Map<String, Integer>> queries(@RequestBody List<QueryEvent> events) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("accepted", ingest.ingestQueries(events)));
    }

    @PostMapping("/telemetry/connections")
    public ResponseEntity<Map<String, Integer>> connections(@RequestBody List<ConnectionEvent> events) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("accepted", ingest.ingestConnections(events)));
    }

    @PostMapping("/telemetry/pools")
    public ResponseEntity<Map<String, Integer>> pools(@RequestBody List<PoolStats> stats) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("accepted", ingest.ingestPools(stats)));
    }

    @PostMapping("/heartbeat")
    public ConfigVersion heartbeat(@RequestBody Map<String, Object> body) {
        return new ConfigVersion(components.heartbeat(body));
    }
}
