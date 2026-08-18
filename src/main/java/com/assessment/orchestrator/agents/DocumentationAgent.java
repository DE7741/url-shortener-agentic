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

/** Generates release notes from upstream artifacts (runs in parallel with testing). */
public class DocumentationAgent implements AgentExecutor {

    @Override
    public String name() {
        return "documentation-agent";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        StringBuilder doc = new StringBuilder();
        doc.append("# Release Notes\n\n");
        doc.append("Change: ").append(ctx.getRequirement()).append("\n\n");
        if (ctx.getClarificationResolution() != null) {
            doc.append("Clarified as: ").append(ctx.getClarificationResolution()).append("\n\n");
        }
        doc.append("## What changed\n\n");
        Artifact impl = ctx.getArtifacts().get("implementation-notes.md");
        doc.append(impl != null ? indent(impl.getContent())
                : "(implementation notes unavailable)\n");
        doc.append("\n## API quick reference\n\n");
        doc.append("- POST /api/urls { url, customCode?, ttlSeconds? } -> 201 { code, shortUrl, expiresAt }\n");
        doc.append("- GET /r/{code} -> 302 | 404 | 410 (expired) | 429 (rate limited)\n");
        doc.append("- GET /api/urls/{code}/stats -> click analytics\n");

        List<DecisionRecord> decisions = Arrays.asList(new DecisionRecord(
                StageId.DOCUMENTATION, name(), "DOCS_GENERATED",
                "release notes derived from the requirement and implementation artifacts",
                Instant.now()));
        Artifact artifact = new Artifact("release-notes.md", "markdown", doc.toString(),
                StageId.DOCUMENTATION, Instant.now());
        return AgentResult.success("release notes generated", List.of(artifact), decisions);
    }

    private String indent(String content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            sb.append("> ").append(line).append("\n");
        }
        return sb.toString();
    }
}
