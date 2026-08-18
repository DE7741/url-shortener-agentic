package com.assessment.orchestrator.core.graph;

import com.assessment.orchestrator.core.agent.AgentExecutor;
import com.assessment.orchestrator.core.model.StageId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** A node in the workflow dependency graph. */
public class StageNode {

    private final StageId id;
    private final Set<StageId> dependsOn;
    private final AgentExecutor agent;
    private final AgentExecutor fallbackAgent;  // optional degraded-mode executor
    private final boolean requiresApproval;     // human entry gate
    private final boolean highImpact;           // flagged for change-control policy

    private StageNode(StageId id, Set<StageId> dependsOn, AgentExecutor agent,
                      AgentExecutor fallbackAgent, boolean requiresApproval, boolean highImpact) {
        this.id = id;
        this.dependsOn = Collections.unmodifiableSet(new LinkedHashSet<>(dependsOn));
        this.agent = agent;
        this.fallbackAgent = fallbackAgent;
        this.requiresApproval = requiresApproval;
        this.highImpact = highImpact;
    }

    public static Builder builder(StageId id, AgentExecutor agent) {
        return new Builder(id, agent);
    }

    public StageId getId() { return id; }
    public Set<StageId> getDependsOn() { return dependsOn; }
    public AgentExecutor getAgent() { return agent; }
    public AgentExecutor getFallbackAgent() { return fallbackAgent; }
    public boolean isRequiresApproval() { return requiresApproval; }
    public boolean isHighImpact() { return highImpact; }

    public static class Builder {
        private final StageId id;
        private final AgentExecutor agent;
        private final Set<StageId> dependsOn = new LinkedHashSet<>();
        private AgentExecutor fallbackAgent;
        private boolean requiresApproval;
        private boolean highImpact;

        private Builder(StageId id, AgentExecutor agent) {
            this.id = id;
            this.agent = agent;
        }

        public Builder dependsOn(StageId... ids) {
            for (StageId s : ids) dependsOn.add(s);
            return this;
        }

        public Builder fallback(AgentExecutor fallback) {
            this.fallbackAgent = fallback;
            return this;
        }

        public Builder requiresApproval() {
            this.requiresApproval = true;
            return this;
        }

        public Builder highImpact() {
            this.highImpact = true;
            return this;
        }

        public StageNode build() {
            return new StageNode(id, dependsOn, agent, fallbackAgent, requiresApproval, highImpact);
        }
    }
}
