package com.assessment.orchestrator.core.model;

public enum StageStatus {
    PENDING,
    AWAITING_APPROVAL,
    AWAITING_CLARIFICATION,
    RUNNING,
    COMPLETED,
    FAILED,
    ROLLED_BACK,
    INVALIDATED   // upstream output changed, so this stage must re-run
}
