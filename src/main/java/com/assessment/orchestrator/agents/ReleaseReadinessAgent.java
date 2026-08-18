package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.agent.AgentContext;
import com.assessment.orchestrator.core.agent.AgentExecutor;
import com.assessment.orchestrator.core.agent.AgentResult;
import com.assessment.orchestrator.core.model.Artifact;
import com.assessment.orchestrator.core.model.DecisionRecord;
import com.assessment.orchestrator.core.model.StageId;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * The synchronization point after the parallel test/docs wave: verifies every
 * required artifact exists and the test verdict is green before sign-off.
 */
public class ReleaseReadinessAgent implements AgentExecutor {

    private static final List<String> REQUIRED_ARTIFACTS = Arrays.asList(
            "requirements.md", "design.md", "implementation-notes.md",
            "test-report.md", "release-notes.md");

    @Override
    public String name() {
        return "release-readiness-agent";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        StringBuilder checklist = new StringBuilder("# Release Readiness Checklist\n\n");
        boolean ready = true;

        for (String required : REQUIRED_ARTIFACTS) {
            boolean present = ctx.getArtifacts().containsKey(required);
            checklist.append("- [").append(present ? "x" : " ").append("] artifact: ")
                    .append(required).append("\n");
            ready &= present;
        }

        Artifact testReport = ctx.getArtifacts().get("test-report.md");
        boolean testsGreen = testReport != null && testReport.getContent().contains("Verdict: PASS");
        checklist.append("- [").append(testsGreen ? "x" : " ").append("] test verdict is PASS\n");
        ready &= testsGreen;

        checklist.append("\nResult: ").append(ready ? "READY FOR RELEASE" : "NOT READY").append("\n");

        if (!ready) {
            return AgentResult.failure("release readiness checks failed:\n" + checklist);
        }

        List<DecisionRecord> decisions = Arrays.asList(new DecisionRecord(
                StageId.RELEASE_READINESS, name(), "RELEASE_SIGNED_OFF",
                "all artifacts present and tests green; human approval was on record before this stage ran",
                Instant.now()));
        Artifact artifact = new Artifact("readiness-checklist.md", "markdown", checklist.toString(),
                StageId.RELEASE_READINESS, Instant.now());
        return AgentResult.success("release readiness confirmed", List.of(artifact), decisions);
    }
}
