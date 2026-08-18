package com.assessment.orchestrator.core.policy;

import com.assessment.orchestrator.core.graph.StageNode;
import com.assessment.orchestrator.core.model.Artifact;

import java.util.List;
import java.util.Optional;

/**
 * A guardrail evaluated by the engine. Entry checks run before a stage executes
 * (change control); artifact checks run on stage outputs (content scanning).
 */
public interface Policy {

    String name();

    /** Checked at stage entry. Return a violation to block execution. */
    default Optional<PolicyViolation> checkEntry(StageNode node, boolean approvalGranted) {
        return Optional.empty();
    }

    /** Checked on artifacts a stage produced, before they are committed. */
    default Optional<PolicyViolation> checkArtifacts(StageNode node, List<Artifact> artifacts) {
        return Optional.empty();
    }
}
