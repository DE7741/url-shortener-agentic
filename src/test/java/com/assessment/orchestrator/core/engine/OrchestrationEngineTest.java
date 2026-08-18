package com.assessment.orchestrator.core.engine;

import com.assessment.orchestrator.core.agent.AgentContext;
import com.assessment.orchestrator.core.agent.AgentExecutor;
import com.assessment.orchestrator.core.agent.AgentResult;
import com.assessment.orchestrator.core.audit.AuditLog;
import com.assessment.orchestrator.core.graph.StageNode;
import com.assessment.orchestrator.core.graph.WorkflowDefinition;
import com.assessment.orchestrator.core.metrics.MetricsRegistry;
import com.assessment.orchestrator.core.model.Artifact;
import com.assessment.orchestrator.core.model.StageId;
import com.assessment.orchestrator.core.model.StageStatus;
import com.assessment.orchestrator.core.model.WorkflowStatus;
import com.assessment.orchestrator.core.policy.PolicyEngine;
import com.assessment.orchestrator.core.policy.StandardPolicies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the framework-free orchestration engine (no Spring context). */
class OrchestrationEngineTest {

    private OrchestrationEngine engine;
    private MetricsRegistry metrics;

    @BeforeEach
    void setUp() {
        metrics = new MetricsRegistry();
        engine = new OrchestrationEngine(new PolicyEngine(StandardPolicies.defaults()),
                new AuditLog(null), metrics, 2, 10);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    // helpers

    private static AgentExecutor okAgent(String name, List<String> executionLog) {
        return new AgentExecutor() {
            @Override public String name() { return name; }
            @Override public AgentResult execute(AgentContext ctx) {
                synchronized (executionLog) { executionLog.add(name); }
                return AgentResult.success(name + " done",
                        List.of(new Artifact(name + ".md", "markdown", "output of " + name,
                                StageId.REQUIREMENTS, Instant.now())),
                        Collections.emptyList());
            }
        };
    }

    private static WorkflowDefinition diamond(List<String> executionLog) {
        // A -> (B, C) -> D : a parallel wave with a synchronization barrier
        Map<StageId, StageNode> nodes = new LinkedHashMap<>();
        nodes.put(StageId.REQUIREMENTS,
                StageNode.builder(StageId.REQUIREMENTS, okAgent("A", executionLog)).build());
        nodes.put(StageId.TESTING,
                StageNode.builder(StageId.TESTING, okAgent("B", executionLog))
                        .dependsOn(StageId.REQUIREMENTS).build());
        nodes.put(StageId.DOCUMENTATION,
                StageNode.builder(StageId.DOCUMENTATION, okAgent("C", executionLog))
                        .dependsOn(StageId.REQUIREMENTS).build());
        nodes.put(StageId.RELEASE_READINESS,
                StageNode.builder(StageId.RELEASE_READINESS, okAgent("D", executionLog))
                        .dependsOn(StageId.TESTING, StageId.DOCUMENTATION).build());
        return new WorkflowDefinition("diamond", nodes);
    }

    private void await(WorkflowState state, Predicate<WorkflowState> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test(state)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("condition not met within timeout; status=" + state.getStatus());
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // tests

    @Test
    void executesDependencyGraphWithParallelWaveAndBarrier() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        WorkflowState state = engine.start(diamond(log), "req", "TEST", params());
        await(state, WorkflowState::isTerminal);

        assertEquals(WorkflowStatus.COMPLETED, state.getStatus());
        assertEquals("A", log.get(0));
        assertEquals("D", log.get(3));
        assertTrue(log.subList(1, 3).containsAll(Arrays.asList("B", "C")));
        assertEquals(4, state.getArtifacts().size());
    }

    @Test
    void pausesForApprovalThenResumes() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Map<StageId, StageNode> nodes = new LinkedHashMap<>();
        nodes.put(StageId.REQUIREMENTS,
                StageNode.builder(StageId.REQUIREMENTS, okAgent("A", log)).build());
        nodes.put(StageId.IMPLEMENTATION,
                StageNode.builder(StageId.IMPLEMENTATION, okAgent("B", log))
                        .dependsOn(StageId.REQUIREMENTS).requiresApproval().highImpact().build());
        WorkflowState state = engine.start(new WorkflowDefinition("gated", nodes), "req", "TEST", params());

        await(state, s -> s.getStatus() == WorkflowStatus.AWAITING_APPROVAL);
        assertEquals(StageStatus.AWAITING_APPROVAL,
                state.execution(StageId.IMPLEMENTATION).getStatus());
        assertTrue(log.size() == 1); // B has not run

        engine.approve(state.getId(), StageId.IMPLEMENTATION, "tester", true, "looks good");
        await(state, WorkflowState::isTerminal);
        assertEquals(WorkflowStatus.COMPLETED, state.getStatus());
        assertEquals(Arrays.asList("A", "B"), log);
    }

