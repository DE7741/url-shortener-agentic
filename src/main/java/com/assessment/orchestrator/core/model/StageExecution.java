package com.assessment.orchestrator.core.model;

import java.time.Instant;

/** Mutable execution record for a stage within one workflow. */
public class StageExecution {

    private final StageId stageId;
    private volatile StageStatus status = StageStatus.PENDING;
    private volatile int attempts = 0;
    private volatile Instant startedAt;
    private volatile Instant endedAt;
    private volatile String lastError;

    public StageExecution(StageId stageId) {
        this.stageId = stageId;
    }

    public StageId getStageId() { return stageId; }
    public StageStatus getStatus() { return status; }
    public void setStatus(StageStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void incrementAttempts() { this.attempts++; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public void resetForRerun() {
        this.status = StageStatus.PENDING;
        this.startedAt = null;
        this.endedAt = null;
        this.lastError = null;
        // attempts are kept on purpose: they're part of the workflow's history and metrics
    }
}
