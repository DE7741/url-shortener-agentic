package com.assessment.orchestrator.core.engine;

import com.assessment.orchestrator.core.agent.AgentContext;
import com.assessment.orchestrator.core.agent.AgentResult;
import com.assessment.orchestrator.core.audit.AuditLog;
import com.assessment.orchestrator.core.graph.StageNode;
import com.assessment.orchestrator.core.graph.WorkflowDefinition;
import com.assessment.orchestrator.core.metrics.MetricsRegistry;
import com.assessment.orchestrator.core.model.Artifact;
import com.assessment.orchestrator.core.model.DecisionRecord;
import com.assessment.orchestrator.core.model.StageExecution;
import com.assessment.orchestrator.core.model.StageId;
import com.assessment.orchestrator.core.model.StageStatus;
import com.assessment.orchestrator.core.model.WorkflowStatus;
import com.assessment.orchestrator.core.policy.PolicyEngine;
import com.assessment.orchestrator.core.policy.PolicyViolation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Stateful, non-linear workflow coordinator.
 *
 * Each iteration: find the stages whose dependencies are complete, run them through
 * the entry gates (human approval, then policy), execute the gated-in stages in
 * parallel, synchronize, then run exit gates (artifact policy scan) before committing
 * outputs. Failures get bounded retries with backoff, then a fallback agent if one is
 * configured, then rollback and safe-stop. The loop parks whenever human input is
 * needed and resumes when it arrives.
 *
 * The autonomy boundary: agents never mutate workflow state directly. They return
 * results, and the engine commits them only after gates pass.
 */
public class OrchestrationEngine {

    private final PolicyEngine policyEngine;
    private final AuditLog auditLog;
    private final MetricsRegistry metrics;
    private final int maxRetries;
    private final long retryBackoffMillis;

    private final ExecutorService stagePool = Executors.newFixedThreadPool(4);
    private final ExecutorService coordinatorPool = Executors.newCachedThreadPool();
    private final Map<String, WorkflowState> workflows = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public OrchestrationEngine(PolicyEngine policyEngine, AuditLog auditLog,
                               MetricsRegistry metrics, int maxRetries, long retryBackoffMillis) {
        this.policyEngine = policyEngine;
        this.auditLog = auditLog;
        this.metrics = metrics;
        this.maxRetries = maxRetries;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    public WorkflowState start(WorkflowDefinition definition, String requirement,
                               String scenarioType, Map<String, String> params) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        WorkflowState state = new WorkflowState(id, definition, requirement, scenarioType,
                params == null ? new HashMap<>() : params);
        workflows.put(id, state);
        metrics.workflowStarted(id);
        audit(state, null, "engine", "WORKFLOW_STARTED",
                definition.getName() + " | requirement: " + requirement);
        scheduleAdvance(state);
        return state;
    }

    public WorkflowState get(String workflowId) {
        WorkflowState state = workflows.get(workflowId);
        if (state == null) throw new IllegalArgumentException("unknown workflow: " + workflowId);
        return state;
    }

    public List<WorkflowState> all() {
        return new ArrayList<>(workflows.values());
    }

    /** Human approval checkpoint. Rejection safe-stops the workflow. */
    public void approve(String workflowId, StageId stage, String approver, boolean approved, String comment) {
        WorkflowState state = get(workflowId);
        synchronized (lockFor(workflowId)) {
            if (!state.getPendingApprovals().containsKey(stage)) {
                throw new IllegalStateException("no pending approval for stage " + stage);
            }
            state.getPendingApprovals().remove(stage);
            String detail = (comment == null || comment.isBlank()) ? "" : " - " + comment;
            if (approved) {
                state.getApprovals().put(stage, approver);
                state.getDecisions().add(new DecisionRecord(stage, approver, "APPROVED " + stage,
                        "human approval checkpoint" + detail, Instant.now()));
                audit(state, stage, approver, "APPROVAL_GRANTED", "stage approved" + detail);
                state.execution(stage).setStatus(StageStatus.PENDING);
                state.setStatus(WorkflowStatus.RUNNING);
                scheduleAdvance(state);
            } else {
                state.getDecisions().add(new DecisionRecord(stage, approver, "REJECTED " + stage,
                        "human approval checkpoint" + detail, Instant.now()));
                audit(state, stage, approver, "APPROVAL_REJECTED", "stage rejected" + detail);
                safeStop(state, "approval rejected by " + approver, true);
            }
        }
    }

