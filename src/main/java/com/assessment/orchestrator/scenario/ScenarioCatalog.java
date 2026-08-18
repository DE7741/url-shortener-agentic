package com.assessment.orchestrator.scenario;

import com.assessment.orchestrator.agents.ArchitectureAgent;
import com.assessment.orchestrator.agents.DocumentationAgent;
import com.assessment.orchestrator.agents.ImpactAnalysisAgent;
import com.assessment.orchestrator.agents.ImplementationAgent;
import com.assessment.orchestrator.agents.ReleaseReadinessAgent;
import com.assessment.orchestrator.agents.RequirementsAgent;
import com.assessment.orchestrator.agents.ShortenerOps;
import com.assessment.orchestrator.agents.TestingAgent;
import com.assessment.orchestrator.core.agent.AgentExecutor;
import com.assessment.orchestrator.core.graph.StageNode;
import com.assessment.orchestrator.core.graph.WorkflowDefinition;
import com.assessment.orchestrator.core.model.StageId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Builds the workflow graph for each scenario.
 *
 * Greenfield/ambiguous:
 *   REQUIREMENTS -> ARCHITECTURE -> IMPLEMENTATION -> {TESTING, DOCUMENTATION in parallel} -> RELEASE_READINESS
 *
 * Brownfield adds IMPACT_ANALYSIS between REQUIREMENTS and ARCHITECTURE.
 * IMPLEMENTATION and RELEASE_READINESS carry a human approval checkpoint and are
 * flagged high-impact for the change-control policy.
 */
public class ScenarioCatalog {

    private final ShortenerOps ops;
    private final UnaryOperator<AgentExecutor> llmWrapper; // identity when the LLM is disabled

    public ScenarioCatalog(ShortenerOps ops, UnaryOperator<AgentExecutor> llmWrapper) {
        this.ops = ops;
        this.llmWrapper = llmWrapper == null ? UnaryOperator.identity() : llmWrapper;
    }

    public String defaultRequirement(ScenarioType type) {
        switch (type) {
            case BROWNFIELD:
                return "Legitimate burst traffic on redirects is rejected with 429. Allow bursts of 25 "
                        + "requests on the redirect rate limit while keeping the sustained rate unchanged.";
            case AMBIGUOUS:
                return "Make short links safer.";
            case GREENFIELD:
            default:
                return "Add link expiration: a short link may carry an optional TTL after which "
                        + "redirects must stop resolving.";
        }
    }

    public WorkflowDefinition build(ScenarioType type) {
        Map<StageId, StageNode> nodes = new LinkedHashMap<>();

        AgentExecutor requirements = llmWrapper.apply(new RequirementsAgent());
        AgentExecutor architecture = llmWrapper.apply(new ArchitectureAgent());
        AgentExecutor implementation = llmWrapper.apply(new ImplementationAgent(ops));
        AgentExecutor testing = new TestingAgent(ops); // live probes stay deterministic
        AgentExecutor documentation = llmWrapper.apply(new DocumentationAgent());
        AgentExecutor readiness = new ReleaseReadinessAgent();

        nodes.put(StageId.REQUIREMENTS,
                StageNode.builder(StageId.REQUIREMENTS, requirements).build());

        StageId architectureDep = StageId.REQUIREMENTS;
        if (type == ScenarioType.BROWNFIELD) {
            nodes.put(StageId.IMPACT_ANALYSIS,
                    StageNode.builder(StageId.IMPACT_ANALYSIS, new ImpactAnalysisAgent())
                            .dependsOn(StageId.REQUIREMENTS).build());
            architectureDep = StageId.IMPACT_ANALYSIS;
        }

        nodes.put(StageId.ARCHITECTURE,
                StageNode.builder(StageId.ARCHITECTURE, architecture)
                        .dependsOn(architectureDep).build());

        nodes.put(StageId.IMPLEMENTATION,
                StageNode.builder(StageId.IMPLEMENTATION, implementation)
                        .dependsOn(StageId.ARCHITECTURE)
                        .requiresApproval()   // human checkpoint before system-altering work
                        .highImpact()         // change-control policy applies
                        .build());

        // parallel wave after implementation
        nodes.put(StageId.TESTING,
                StageNode.builder(StageId.TESTING, testing)
                        .dependsOn(StageId.IMPLEMENTATION).build());
        nodes.put(StageId.DOCUMENTATION,
                StageNode.builder(StageId.DOCUMENTATION, documentation)
                        .dependsOn(StageId.IMPLEMENTATION).build());

        // synchronization barrier: both parallel paths must complete
        nodes.put(StageId.RELEASE_READINESS,
                StageNode.builder(StageId.RELEASE_READINESS, readiness)
                        .dependsOn(StageId.TESTING, StageId.DOCUMENTATION)
                        .requiresApproval()   // human sign-off before "release"
                        .highImpact()
                        .build());

        return new WorkflowDefinition(type.name().toLowerCase() + "-url-shortener", nodes);
    }
}
