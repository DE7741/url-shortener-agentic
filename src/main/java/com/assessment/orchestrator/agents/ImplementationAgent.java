package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.agent.AgentContext;
import com.assessment.orchestrator.core.agent.AgentExecutor;
import com.assessment.orchestrator.core.agent.AgentResult;
import com.assessment.orchestrator.core.model.Artifact;
import com.assessment.orchestrator.core.model.DecisionRecord;
import com.assessment.orchestrator.core.model.StageId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Executes the approved change against the live system through the narrow
 * ShortenerOps surface, and registers a compensation (undo) with the engine.
 */
public class ImplementationAgent implements AgentExecutor {

    private final ShortenerOps ops;

    public ImplementationAgent(ShortenerOps ops) {
        this.ops = ops;
    }

    @Override
    public String name() {
        return "implementation-agent";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String scenario = ctx.getScenarioType();
        if ("BROWNFIELD".equals(scenario)) {
            return applyRateLimitChange();
        }
        if ("AMBIGUOUS".equals(scenario)) {
            return applyHardening(String.valueOf(ctx.getClarificationResolution()));
        }
        return applyTtlFeature();
    }

    private AgentResult applyRateLimitChange() {
        final int previousCapacity = ops.getRateLimitCapacity();
        final double previousRefill = ops.getRateLimitRefillPerSecond();
        final int newCapacity = 25;

        ops.setRateLimit(newCapacity, previousRefill);

        String content = "# Implementation: rate limit burst tuning\n\n"
                + "Applied at runtime via TokenBucketRateLimiter#reconfigure.\n\n"
                + "| setting | before | after |\n|---|---|---|\n"
                + "| capacity (burst) | " + previousCapacity + " | " + newCapacity + " |\n"
                + "| refillPerSecond | " + previousRefill + " | " + previousRefill + " (unchanged) |\n";
        Artifact artifact = new Artifact("implementation-notes.md", "markdown", content,
                StageId.IMPLEMENTATION, Instant.now());
        List<DecisionRecord> decisions = Arrays.asList(new DecisionRecord(
                StageId.IMPLEMENTATION, name(), "RATE_LIMIT_RECONFIGURED",
                "burst capacity " + previousCapacity + " -> " + newCapacity
                        + "; refill unchanged per constraint; compensation registered", Instant.now()));

        Runnable compensation = () -> ops.setRateLimit(previousCapacity, previousRefill);
        return AgentResult.successWithCompensation("rate limit reconfigured",
                List.of(artifact), decisions, compensation,
                "restore rate limit to capacity=" + previousCapacity + ", refill=" + previousRefill);
    }

    private AgentResult applyHardening(String resolution) {
        final boolean previousHttpsOnly = ops.isHttpsOnly();
        List<String> applied = new ArrayList<>();

        if (resolution.startsWith("HTTPS_ONLY") || resolution.startsWith("BOTH")) {
            ops.setHttpsOnly(true);
            applied.add("httpsOnly=true");
        }
        if (resolution.startsWith("DOMAIN_BLOCKLIST") || resolution.startsWith("BOTH")) {
            ops.blockDomain("malware.example");
            ops.blockDomain("phishing.example");
            applied.add("blocklist += [malware.example, phishing.example]");
        }

        String content = "# Implementation: URL creation hardening\n\n"
                + "Human-selected interpretation: " + resolution + "\n\n"
                + "Applied settings: " + String.join(", ", applied) + "\n";
        Artifact artifact = new Artifact("implementation-notes.md", "markdown", content,
                StageId.IMPLEMENTATION, Instant.now());
        List<DecisionRecord> decisions = Arrays.asList(new DecisionRecord(
                StageId.IMPLEMENTATION, name(), "HARDENING_APPLIED",
                "applied " + applied + " per human clarification; https-only flag reversible via compensation",
                Instant.now()));

        Runnable compensation = () -> ops.setHttpsOnly(previousHttpsOnly);
        return AgentResult.successWithCompensation("hardening applied",
                List.of(artifact), decisions, compensation,
                "restore httpsOnly=" + previousHttpsOnly);
    }

    private AgentResult applyTtlFeature() {
        // greenfield: exercise the TTL feature end to end and record reference links
        String permanent = ops.createUrl("https://www.example.com/docs", null, null);
        String expiring = ops.createUrl("https://www.example.com/launch", null, 3600L);

        String content = "# Implementation: link expiration (TTL)\n\n"
                + "Feature exposed via POST /api/urls { url, ttlSeconds }. Expiry is enforced lazily in\n"
                + "UrlShortenerService#resolveForRedirect and surfaces as 410 Gone.\n\n"
                + "Reference links created for verification:\n"
                + "- permanent: /r/" + permanent + "\n"
                + "- expiring (3600s): /r/" + expiring + "\n";
        Artifact artifact = new Artifact("implementation-notes.md", "markdown", content,
                StageId.IMPLEMENTATION, Instant.now());
        List<DecisionRecord> decisions = Arrays.asList(new DecisionRecord(
                StageId.IMPLEMENTATION, name(), "TTL_FEATURE_WIRED",
                "created reference links (permanent + 3600s TTL) proving the acceptance path; "
                        + "cleanup registered as compensation", Instant.now()));

        Runnable compensation = () -> {
            ops.deleteUrl(permanent);
            ops.deleteUrl(expiring);
        };
        return AgentResult.successWithCompensation("TTL feature exercised",
                List.of(artifact), decisions, compensation,
                "delete reference links " + permanent + ", " + expiring);
    }
}
