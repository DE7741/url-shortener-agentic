package com.assessment.orchestrator.core.audit;

import com.assessment.orchestrator.core.model.StageId;

import java.time.Instant;

/** One immutable, append-only audit record. */
public class AuditEvent {

    private final Instant timestamp;
    private final String workflowId;
    private final StageId stage;   // may be null for workflow-level events
    private final String actor;    // "engine", an agent name, or a human identity
    private final String action;   // e.g. STAGE_STARTED, APPROVAL_GRANTED, ROLLBACK
    private final String detail;

    public AuditEvent(Instant timestamp, String workflowId, StageId stage,
                      String actor, String action, String detail) {
        this.timestamp = timestamp;
        this.workflowId = workflowId;
        this.stage = stage;
        this.actor = actor;
        this.action = action;
        this.detail = detail;
    }

    public Instant getTimestamp() { return timestamp; }
    public String getWorkflowId() { return workflowId; }
    public StageId getStage() { return stage; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
}