    @Test
    void approvalRejectionSafeStops() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Map<StageId, StageNode> nodes = new LinkedHashMap<>();
        nodes.put(StageId.IMPLEMENTATION,
                StageNode.builder(StageId.IMPLEMENTATION, okAgent("B", log))
                        .requiresApproval().build());
        WorkflowState state = engine.start(new WorkflowDefinition("gated", nodes), "req", "TEST", params());

        await(state, s -> s.getStatus() == WorkflowStatus.AWAITING_APPROVAL);
        engine.approve(state.getId(), StageId.IMPLEMENTATION, "tester", false, "too risky");
        await(state, WorkflowState::isTerminal);
        assertEquals(WorkflowStatus.SAFE_STOPPED, state.getStatus());
        assertTrue(log.isEmpty());
    }

    @Test
    void boundedRetryRecoversFromTransientFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AgentExecutor flaky = new AgentExecutor() {
            @Override public String name() { return "flaky"; }
            @Override public AgentResult execute(AgentContext ctx) {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("transient");
                return AgentResult.success("ok", Collections.emptyList(), Collections.emptyList());
            }
        };
        Map<StageId, StageNode> nodes = new LinkedHashMap<>();
        nodes.put(StageId.TESTING, StageNode.builder(StageId.TESTING, flaky).build());
        WorkflowState state = engine.start(new WorkflowDefinition("flaky", nodes), "req", "TEST", params());

        await(state, WorkflowState::isTerminal);
        assertEquals(WorkflowStatus.COMPLETED, state.getStatus());
        assertEquals(2, calls.get());
        Map<String, Object> snapshot = metrics.snapshot();
        assertTrue(((Number) snapshot.get("totalRetries")).longValue() >= 1);
        assertTrue(snapshot.get("mttrMillis") != null); // recovery time measured
    }

    @Test
    void persistentFailureTriggersRollbackAndSafeStop() throws Exception {
        AtomicBoolean compensated = new AtomicBoolean(false);
        AgentExecutor changer = new AgentExecutor() {
            @Override public String name() { return "changer"; }
            @Override public AgentResult execute(AgentContext ctx) {
                return AgentResult.successWithCompensation("changed", Collections.emptyList(),
                        Collections.emptyList(), () -> compensated.set(true), "undo change");
            }
        };
        AgentExecutor alwaysFails = new AgentExecutor() {
            @Override public String name() { return "broken"; }
            @Override public AgentResult execute(AgentContext ctx) {
                throw new IllegalStateException("persistent");
            }
        };
        Map<StageId, StageNode> nodes = new LinkedHashMap<>();
        nodes.put(StageId.IMPLEMENTATION, StageNode.builder(StageId.IMPLEMENTATION, changer).build());
        nodes.put(StageId.TESTING, StageNode.builder(StageId.TESTING, alwaysFails)
                .dependsOn(StageId.IMPLEMENTATION).build());
        WorkflowState state = engine.start(new WorkflowDefinition("failing", nodes), "req", "TEST", params());

        await(state, WorkflowState::isTerminal);
        assertEquals(WorkflowStatus.SAFE_STOPPED, state.getStatus());
        assertTrue(compensated.get(), "compensation must run on rollback");
        assertEquals(StageStatus.ROLLED_BACK, state.execution(StageId.IMPLEMENTATION).getStatus());
        assertEquals(3, state.execution(StageId.TESTING).getAttempts()); // 1 + 2 retries
        assertTrue(((Number) metrics.snapshot().get("totalRollbacks")).longValue() >= 1);
    }

    @Test
    void fallbackAgentRecoversAfterRetriesExhausted() throws Exception {
        AgentExecutor alwaysFails = new AgentExecutor() {
            @Override public String name() { return "broken"; }
            @Override public AgentResult execute(AgentContext ctx) {
                throw new IllegalStateException("persistent");
            }
        };
        AgentExecutor fallback = new AgentExecutor() {
            @Override public String name() { return "fallback"; }
            @Override public AgentResult execute(AgentContext ctx) {
                return AgentResult.success("fallback ok", Collections.emptyList(), Collections.emptyList());
            }
        };
        Map<StageId, StageNode> nodes = new LinkedHashMap<>();
        nodes.put(StageId.TESTING,
                StageNode.builder(StageId.TESTING, alwaysFails).fallback(fallback).build());
        WorkflowState state = engine.start(new WorkflowDefinition("fb", nodes), "req", "TEST", params());

        await(state, WorkflowState::isTerminal);
        assertEquals(WorkflowStatus.COMPLETED, state.getStatus());
    }

    @Test
    void clarificationPausesThenResumesAfterHumanInput() throws Exception {
        AgentExecutor ambiguityDetector = new AgentExecutor() {
            @Override public String name() { return "req-agent"; }
            @Override public AgentResult execute(AgentContext ctx) {
                if (ctx.getClarificationResolution() == null) {
                    return AgentResult.clarificationNeeded("which one?",
                            Arrays.asList("option-1", "option-2"), Collections.emptyList());
                }
                return AgentResult.success("resolved as " + ctx.getClarificationResolution(),
                        Collections.emptyList(), Collections.emptyList());
            }
        };
        Map<StageId, StageNode> nodes = new LinkedHashMap<>();
        nodes.put(StageId.REQUIREMENTS,
                StageNode.builder(StageId.REQUIREMENTS, ambiguityDetector).build());
        WorkflowState state = engine.start(new WorkflowDefinition("amb", nodes), "make it safer", "TEST", params());

        await(state, s -> s.getStatus() == WorkflowStatus.AWAITING_CLARIFICATION);
        assertEquals(2, state.getPendingClarification().getOptions().size());

        engine.resolveClarification(state.getId(), "option-1", "tester");
        await(state, WorkflowState::isTerminal);
        assertEquals(WorkflowStatus.COMPLETED, state.getStatus());
        assertTrue(state.getDecisions().stream()
                .anyMatch(d -> d.getDecision().startsWith("AMBIGUITY_RESOLVED")));
    }

    @Test
    void replanInvalidatesAndRerunsAllStages() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        WorkflowState state = engine.start(diamond(log), "original req", "TEST", params());
        await(state, WorkflowState::isTerminal);
        assertEquals(4, log.size());

        engine.replan(state.getId(), "amended req", "tester");
        await(state, s -> s.isTerminal() && log.size() == 8);
        assertEquals(WorkflowStatus.COMPLETED, state.getStatus());
        assertEquals("amended req", state.getRequirement());
        assertTrue(state.getDecisions().stream().anyMatch(d -> "REPLAN".equals(d.getDecision())));
    }

    @Test
    void exitGateBlocksArtifactContainingSecret() throws Exception {
        AgentExecutor leaky = new AgentExecutor() {
            @Override public String name() { return "leaky"; }
            @Override public AgentResult execute(AgentContext ctx) {
                return AgentResult.success("done",
                        List.of(new Artifact("leak.md", "markdown",
                                "config: api_key = sk-123456 do not share",
                                StageId.IMPLEMENTATION, Instant.now())),
                        Collections.emptyList());
            }
        };
        Map<StageId, StageNode> nodes = new LinkedHashMap<>();
        nodes.put(StageId.IMPLEMENTATION, StageNode.builder(StageId.IMPLEMENTATION, leaky).build());
        WorkflowState state = engine.start(new WorkflowDefinition("leak", nodes), "req", "TEST", params());

        await(state, WorkflowState::isTerminal);
        assertEquals(WorkflowStatus.SAFE_STOPPED, state.getStatus());
        assertTrue(state.getArtifacts().isEmpty(), "leaky artifact must not be committed");
    }

    @Test
    void autoApproveModeStillRecordsGovernance() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Map<StageId, StageNode> nodes = new LinkedHashMap<>();
        nodes.put(StageId.IMPLEMENTATION,
                StageNode.builder(StageId.IMPLEMENTATION, okAgent("B", log))
                        .requiresApproval().highImpact().build());
        WorkflowState state = engine.start(new WorkflowDefinition("auto", nodes), "req", "TEST",
                params("autoApprove", "true"));

        await(state, WorkflowState::isTerminal);
        assertEquals(WorkflowStatus.COMPLETED, state.getStatus());
        assertTrue(state.getDecisions().stream()
                .anyMatch(d -> d.getDecision().startsWith("AUTO_APPROVED")));
    }
}
