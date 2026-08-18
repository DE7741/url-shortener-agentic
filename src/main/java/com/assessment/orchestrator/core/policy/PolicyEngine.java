package com.assessment.orchestrator.core.policy;

import com.assessment.orchestrator.core.graph.StageNode;
import com.assessment.orchestrator.core.model.Artifact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Evaluates all registered policies; the first violation blocks. */
public class PolicyEngine {

    private final List<Policy> policies;

    public PolicyEngine(List<Policy> policies) {
        this.policies = Collections.unmodifiableList(new ArrayList<>(policies));
    }

    public Optional<PolicyViolation> checkEntry(StageNode node, boolean approvalGranted) {
        for (Policy p : policies) {
            Optional<PolicyViolation> v = p.checkEntry(node, approvalGranted);
            if (v.isPresent()) return v;
        }
        return Optional.empty();
    }

    public Optional<PolicyViolation> checkArtifacts(StageNode node, List<Artifact> artifacts) {
        for (Policy p : policies) {
            Optional<PolicyViolation> v = p.checkArtifacts(node, artifacts);
            if (v.isPresent()) return v;
        }
        return Optional.empty();
    }

    public List<String> policyNames() {
        List<String> names = new ArrayList<>();
        for (Policy p : policies) names.add(p.name());
        return names;
    }
}
