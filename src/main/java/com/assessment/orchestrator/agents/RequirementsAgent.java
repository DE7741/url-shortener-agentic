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
import java.util.Locale;

/**
 * Interprets intent, detects ambiguity, and normalizes the requirement into
 * a concrete engineering problem with acceptance criteria.
 */
public class RequirementsAgent implements AgentExecutor {

    /** Vague quality words with no measurable criteria signal ambiguity. */
    private static final List<String> AMBIGUITY_MARKERS =
            Arrays.asList("safer", "better", "improve", "nicer", "faster", "more secure", "cleaner");

    @Override
    public String name() {
        return "requirements-agent";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String requirement = ctx.getRequirement();
        String lower = requirement.toLowerCase(Locale.ROOT);

        boolean ambiguous = false;
        String marker = null;
        for (String m : AMBIGUITY_MARKERS) {
            if (lower.contains(m)) {
                ambiguous = true;
                marker = m;
                break;
            }
        }

        // ambiguity detected and no human resolution yet: stop and ask
        if (ambiguous && ctx.getClarificationResolution() == null) {
            List<DecisionRecord> decisions = new ArrayList<>();
            decisions.add(new DecisionRecord(StageId.REQUIREMENTS, name(),
                    "AMBIGUITY_DETECTED",
                    "requirement contains unmeasurable term '" + marker
                            + "' with no acceptance criteria; escalating to a human rather than guessing",
                    Instant.now()));
            return AgentResult.clarificationNeeded(
                    "The requirement '" + requirement + "' is ambiguous. Which interpretation should be implemented?",
                    Arrays.asList(
                            "HTTPS_ONLY: reject non-HTTPS target URLs at creation time",
                            "DOMAIN_BLOCKLIST: block link creation for known-malicious domains",
                            "BOTH: enforce HTTPS-only and the domain blocklist"),
                    decisions);
        }

        StringBuilder doc = new StringBuilder();
        doc.append("# Normalized Requirement\n\n");
        doc.append("Raw input: ").append(requirement).append("\n\n");
        if (ctx.getClarificationResolution() != null) {
            doc.append("Human clarification: ").append(ctx.getClarificationResolution()).append("\n\n");
        }
        doc.append("Scenario type: ").append(ctx.getScenarioType()).append("\n\n");
        doc.append("## Problem statement\n\n").append(problemStatement(ctx)).append("\n\n");
        doc.append("## Acceptance criteria\n\n");
        for (String c : acceptanceCriteria(ctx)) {
            doc.append("- ").append(c).append("\n");
        }
        doc.append("\n## Out of scope\n\n- Persistence beyond process lifetime\n- AuthN/AuthZ (assumed handled by gateway)\n");

        List<DecisionRecord> decisions = new ArrayList<>();
        decisions.add(new DecisionRecord(StageId.REQUIREMENTS, name(),
                "REQUIREMENT_NORMALIZED",
                ambiguous
                        ? "ambiguous term resolved by human as: " + ctx.getClarificationResolution()
                        : "requirement was concrete; normalized with measurable acceptance criteria",
                Instant.now()));

        Artifact artifact = new Artifact("requirements.md", "markdown", doc.toString(),
                StageId.REQUIREMENTS, Instant.now());
        return AgentResult.success("requirement normalized", List.of(artifact), decisions);
    }

    private String problemStatement(AgentContext ctx) {
        String scenario = ctx.getScenarioType();
        if ("BROWNFIELD".equals(scenario)) {
            return "Modify the existing redirect rate limiting so legitimate short bursts are not rejected, "
                    + "without raising the sustained request rate.";
        }
        if ("AMBIGUOUS".equals(scenario)) {
            return "Harden URL creation according to the human-selected interpretation: "
                    + ctx.getClarificationResolution();
        }
        return "Add link expiration (TTL) support to the URL shortener: links may carry an optional "
                + "time-to-live after which redirects must stop resolving.";
    }

    private List<String> acceptanceCriteria(AgentContext ctx) {
        String scenario = ctx.getScenarioType();
        if ("BROWNFIELD".equals(scenario)) {
            return Arrays.asList(
                    "A burst of 25 redirect requests from one client is fully admitted",
                    "Sustained refill rate remains unchanged",
                    "Change is applied at runtime and is reversible (a rollback path exists)");
        }
        if ("AMBIGUOUS".equals(scenario)) {
            String r = String.valueOf(ctx.getClarificationResolution());
            List<String> criteria = new ArrayList<>();
            if (r.startsWith("HTTPS_ONLY") || r.startsWith("BOTH")) {
                criteria.add("Creating a short link for an http:// URL returns a validation error");
            }
            if (r.startsWith("DOMAIN_BLOCKLIST") || r.startsWith("BOTH")) {
                criteria.add("Creating a short link for a blocklisted domain returns a validation error");
            }
            criteria.add("Existing valid links keep resolving");
            return criteria;
        }
        return Arrays.asList(
                "POST /api/urls accepts optional ttlSeconds >= 1",
                "GET /r/{code} returns 410 Gone after expiry",
                "Links without ttlSeconds never expire");
    }
}
