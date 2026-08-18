import com.assessment.orchestrator.agents.DefaultShortenerOps;
import com.assessment.orchestrator.agents.ShortenerOps;
import com.assessment.orchestrator.core.audit.AuditEvent;
import com.assessment.orchestrator.core.audit.AuditLog;
import com.assessment.orchestrator.core.engine.OrchestrationEngine;
import com.assessment.orchestrator.core.engine.WorkflowState;
import com.assessment.orchestrator.core.metrics.MetricsRegistry;
import com.assessment.orchestrator.core.model.StageId;
import com.assessment.orchestrator.core.policy.PolicyEngine;
import com.assessment.orchestrator.core.policy.StandardPolicies;
import com.assessment.orchestrator.scenario.ScenarioCatalog;
import com.assessment.orchestrator.scenario.ScenarioType;
import com.assessment.shortener.ratelimit.TokenBucketRateLimiter;
import com.assessment.shortener.service.CodeGenerator;
import com.assessment.shortener.service.SafetySettings;
import com.assessment.shortener.service.UrlShortenerService;
import com.assessment.shortener.store.InMemoryUrlStore;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone verification harness used during development. Drives the orchestration
 * core through all scenarios without Spring. Compile the main sources plus this file,
 * then run: java Harness
 */
public class Harness {