    /** Human resolution of an ambiguity surfaced by an agent. Invalidates downstream stages. */
    public void resolveClarification(String workflowId, String chosenOption, String resolver) {
        WorkflowState state = get(workflowId);
        synchronized (lockFor(workflowId)) {
            WorkflowState.PendingClarification pc = state.getPendingClarification();
            if (pc == null) throw new IllegalStateException("no pending clarification");
            state.setClarificationResolution(chosenOption);
            state.setPendingClarification(null);
            state.getDecisions().add(new DecisionRecord(pc.getStage(), resolver,
                    "AMBIGUITY_RESOLVED: " + chosenOption,
                    "human selected interpretation for: " + pc.getQuestion(), Instant.now()));
            audit(state, pc.getStage(), resolver, "CLARIFICATION_RESOLVED", chosenOption);
            invalidateDownstream(state, pc.getStage(), "clarification changed upstream output");
            state.execution(pc.getStage()).setStatus(StageStatus.PENDING);
            state.setStatus(WorkflowStatus.RUNNING);
            scheduleAdvance(state);
        }
    }

    /**
     * Dynamic re-planning: the requirement changed, so every stage is invalidated and
     * re-executed. Lineage, audit trail, and metrics persist across the re-plan.
     */
    public void replan(String workflowId, String newRequirement, String actor) {
        WorkflowState state = get(workflowId);
        synchronized (lockFor(workflowId)) {
            audit(state, null, actor, "REPLAN_REQUESTED", "new requirement: " + newRequirement);
            state.setRequirement(newRequirement);
            state.setClarificationResolution(null);
            state.setPendingClarification(null);
            state.getPendingApprovals().clear();
            for (StageNode node : state.getDefinition().getNodes().values()) {
                StageExecution exec = state.execution(node.getId());
                if (exec.getStatus() != StageStatus.PENDING) {
                    exec.setStatus(StageStatus.INVALIDATED);
                    audit(state, node.getId(), "engine", "STAGE_INVALIDATED", "requirement changed");
                }
                exec.resetForRerun();
                // approvals are content-specific: a changed plan needs fresh approval
                state.getApprovals().remove(node.getId());
            }
            removeArtifacts(state, state.getDefinition().getNodes().keySet());
            state.getCompensations().clear();
            state.getDecisions().add(new DecisionRecord(null, actor, "REPLAN",
                    "requirement amended; all stages invalidated and re-queued", Instant.now()));
            if (state.isTerminal()) {
                metrics.workflowStarted(state.getId()); // new measurement window
            }
            state.setEndedAt(null);
            state.setStatus(WorkflowStatus.RUNNING);
            scheduleAdvance(state);
        }
    }

    /** Cooperative safe-stop; honored at the next stage boundary. */
    public void requestStop(String workflowId, String actor) {
        WorkflowState state = get(workflowId);
        state.requestStop();
        audit(state, null, actor, "STOP_REQUESTED", "safe-stop will occur at next stage boundary");
        synchronized (lockFor(workflowId)) {
            if (!state.isTerminal() && state.getStatus() != WorkflowStatus.RUNNING) {
                // parked workflow: stop immediately
                safeStop(state, "stop requested by " + actor, false);
            }
        }
    }

    // core loop

    private void scheduleAdvance(WorkflowState state) {
        coordinatorPool.submit(() -> {
            synchronized (lockFor(state.getId())) {
                try {
                    advanceLoop(state);
                } catch (Exception e) {
                    audit(state, null, "engine", "ENGINE_ERROR", String.valueOf(e.getMessage()));
                    endWorkflow(state, WorkflowStatus.FAILED, "engine error: " + e.getMessage());
                }
            }
        });
    }

