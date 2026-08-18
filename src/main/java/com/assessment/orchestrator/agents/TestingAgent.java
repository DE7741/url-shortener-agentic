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
 * Runs live probes against the running system (not simulated assertions) and fails
 * the stage if any acceptance criterion is not met.
 *
 * Supports controlled failure injection so the reliability controls can be shown
 * on demand: failureInjection=transient makes attempt 1 fail so the retry recovers;
 * failureInjection=persistent makes every attempt fail, which drives the fallback,
 * rollback, and safe-stop path.
 */
public class TestingAgent implements AgentExecutor {

    private final ShortenerOps ops;

    public TestingAgent(ShortenerOps ops) {
        this.ops = ops;
    }

    @Override
    public String name() {
        return "testing-agent";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws Exception {
        String injection = ctx.param("failureInjection", "none");
        if ("persistent".equals(injection)) {
            throw new IllegalStateException("injected persistent failure (demo of rollback + safe-stop)");
        }
        if ("transient".equals(injection) && ctx.getAttempt() <= 1) {
            throw new IllegalStateException("injected transient failure (demo of bounded retry)");
        }

        List<String> results = new ArrayList<>();
        boolean allPassed = true;
        String scenario = ctx.getScenarioType();

        if ("BROWNFIELD".equals(scenario)) {
            int allowed = ops.rateLimitBurstAllowance(25);
            allPassed &= check(results, "burst of 25 fully admitted", allowed == 25,
                    "admitted " + allowed + "/25");
            allPassed &= check(results, "sustained refill unchanged",
                    ops.getRateLimitRefillPerSecond() > 0, "refill=" + ops.getRateLimitRefillPerSecond());
        } else if ("AMBIGUOUS".equals(scenario)) {
            String resolution = String.valueOf(ctx.getClarificationResolution());
            if (resolution.startsWith("HTTPS_ONLY") || resolution.startsWith("BOTH")) {
                boolean rejected;
                try {
                    ops.createUrl("http://insecure.example.com/page", null, null);
                    rejected = false;
                } catch (RuntimeException e) {
                    rejected = true;
                }
                allPassed &= check(results, "http:// URL rejected", rejected, null);
            }
            if (resolution.startsWith("DOMAIN_BLOCKLIST") || resolution.startsWith("BOTH")) {
                boolean rejected;
                try {
                    ops.createUrl("https://malware.example/payload", null, null);
                    rejected = false;
                } catch (RuntimeException e) {
                    rejected = true;
                }
                allPassed &= check(results, "blocklisted domain rejected", rejected, null);
            }
            String ok = ops.createUrl("https://www.example.com/safe", null, null);
            allPassed &= check(results, "valid https link still creatable", ok != null, "code=" + ok);
            ops.deleteUrl(ok);
        } else {
            // greenfield: TTL behavior
            String code = ops.createUrl("https://www.example.com/ttl-probe", null, 1L);
            allPassed &= check(results, "link with ttl created", code != null, "code=" + code);
            String resolved = ops.resolve(code);
            allPassed &= check(results, "link resolves before expiry",
                    "https://www.example.com/ttl-probe".equals(resolved), null);
            Thread.sleep(1200); // let the 1s TTL lapse
            boolean expired;
            try {
                ops.resolve(code);
                expired = false;
            } catch (RuntimeException e) {
                expired = true;
            }
            allPassed &= check(results, "expired link no longer resolves", expired, null);
            String permanent = ops.createUrl("https://www.example.com/permanent-probe", null, null);
            allPassed &= check(results, "link without ttl resolves", ops.resolve(permanent) != null, null);
            ops.deleteUrl(permanent);
            ops.deleteUrl(code);
        }

        StringBuilder report = new StringBuilder("# Test Report (live probes)\n\n");
        for (String r : results) report.append("- ").append(r).append("\n");
        report.append("\nVerdict: ").append(allPassed ? "PASS" : "FAIL").append("\n");
        Artifact artifact = new Artifact("test-report.md", "markdown", report.toString(),
                StageId.TESTING, Instant.now());

        if (!allPassed) {
            // exit-gate semantics: a red test report may not pass the stage
            throw new IllegalStateException("acceptance probes failed:\n" + report);
        }

        List<DecisionRecord> decisions = Arrays.asList(new DecisionRecord(
                StageId.TESTING, name(), "ACCEPTANCE_VERIFIED",
                "all live probes passed against the running system", Instant.now()));
        return AgentResult.success("all probes passed", List.of(artifact), decisions);
    }

    private boolean check(List<String> results, String label, boolean passed, String detail) {
        results.add((passed ? "PASS" : "FAIL") + ": " + label + (detail == null ? "" : " (" + detail + ")"));
        return passed;
    }
}
