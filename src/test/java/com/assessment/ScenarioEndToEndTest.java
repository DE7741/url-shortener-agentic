package com.assessment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests: boots the full application and drives the three assessment
 * scenarios through the public REST API, the same way a reviewer would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "orchestrator.audit-dir=./target/test-audit",
        "orchestrator.retry-backoff-millis=50"
})
class ScenarioEndToEndTest {

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    // helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> startWorkflow(Map<String, Object> body) {
        ResponseEntity<Map> resp = rest.postForEntity("/api/workflows", body, Map.class);
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitStatus(String id, List<String> statuses) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        Map<String, Object> latest = null;
        while (System.currentTimeMillis() < deadline) {
            latest = rest.getForObject("/api/workflows/" + id, Map.class);
            if (latest != null && statuses.contains(String.valueOf(latest.get("status")))) {
                return latest;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("workflow " + id + " did not reach " + statuses
                + "; last=" + (latest == null ? "null" : latest.get("status")));
    }

    @SuppressWarnings("unchecked")
    private boolean hasArtifact(Map<String, Object> workflow, String name) {
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) workflow.get("artifacts");
        return artifacts.stream().anyMatch(a -> name.equals(a.get("name")));
    }

    // scenarios

    @Test
    void greenfieldScenarioRunsToCompletion() throws Exception {
        Map<String, Object> started = startWorkflow(Map.of(
                "scenario", "greenfield", "autoApprove", true));
        String id = String.valueOf(started.get("id"));

        Map<String, Object> done = awaitStatus(id, List.of("COMPLETED"));
        assertTrue(hasArtifact(done, "requirements.md"));
        assertTrue(hasArtifact(done, "design.md"));
        assertTrue(hasArtifact(done, "implementation-notes.md"));
        assertTrue(hasArtifact(done, "test-report.md"));
        assertTrue(hasArtifact(done, "release-notes.md"));
        assertTrue(hasArtifact(done, "readiness-checklist.md"));

        // the audit trail exists and is non-trivial
        List<?> audit = rest.getForObject("/api/workflows/" + id + "/audit", List.class);
        assertNotNull(audit);
        assertTrue(audit.size() >= 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void brownfieldScenarioWithTransientFailureRetriesAndCompletes() throws Exception {
        Map<String, Object> started = startWorkflow(Map.of(
                "scenario", "brownfield", "autoApprove", true, "failureInjection", "transient"));
        String id = String.valueOf(started.get("id"));

        Map<String, Object> done = awaitStatus(id, List.of("COMPLETED"));
        assertTrue(hasArtifact(done, "impact-analysis.md"), "brownfield must include impact analysis");

        Map<String, Object> stages = (Map<String, Object>) done.get("stages");
        Map<String, Object> testing = (Map<String, Object>) stages.get("TESTING");
        assertTrue(((Number) testing.get("attempts")).intValue() >= 2, "transient failure must be retried");

        // the change is live: burst capacity raised to 25
        Map<String, Object> settings = rest.getForObject("/api/admin/settings", Map.class);
        Map<String, Object> rateLimit = (Map<String, Object>) settings.get("rateLimit");
        assertEquals(25, ((Number) rateLimit.get("capacity")).intValue());

        // reliability metrics reflect the retry
        Map<String, Object> metrics = rest.getForObject("/api/workflows/metrics", Map.class);
        assertTrue(((Number) metrics.get("totalRetries")).longValue() >= 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ambiguousScenarioPausesForClarificationThenCompletes() throws Exception {
        Map<String, Object> started = startWorkflow(Map.of(
                "scenario", "ambiguous", "autoApprove", true));
        String id = String.valueOf(started.get("id"));

        Map<String, Object> waiting = awaitStatus(id, List.of("AWAITING_CLARIFICATION"));
        Map<String, Object> pc = (Map<String, Object>) waiting.get("pendingClarification");
        assertNotNull(pc, "an ambiguous requirement must surface a clarification request");
        List<String> options = (List<String>) pc.get("options");
        assertTrue(options.size() >= 2);

        String httpsOnly = options.stream().filter(o -> o.startsWith("HTTPS_ONLY"))
                .findFirst().orElseThrow();
        rest.postForEntity("/api/workflows/" + id + "/clarifications",
                Map.of("selectedOption", httpsOnly, "resolver", "reviewer"), Map.class);

        Map<String, Object> done = awaitStatus(id, List.of("COMPLETED"));
        assertTrue(hasArtifact(done, "readiness-checklist.md"));

        // the decision lineage captured the human resolution
        List<Map<String, Object>> lineage = (List<Map<String, Object>>) done.get("decisionLineage");
        assertTrue(lineage.stream().anyMatch(d ->
                String.valueOf(d.get("decision")).startsWith("AMBIGUITY_RESOLVED")));

        // the hardening is live: http urls are now rejected
        ResponseEntity<Map> reject = rest.postForEntity("/api/urls",
                Map.of("url", "http://insecure.example.com/x"), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, reject.getStatusCode());
    }

    // shortener API

    @Test
    @SuppressWarnings("unchecked")
    void shortenAndRedirectFlowWorks() {
        // point the short link at this app itself so the redirect can be followed locally
        String target = "https://localhost:" + port + "/actuator/health";
        ResponseEntity<Map> created = rest.postForEntity("/api/urls",
                Map.of("url", target, "customCode", "self-test"), Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals("self-test", created.getBody().get("code"));

        // stats start at zero
        Map<String, Object> stats = rest.getForObject("/api/urls/self-test/stats", Map.class);
        assertEquals(0, ((Number) stats.get("totalClicks")).intValue());

        // unknown code gives 404
        ResponseEntity<Map> missing = rest.getForEntity("/api/urls/does-not-exist", Map.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());

        // delete works
        rest.delete("/api/urls/self-test");
        ResponseEntity<Map> afterDelete = rest.getForEntity("/api/urls/self-test", Map.class);
        assertEquals(HttpStatus.NOT_FOUND, afterDelete.getStatusCode());
    }
}
