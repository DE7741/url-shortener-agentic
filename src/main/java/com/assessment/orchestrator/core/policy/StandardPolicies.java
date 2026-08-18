package com.assessment.orchestrator.core.policy;

import com.assessment.orchestrator.core.graph.StageNode;
import com.assessment.orchestrator.core.model.Artifact;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Built-in guardrails: secret scanning and change control. */
public final class StandardPolicies {

    private StandardPolicies() {}

    public static List<Policy> defaults() {
        return Arrays.asList(secretLeakScan(), changeControl());
    }

    /**
     * Security guardrail: nothing that looks like a credential may leave a stage
     * in an artifact. Stands in for enterprise DLP/secret scanning.
     */
    public static Policy secretLeakScan() {
        final Pattern secretPattern = Pattern.compile(
                "(api[_-]?key|secret|password|bearer\\s+[a-z0-9]{16,})\\s*[:=]\\s*\\S+",
                Pattern.CASE_INSENSITIVE);
        return new Policy() {
            @Override
            public String name() { return "secret-leak-scan"; }

            @Override
            public Optional<PolicyViolation> checkArtifacts(StageNode node, List<Artifact> artifacts) {
                for (Artifact a : artifacts) {
                    if (a.getContent() != null && secretPattern.matcher(a.getContent()).find()) {
                        return Optional.of(new PolicyViolation(name(), node.getId(),
                                "artifact '" + a.getName() + "' appears to contain a credential"));
                    }
                }
                return Optional.empty();
            }
        };
    }

    /**
     * Change-control guardrail: a high-impact stage may never execute without an
     * approval on record. Defense in depth behind the engine's approval gate.
     */
    public static Policy changeControl() {
        return new Policy() {
            @Override
            public String name() { return "change-control"; }

            @Override
            public Optional<PolicyViolation> checkEntry(StageNode node, boolean approvalGranted) {
                if (node.isHighImpact() && !approvalGranted) {
                    return Optional.of(new PolicyViolation(name(), node.getId(),
                            "high-impact stage " + node.getId().name().toLowerCase(Locale.ROOT)
                                    + " requires an approval on record"));
                }
                return Optional.empty();
            }
        };
    }
}