    private void advanceLoop(WorkflowState state) {
        while (true) {
            if (state.isTerminal()) return;
            if (state.isStopRequested()) {
                safeStop(state, "stop requested", false);
                return;
            }

            List<StageNode> ready = readyStages(state);
            if (ready.isEmpty()) {
                if (allCompleted(state)) {
                    endWorkflow(state, WorkflowStatus.COMPLETED, "all stages completed");
                }
                return; // parked (awaiting approval/clarification) or finished
            }

            List<StageNode> executable = new ArrayList<>();
            for (StageNode node : ready) {
                if (!passesApprovalGate(state, node)) continue;
                boolean approvalGranted = state.getApprovals().containsKey(node.getId());
                Optional<PolicyViolation> violation = policyEngine.checkEntry(node, approvalGranted);
                if (violation.isPresent()) {
                    audit(state, node.getId(), "policy-engine", "POLICY_VIOLATION", violation.get().toString());
                    safeStop(state, "entry policy violation: " + violation.get(), true);
                    return;
                }
                executable.add(node);
            }
            if (executable.isEmpty()) return; // everything ready is awaiting approval

            runBatch(state, executable);

            boolean anyFailed = false;
            for (StageNode node : executable) {
                if (state.execution(node.getId()).getStatus() == StageStatus.FAILED) anyFailed = true;
            }
            if (anyFailed) {
                safeStop(state, "stage failed after bounded retries and fallback", true);
                return;
            }
            if (state.getPendingClarification() != null) {
                state.setStatus(WorkflowStatus.AWAITING_CLARIFICATION);
                return; // parked until a human resolves it
            }
            // otherwise loop for the next wave of ready stages
        }
    }

    private List<StageNode> readyStages(WorkflowState state) {
        List<StageNode> ready = new ArrayList<>();
        for (StageNode node : state.getDefinition().getNodes().values()) {
            StageExecution exec = state.execution(node.getId());
            if (exec.getStatus() != StageStatus.PENDING) continue;
            boolean depsDone = true;
            for (StageId dep : node.getDependsOn()) {
                if (state.execution(dep).getStatus() != StageStatus.COMPLETED) {
                    depsDone = false;
                    break;
                }
            }
            if (depsDone) ready.add(node);
        }
        return ready;
    }

    /** Returns true if the stage may execute now; otherwise parks it awaiting approval. */
    private boolean passesApprovalGate(WorkflowState state, StageNode node) {
        if (!node.isRequiresApproval()) return true;
        if (state.getApprovals().containsKey(node.getId())) return true;

        boolean autoApprove = Boolean.parseBoolean(
                state.getParams().getOrDefault("autoApprove", "false"));
        if (autoApprove) {
            state.getApprovals().put(node.getId(), "auto-approver");
            state.getDecisions().add(new DecisionRecord(node.getId(), "auto-approver",
                    "AUTO_APPROVED " + node.getId(),
                    "demo mode: approval checkpoint auto-granted (still audited)", Instant.now()));
            audit(state, node.getId(), "auto-approver", "APPROVAL_AUTO_GRANTED", "demo mode");
            return true;
        }
        state.getPendingApprovals().put(node.getId(),
                (node.isHighImpact() ? "high-impact change: " : "") + "human approval required before "
                        + node.getId());
        state.execution(node.getId()).setStatus(StageStatus.AWAITING_APPROVAL);
        state.setStatus(WorkflowStatus.AWAITING_APPROVAL);
        audit(state, node.getId(), "engine", "APPROVAL_REQUESTED", "workflow parked for human decision");
        return false;
    }

    /** Executes a wave of independent stages in parallel, then synchronizes. */
    private void runBatch(WorkflowState state, List<StageNode> batch) {
        StringBuilder names = new StringBuilder();
        for (StageNode n : batch) {
            if (names.length() > 0) names.append(", ");
            names.append(n.getId());
        }
        audit(state, null, "engine", "BATCH_DISPATCHED", "parallel wave: [" + names + "]");

        Map<StageNode, Future<StageOutcome>> futures = new LinkedHashMap<>();
        for (StageNode node : batch) {
            StageExecution exec = state.execution(node.getId());
            exec.setStatus(StageStatus.RUNNING);
            exec.setStartedAt(Instant.now());
            audit(state, node.getId(), node.getAgent().name(), "STAGE_STARTED", "attempt window opened");
            futures.put(node, stagePool.submit(() -> runStageWithRetries(state, node)));
        }
        for (Map.Entry<StageNode, Future<StageOutcome>> entry : futures.entrySet()) {
            StageNode node = entry.getKey();
            StageExecution exec = state.execution(node.getId());
            StageOutcome outcome;
            try {
                outcome = entry.getValue().get();
            } catch (Exception e) {
                outcome = StageOutcome.failed("executor error: " + e.getMessage());
            }
            applyOutcome(state, node, exec, outcome);
        }
    }

