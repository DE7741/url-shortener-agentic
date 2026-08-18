package com.assessment.orchestrator.core.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One entry of decision lineage: who decided what, at which stage, and why.
 * The ordered list survives retries and re-plans.
 */
public class DecisionRecord {

    private static final AtomicLong SEQ = new AtomicLong();

    private final long id;
    private final StageId stage;
    private final String actor;      // agent name or human identity
    private final String decision;
    private final String rationale;
    private final Instant timestamp;

    public DecisionRecord(StageId stage, String actor, String decision, String rationale, Instant timestamp) {
        this.id = SEQ.incrementAndGet();
        this.stage = stage;
        this.actor = actor;
        this.decision = decision;
        this.rationale = rationale;
        this.timestamp = timestamp;
    }

    public long getId() { return id; }
    public StageId getStage() { return stage; }
    public String getActor() { return actor; }
    public String getDecision() { return decision; }
    public String getRationale() { return rationale; }
    public Instant getTimestamp() { return timestamp; }
}
