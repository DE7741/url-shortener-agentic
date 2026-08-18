package com.assessment.orchestrator.core.engine;

import com.assessment.orchestrator.core.graph.WorkflowDefinition;
import com.assessment.orchestrator.core.model.Artifact;
import com.assessment.orchestrator.core.model.DecisionRecord;
import com.assessment.orchestrator.core.model.StageExecution;
import com.assessment.orchestrator.core.model.StageId;
import com.assessment.orchestrator.core.model.WorkflowStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Full mutable state of one workflow run. Guarded by the engine's per-workflow lock. */
public class WorkflowState {

    /** A registered undo action for a completed stage's side effects. */
    public static class Compensation {
        private final StageId stage;
        private final String description;
        private final Runnable action;

        public Compensation(StageId stage, String description, Runnable action) {
            this.stage = stage;
            this.description = description;
            this.action = action;
        }

        public StageId getStage() { return stage; }
        public String getDescription() { return description; }
        public Runnable getAction() { return action; }
    }

    /** A pending human clarification request (ambiguous requirements). */
    public static class PendingClarification {
        private final StageId stage;
        private final String question;
        private final List<String> options;

        public PendingClarification(StageId stage, String question, List<String> options) {
            this.stage = stage;
            this.question = question;
            this.options = Collections.unmodifiableList(new ArrayList<>(options));
        }

        public StageId getStage() { return stage; }
        public String getQuestion() { return question; }
        public List<String> getOptions() { return options; }
    }

    private final String id;
    private final WorkflowDefinition definition;
    private final String scenarioType;
    private final Map<String, String> params;
    private final Instant createdAt = Instant.now();

    private volatile String requirement;
    private volatile WorkflowStatus status = WorkflowStatus.RUNNING;
    private volatile Instant endedAt;
    private volatile boolean stopRequested;
    private volatile String statusReason;

    private final Map<StageId, StageExecution> executions = new ConcurrentHashMap<>();
    private final Map<String, Artifact> artifacts = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<DecisionRecord> decisions = Collections.synchronizedList(new ArrayList<>());
    private final Map<StageId, String> approvals = new ConcurrentHashMap<>();        // stage -> approver
    private final Map<StageId, String> pendingApprovals = new ConcurrentHashMap<>(); // stage -> reason
    private final List<Compensation> compensations = Collections.synchronizedList(new ArrayList<>());
    private volatile PendingClarification pendingClarification;
    private volatile String clarificationResolution;

    public WorkflowState(String id, WorkflowDefinition definition, String requirement,
                         String scenarioType, Map<String, String> params) {
        this.id = id;
        this.definition = definition;
        this.requirement = requirement;
        this.scenarioType = scenarioType;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        for (StageId stageId : definition.getNodes().keySet()) {
            executions.put(stageId, new StageExecution(stageId));
        }
    }

    public String getId() { return id; }
    public WorkflowDefinition getDefinition() { return definition; }
    public String getScenarioType() { return scenarioType; }
    public Map<String, String> getParams() { return params; }
    public Instant getCreatedAt() { return createdAt; }

    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }

    public WorkflowStatus getStatus() { return status; }
    public void setStatus(WorkflowStatus status) { this.status = status; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }

    public boolean isStopRequested() { return stopRequested; }
    public void requestStop() { this.stopRequested = true; }

    public Map<StageId, StageExecution> getExecutions() { return executions; }
    public StageExecution execution(StageId stage) { return executions.get(stage); }

    public Map<String, Artifact> getArtifacts() { return artifacts; }
    public List<DecisionRecord> getDecisions() { return decisions; }

    public Map<StageId, String> getApprovals() { return approvals; }
    public Map<StageId, String> getPendingApprovals() { return pendingApprovals; }

    public List<Compensation> getCompensations() { return compensations; }

    public PendingClarification getPendingClarification() { return pendingClarification; }
    public void setPendingClarification(PendingClarification pc) { this.pendingClarification = pc; }

    public String getClarificationResolution() { return clarificationResolution; }
    public void setClarificationResolution(String r) { this.clarificationResolution = r; }

    public boolean isTerminal() {
        return status == WorkflowStatus.COMPLETED
                || status == WorkflowStatus.FAILED
                || status == WorkflowStatus.SAFE_STOPPED;
    }
}
