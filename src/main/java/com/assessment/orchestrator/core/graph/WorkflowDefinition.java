package com.assessment.orchestrator.core.graph;

import com.assessment.orchestrator.core.model.StageId;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** An explicit, validated (closed and acyclic) dependency graph of SDLC stages. */
public class WorkflowDefinition {

    private final String name;
    private final Map<StageId, StageNode> nodes;

    public WorkflowDefinition(String name, Map<StageId, StageNode> nodes) {
        this.name = name;
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        validate();
    }

    public String getName() { return name; }
    public Map<StageId, StageNode> getNodes() { return nodes; }

    /** All stages that (transitively) depend on the given stage. Used for re-planning. */
    public Set<StageId> downstreamOf(StageId stage) {
        Set<StageId> result = new HashSet<>();
        Deque<StageId> queue = new ArrayDeque<>();
        queue.add(stage);
        while (!queue.isEmpty()) {
            StageId current = queue.poll();
            for (StageNode node : nodes.values()) {
                if (node.getDependsOn().contains(current) && result.add(node.getId())) {
                    queue.add(node.getId());
                }
            }
        }
        return result;
    }

    private void validate() {
        // dependencies must reference nodes that exist in the graph
        for (StageNode node : nodes.values()) {
            for (StageId dep : node.getDependsOn()) {
                if (!nodes.containsKey(dep)) {
                    throw new IllegalArgumentException("stage " + node.getId()
                            + " depends on missing stage " + dep);
                }
            }
        }
        // acyclicity check via Kahn's algorithm
        Map<StageId, Integer> inDegree = new HashMap<>();
        for (StageNode node : nodes.values()) {
            inDegree.put(node.getId(), node.getDependsOn().size());
        }
        Deque<StageId> ready = new ArrayDeque<>();
        for (Map.Entry<StageId, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());
        }
        int visited = 0;
        while (!ready.isEmpty()) {
            StageId current = ready.poll();
            visited++;
            for (StageNode node : nodes.values()) {
                if (node.getDependsOn().contains(current)) {
                    int d = inDegree.merge(node.getId(), -1, Integer::sum);
                    if (d == 0) ready.add(node.getId());
                }
            }
        }
        if (visited != nodes.size()) {
            throw new IllegalArgumentException("workflow graph contains a cycle");
        }
    }
}
