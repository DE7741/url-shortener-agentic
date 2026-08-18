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

/** Produces the design: chosen approach, alternatives considered, trade-offs. */
public class ArchitectureAgent implements AgentExecutor {

    @Override
    public String name() {
        return "architecture-agent";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String scenario = ctx.getScenarioType();
        StringBuilder doc = new StringBuilder();
        doc.append("# Design\n\n");
        doc.append("Derived from: requirements.md")
                .append(ctx.getArtifacts().containsKey("impact-analysis.md") ? " + impact-analysis.md" : "")
                .append("\n\n");

        String decision;
        String rationale;
        if ("BROWNFIELD".equals(scenario)) {
            doc.append("## Approach\n\n")
               .append("Adjust token-bucket parameters at runtime: raise burst capacity to 25, keep refill rate.\n\n")
               .append("## Alternatives considered\n\n")
               .append("1. Sliding-window limiter rewrite - rejected: larger blast radius, same outcome\n")
               .append("2. Per-endpoint buckets - rejected: premature for a single hot path\n")
               .append("3. Raise sustained rate - rejected: violates the stated constraint\n\n")
               .append("## Trade-offs\n\n")
               .append("- Burst 25 admits short spikes but slightly increases worst-case downstream load\n")
               .append("- Runtime reconfiguration resets buckets (brief window of extra allowance)\n");
            decision = "TUNE_TOKEN_BUCKET";
            rationale = "smallest change satisfying acceptance criteria; reversible at runtime";
        } else if ("AMBIGUOUS".equals(scenario)) {
            doc.append("## Approach\n\n")
               .append("Apply the human-selected hardening (" + ctx.getClarificationResolution() + ") via ")
               .append("SafetySettings, which UrlShortenerService#validateUrl already consults.\n\n")
               .append("## Alternatives considered\n\n")
               .append("1. External URL reputation service - rejected for prototype: adds a network dependency\n")
               .append("2. Validation at redirect time - rejected: fails late, bad UX and wasted storage\n\n")
               .append("## Trade-offs\n\n")
               .append("- Creation-time validation cannot catch domains that turn malicious later\n");
            decision = "VALIDATE_AT_CREATION_VIA_SAFETY_SETTINGS";
            rationale = "reuses existing validation seam; no new dependencies; instantly reversible";
        } else {
            doc.append("## Approach\n\n")
               .append("Store optional expiresAt on ShortUrl; enforce at resolve time (lazy expiry). ")
               .append("Expired links answer 410 Gone.\n\n")
               .append("## Alternatives considered\n\n")
               .append("1. Background sweeper thread deleting expired rows - deferred: lazy expiry is sufficient ")
               .append("for correctness; a sweeper is a memory optimization\n")
               .append("2. TTL at the store layer (e.g., Redis EXPIRE) - deferred until a Redis store exists\n\n")
               .append("## Trade-offs\n\n")
               .append("- Lazy expiry leaves tombstones in memory until deleted (bounded by prototype scope)\n")
               .append("- 410 Gone (vs 404) deliberately signals 'existed but expired' for client caching\n");
            decision = "LAZY_EXPIRY_AT_RESOLVE";
            rationale = "correct with least mechanism; avoids background thread lifecycle in the prototype";
        }

        List<DecisionRecord> decisions = Arrays.asList(
                new DecisionRecord(StageId.ARCHITECTURE, name(), decision, rationale, Instant.now()));
        Artifact artifact = new Artifact("design.md", "markdown", doc.toString(),
                StageId.ARCHITECTURE, Instant.now());
        return AgentResult.success("design produced", List.of(artifact), decisions);
    }
}
