package org.dbplatform.controlplane.api;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.dbplatform.controlplane.service.graph.GraphService;
import org.dbplatform.controlplane.service.graph.ImpactService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Graph and impact analysis (docs/control-plane-api.md §8). */
@RestController
@RequestMapping("/api/v1")
public class GraphController {
    private final GraphService graph;
    private final ImpactService impact;

    public GraphController(GraphService graph, ImpactService impact) { this.graph = graph; this.impact = impact; }

    @GetMapping("/graph")
    public GraphService.Graph graph(@RequestParam(required = false) String root, @RequestParam(required = false, defaultValue = "2") int depth,
                                    @RequestParam(required = false) String include, @RequestParam(required = false) String edgeKinds,
                                    @RequestParam(required = false, defaultValue = "500") int limit,
                                    @RequestParam(required = false, defaultValue = "false") boolean includeIndirect) {
        return graph.graph(root, Math.max(0, depth), split(include), split(edgeKinds), Math.max(1, limit), includeIndirect);
    }

    @GetMapping("/impact/table/{id}") public ImpactService.Impact table(@PathVariable String id) { return impact.table(id); }
    @GetMapping("/impact/column/{columnId}") public ImpactService.Impact column(@PathVariable String columnId) { return impact.column(columnId); }
    @GetMapping("/impact/datasource/{id}") public ImpactService.DatasourceImpact datasource(@PathVariable String id) { return impact.datasource(id); }

    private static Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) return null;
        return new LinkedHashSet<>(Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }
}
