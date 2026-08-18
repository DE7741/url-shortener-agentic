package com.assessment.orchestrator.core.model;

public enum WorkflowStatus {
    RUNNING,
    AWAITING_APPROVAL,
    AWAITING_CLARIFICATION,
    COMPLETED,
    FAILED,
    SAFE_STOPPED
}