    private void applyOutcome(WorkflowState state, StageNode node, StageExecution exec, StageOutcome outcome) {
        exec.setEndedAt(Instant.now());
        if (outcome.type == StageOutcome.Type.CLARIFICATION) {
            exec.setStatus(StageStatus.AWAITING_CLARIFICATION);
            state.setPendingClarification(new WorkflowState.PendingClarification(
                    node.getId(), outcome.result.getClarificationQuestion(),
                    outcome.result.getClarificationOptions()));
            commitDecisions(state, outcome.result.getDecisions());
            audit(state, node.getId(), node.getAgent().name(), "CLARIFICATION_REQUESTED",
                    outcome.result.getClarificationQuestion());
            return;
        }
        if (outcome.type == StageOutcome.Type.FAILED) {
            exec.setStatus(StageStatus.FAILED);
            exec.setLastError(outcome.error);
            audit(state, node.getId(), node.getAgent().name(), "STAGE_FAILED", outcome.error);
            return;
        }

        // exit gate: scan the artifacts before committing anything
        Optional<PolicyViolation> violation =
                policyEngine.checkArtifacts(node, outcome.result.getArtifacts());
        if (violation.isPresent()) {
            exec.setStatus(StageStatus.FAILED);
            exec.setLastError(violation.get().toString());
            audit(state, node.getId(), "policy-engine", "POLICY_VIOLATION",
                    "exit gate blocked commit: " + violation.get());
            return;
        }

        for (Artifact a : outcome.result.getArtifacts()) {
            state.getArtifacts().put(a.getName(), a);
        }
        commitDecisions(state, outcome.result.getDecisions());
        if (outcome.result.getCompensation() != null) {
            state.getCompensations().add(new WorkflowState.Compensation(
                    node.getId(), outcome.result.getCompensationDescription(),
                    outcome.result.getCompensation()));
            audit(state, node.getId(), node.getAgent().name(), "COMPENSATION_REGISTERED",
                    outcome.result.getCompensationDescription());
        }
        exec.setStatus(StageStatus.COMPLETED);
        audit(state, node.getId(), node.getAgent().name(), "STAGE_COMPLETED", outcome.result.getSummary());
    }

    private StageOutcome runStageWithRetries(WorkflowState state, StageNode node) {
        StageExecution exec = state.execution(node.getId());
        boolean hadFailure = false;
        int attemptsAllowed = maxRetries + 1;

        for (int attempt = 1; attempt <= attemptsAllowed; attempt++) {
            exec.incrementAttempts();
            try {
                AgentResult result = node.getAgent().execute(buildContext(state, exec.getAttempts()));
                if (result.isNeedsClarification()) {
                    return StageOutcome.clarification(result);
                }
                if (result.isSuccess()) {
                    if (hadFailure) {
                        metrics.recoveryRecorded(state.getId());
                        audit(state, node.getId(), node.getAgent().name(), "STAGE_RECOVERED",
                                "succeeded on attempt " + attempt);
                    }
                    return StageOutcome.success(result);
                }
                throw new IllegalStateException(result.getSummary());
            } catch (Exception e) {
                hadFailure = true;
                exec.setLastError(String.valueOf(e.getMessage()));
                metrics.failureRecorded(state.getId());
                audit(state, node.getId(), node.getAgent().name(), "ATTEMPT_FAILED",
                        "attempt " + attempt + "/" + attemptsAllowed + ": " + e.getMessage());
                if (attempt < attemptsAllowed) {
                    metrics.retryRecorded(state.getId());
                    audit(state, node.getId(), "engine", "RETRY_SCHEDULED",
                            "backoff " + (retryBackoffMillis * attempt) + "ms");
                    sleep(retryBackoffMillis * attempt);
                }
            }
        }

        // retries exhausted, try the fallback path if there is one
        if (node.getFallbackAgent() != null) {
            audit(state, node.getId(), node.getFallbackAgent().name(), "FALLBACK_INVOKED",
                    "primary agent exhausted retries");
            try {
                AgentResult result = node.getFallbackAgent().execute(buildContext(state, exec.getAttempts()));
                if (result.isSuccess()) {
                    metrics.recoveryRecorded(state.getId());
                    audit(state, node.getId(), node.getFallbackAgent().name(), "STAGE_RECOVERED",
                            "fallback agent succeeded");
                    return StageOutcome.success(result);
                }
            } catch (Exception e) {
                audit(state, node.getId(), node.getFallbackAgent().name(), "FALLBACK_FAILED",
                        String.valueOf(e.getMessage()));
            }
        }
        return StageOutcome.failed("retries and fallback exhausted: " + exec.getLastError());
    }