    static OrchestrationEngine engine;
    static MetricsRegistry metrics = new MetricsRegistry();
    static AuditLog audit = new AuditLog(null);
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        SafetySettings settings = new SafetySettings();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);
        UrlShortenerService service = new UrlShortenerService(
                new InMemoryUrlStore(), new CodeGenerator(7), settings, Clock.systemUTC());
        ShortenerOps ops = new DefaultShortenerOps(service, limiter, settings);
        ScenarioCatalog catalog = new ScenarioCatalog(ops, null);
        engine = new OrchestrationEngine(new PolicyEngine(StandardPolicies.defaults()),
                audit, metrics, 2, 100);

        // 1. greenfield, auto-approve
        WorkflowState g = engine.start(catalog.build(ScenarioType.GREENFIELD),
                catalog.defaultRequirement(ScenarioType.GREENFIELD), "GREENFIELD",
                params("autoApprove", "true"));
        awaitTerminal(g);
        check("greenfield completed", "COMPLETED".equals(g.getStatus().name()));
        check("greenfield produced 6 artifacts", g.getArtifacts().size() == 6);
        check("greenfield lineage recorded", g.getDecisions().size() >= 6);

        // 2. brownfield with transient failure injection: bounded retry + recovery
        WorkflowState b = engine.start(catalog.build(ScenarioType.BROWNFIELD),
                catalog.defaultRequirement(ScenarioType.BROWNFIELD), "BROWNFIELD",
                params("autoApprove", "true", "failureInjection", "transient"));
        awaitTerminal(b);
        check("brownfield completed", "COMPLETED".equals(b.getStatus().name()));
        check("brownfield impact analysis present", b.getArtifacts().containsKey("impact-analysis.md"));
        check("testing retried (attempts>=2)", b.execution(StageId.TESTING).getAttempts() >= 2);
        check("rate limit capacity now 25", limiter.getCapacity() == 25);

        // 3. ambiguous: clarification then completion
        WorkflowState a = engine.start(catalog.build(ScenarioType.AMBIGUOUS),
                catalog.defaultRequirement(ScenarioType.AMBIGUOUS), "AMBIGUOUS",
                params("autoApprove", "true"));
        awaitStatus(a, "AWAITING_CLARIFICATION");
        check("clarification question surfaced", a.getPendingClarification() != null);
        check("3 interpretations offered", a.getPendingClarification().getOptions().size() == 3);
        engine.resolveClarification(a.getId(),
                a.getPendingClarification().getOptions().get(0), "harness-human");
        awaitTerminal(a);
        check("ambiguous completed after resolution", "COMPLETED".equals(a.getStatus().name()));
        check("https-only now enforced", settings.isHttpsOnly());

        // 4. persistent failure: rollback + safe stop (the capacity change reverts)
        int capacityBefore = limiter.getCapacity();
        WorkflowState p = engine.start(catalog.build(ScenarioType.BROWNFIELD),
                catalog.defaultRequirement(ScenarioType.BROWNFIELD), "BROWNFIELD",
                params("autoApprove", "true", "failureInjection", "persistent"));
        awaitTerminal(p);
        check("persistent failure safe-stopped", "SAFE_STOPPED".equals(p.getStatus().name()));
        check("implementation rolled back", "ROLLED_BACK".equals(
                p.execution(StageId.IMPLEMENTATION).getStatus().name()));
        check("rate limit restored to " + capacityBefore, limiter.getCapacity() == capacityBefore);

        // 5. approval flow: no autoApprove -> pause -> approve -> complete
        WorkflowState h = engine.start(catalog.build(ScenarioType.GREENFIELD),
                catalog.defaultRequirement(ScenarioType.GREENFIELD), "GREENFIELD",
                params());
        awaitStatus(h, "AWAITING_APPROVAL");
        check("paused at implementation approval",
                h.getPendingApprovals().containsKey(StageId.IMPLEMENTATION));
        engine.approve(h.getId(), StageId.IMPLEMENTATION, "harness-human", true, "reviewed");
        awaitStatus(h, "AWAITING_APPROVAL");
        engine.approve(h.getId(), StageId.RELEASE_READINESS, "harness-human", true, "ship it");
        awaitTerminal(h);
        check("approval-gated run completed", "COMPLETED".equals(h.getStatus().name()));

        // 6. replan: amend the requirement on a completed workflow -> full re-execution
        engine.replan(g.getId(), catalog.defaultRequirement(ScenarioType.GREENFIELD)
                + " Default TTL should be 30 days.", "harness-human");
        awaitTerminal(g);
        check("replanned workflow completed", "COMPLETED".equals(g.getStatus().name()));
        check("replan decision in lineage", g.getDecisions().stream()
                .anyMatch(d -> "REPLAN".equals(d.getDecision())));

        // metrics + audit sanity
        Map<String, Object> snap = metrics.snapshot();
        System.out.println("\nMETRICS: " + snap);
        check("metrics: retries counted", ((Number) snap.get("totalRetries")).longValue() >= 3);
        check("metrics: rollback counted", ((Number) snap.get("totalRollbacks")).longValue() >= 1);
        check("metrics: mttr measured", snap.get("mttrMillis") != null);
        check("metrics: success rate computed", snap.get("successRate") != null);
        int auditCount = audit.forWorkflow(p.getId()).size();
        check("audit trail rich for failure run (" + auditCount + " events)", auditCount >= 15);

        System.out.println("\nSample audit trail (persistent-failure run):");
        for (AuditEvent e : audit.forWorkflow(p.getId())) {
            System.out.println("  " + e.getAction() + " [" + e.getStage() + "] " + e.getActor()
                    + " - " + truncate(e.getDetail()));
        }

        engine.shutdown();
        System.out.println(failures == 0 ? "\nALL HARNESS CHECKS PASSED"
                : "\n" + failures + " HARNESS CHECKS FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static Map<String, String> params(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static void awaitTerminal(WorkflowState s) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!s.isTerminal() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        if (!s.isTerminal()) throw new IllegalStateException("timeout; status=" + s.getStatus());
    }

    static void awaitStatus(WorkflowState s, String status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!status.equals(s.getStatus().name()) && System.currentTimeMillis() < deadline) {
            if (s.isTerminal()) throw new IllegalStateException(
                    "terminal " + s.getStatus() + " while waiting for " + status
                    + " reason=" + s.getStatusReason());
            Thread.sleep(50);
        }
        if (!status.equals(s.getStatus().name()))
            throw new IllegalStateException("timeout waiting for " + status + "; at " + s.getStatus());
    }

    static void check(String label, boolean ok) {
        System.out.println((ok ? "PASS" : "FAIL") + ": " + label);
        if (!ok) failures++;
    }

    static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 90 ? s.substring(0, 90) + "..." : s;
    }
}
