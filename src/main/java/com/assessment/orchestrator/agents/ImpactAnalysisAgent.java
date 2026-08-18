package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.agent.AgentContext;
import com.assessment.orchestrator.core.agent.AgentExecutor;
import com.assessment.orchestrator.core.agent.AgentResult;
import com.assessment.orchestrator.core.model.Artifact;
import com.assessment.orchestrator.core.model.DecisionRecord;
import com.assessment.orchestrator.core.model.StageId;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Brownfield codebase reasoning: maps the requirement onto the actual modules,
 * APIs, and data flows of this codebase and states the blast radius.
 */
public class ImpactAnalysisAgent implements AgentExecutor {

    /** Static map of this repository's structure - the agent's codebase knowledge. */
    private static final Map<String, String> CODEBASE_MAP = buildCodebaseMap();

    private static Map<String, String> buildCodebaseMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("rate limit", "shortener.ratelimit.TokenBucketRateLimiter, shortener.web.RateLimitFilter, "
                + "ShortenerConfig#rateLimiter, application.properties (shortener.rate-limit.*)");
        m.put("redirect", "shortener.web.RedirectController, UrlShortenerService#resolveForRedirect, "
                + "data flow: HTTP GET /r/{code} -> RateLimitFilter -> RedirectController -> UrlStore");
        m.put("expira", "shortener.domain.ShortUrl#isExpired, UrlShortenerService#create/resolveForRedirect, "
                + "GlobalExceptionHandler (410 Gone mapping)");
        m.put("analytics", "shortener.service.AnalyticsService, UrlStore#recordClick/clicks, "
                + "UrlController#stats");
        m.put("valid", "UrlShortenerService#validateUrl, SafetySettings, AdminController");
        return m;
    }

    @Override
    public String name() {
        return "impact-analysis-agent";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String lower = ctx.getRequirement().toLowerCase(Locale.ROOT);

        StringBuilder doc = new StringBuilder();
        doc.append("# Impact Analysis (Brownfield)\n\n");
        doc.append("Requirement: ").append(ctx.getRequirement()).append("\n\n");
        doc.append("## Impacted modules / APIs / data flows\n\n");
        boolean any = false;
        for (Map.Entry<String, String> e : CODEBASE_MAP.entrySet()) {
            if (lower.contains(e.getKey())) {
                doc.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                any = true;
            }
        }
        if (!any) {
            doc.append("- No direct module match; defaulting to service + web layers review\n");
        }
        doc.append("\n## Blast radius\n\n");
        doc.append("- Runtime config change only; no schema or API contract changes\n");
        doc.append("- Redirect hot path affected, so it must be validated under burst load before release\n");
        doc.append("- Rollback: restore previous limiter settings (registered as compensation)\n");

        List<DecisionRecord> decisions = Arrays.asList(
                new DecisionRecord(StageId.IMPACT_ANALYSIS, name(), "IMPACT_SCOPED",
                        "change confined to rate limiting configuration on the redirect path; "
                                + "no API contract or storage changes required", Instant.now()));

        Artifact artifact = new Artifact("impact-analysis.md", "markdown", doc.toString(),
                StageId.IMPACT_ANALYSIS, Instant.now());
        return AgentResult.success("impact analysis complete", List.of(artifact), decisions);
    }
}