    // helpers

    private AgentContext buildContext(WorkflowState state, int attempt) {
        Map<String, Artifact> artifactsCopy;
        synchronized (state.getArtifacts()) {
            artifactsCopy = new LinkedHashMap<>(state.getArtifacts());
        }
        return new AgentContext(state.getId(), state.getRequirement(), state.getScenarioType(),
                artifactsCopy, state.getParams(), attempt, state.getClarificationResolution());
    }

    private void invalidateDownstream(WorkflowState state, StageId changed, String reason) {
        Set<StageId> downstream = state.getDefinition().downstreamOf(changed);
        for (StageId stageId : downstream) {
            StageExecution exec = state.execution(stageId);
            if (exec.getStatus() == StageStatus.COMPLETED) {
                exec.setStatus(StageStatus.INVALIDATED);
                audit(state, stageId, "engine", "STAGE_INVALIDATED", reason);
            }
            exec.resetForRerun();
            state.getApprovals().remove(stageId);
        }
        removeArtifacts(state, downstream);
    }

    private void removeArtifacts(WorkflowState state, Set<StageId> stages) {
        synchronized (state.getArtifacts()) {
            state.getArtifacts().values().removeIf(a -> stages.contains(a.getProducedBy()));
        }
    }

    /** Runs registered compensations in reverse order, then ends the workflow safely. */
    private void safeStop(WorkflowState state, String reason, boolean rollback) {
        if (rollback && !state.getCompensations().isEmpty()) {
            metrics.rollbackRecorded(state.getId());
            List<WorkflowState.Compensation> comps = new ArrayList<>(state.getCompensations());
            Collections.reverse(comps);
            for (WorkflowState.Compensation c : comps) {
                try {
                    c.getAction().run();
                    state.execution(c.getStage()).setStatus(StageStatus.ROLLED_BACK);
                    audit(state, c.getStage(), "engine", "ROLLBACK_EXECUTED", c.getDescription());
                } catch (Exception e) {
                    audit(state, c.getStage(), "engine", "ROLLBACK_FAILED", String.valueOf(e.getMessage()));
                }
            }
            state.getCompensations().clear();
        }
        endWorkflow(state, WorkflowStatus.SAFE_STOPPED, reason);
    }

    private void endWorkflow(WorkflowState state, WorkflowStatus status, String reason) {
        state.setStatus(status);
        state.setStatusReason(reason);
        state.setEndedAt(Instant.now());
        metrics.workflowEnded(state.getId(), status.name());
        audit(state, null, "engine", "WORKFLOW_" + status.name(), reason);
    }

    private void commitDecisions(WorkflowState state, List<DecisionRecord> decisions) {
        state.getDecisions().addAll(decisions);
    }

    private void audit(WorkflowState state, StageId stage, String actor, String action, String detail) {
        auditLog.record(state.getId(), stage, actor, action, detail);
    }

    private Object lockFor(String workflowId) {
        return locks.computeIfAbsent(workflowId, k -> new Object());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        stagePool.shutdownNow();
        coordinatorPool.shutdownNow();
    }

    private static class StageOutcome {
        enum Type { SUCCESS, FAILED, CLARIFICATION }

        final Type type;
        final AgentResult result;
        final String error;

        private StageOutcome(Type type, AgentResult result, String error) {
            this.type = type;
            this.result = result;
            this.error = error;
        }

        static StageOutcome success(AgentResult r) { return new StageOutcome(Type.SUCCESS, r, null); }
        static StageOutcome failed(String error) { return new StageOutcome(Type.FAILED, null, error); }
        static StageOutcome clarification(AgentResult r) { return new StageOutcome(Type.CLARIFICATION, r, null); }
    }

    private boolean allCompleted(WorkflowState state) {
        for (StageExecution exec : state.getExecutions().values()) {
            if (exec.getStatus() != StageStatus.COMPLETED) return false;
        }
        return true;
    }
}
