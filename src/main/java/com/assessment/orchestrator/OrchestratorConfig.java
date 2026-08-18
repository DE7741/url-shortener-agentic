package com.assessment.orchestrator;

import com.assessment.orchestrator.agents.DefaultShortenerOps;
import com.assessment.orchestrator.agents.LlmEnrichedAgent;
import com.assessment.orchestrator.agents.ShortenerOps;
import com.assessment.orchestrator.core.agent.AgentExecutor;
import com.assessment.orchestrator.core.audit.AuditLog;
import com.assessment.orchestrator.core.engine.OrchestrationEngine;
import com.assessment.orchestrator.core.metrics.MetricsRegistry;
import com.assessment.orchestrator.core.policy.PolicyEngine;
import com.assessment.orchestrator.core.policy.StandardPolicies;
import com.assessment.orchestrator.scenario.ScenarioCatalog;
import com.assessment.shortener.ratelimit.TokenBucketRateLimiter;
import com.assessment.shortener.service.SafetySettings;
import com.assessment.shortener.service.UrlShortenerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

/** Wires the framework-free orchestration core into Spring. */
@Configuration
public class OrchestratorConfig {

    @Bean
    public PolicyEngine policyEngine() {
        return new PolicyEngine(StandardPolicies.defaults());
    }

    @Bean
    public AuditLog auditLog(@Value("${orchestrator.audit-dir:./data/audit}") String auditDir) {
        return new AuditLog(Path.of(auditDir));
    }

    @Bean
    public MetricsRegistry metricsRegistry() {
        return new MetricsRegistry();
    }

    @Bean(destroyMethod = "shutdown")
    public OrchestrationEngine orchestrationEngine(
            PolicyEngine policyEngine, AuditLog auditLog, MetricsRegistry metrics,
            @Value("${orchestrator.max-retries:2}") int maxRetries,
            @Value("${orchestrator.retry-backoff-millis:200}") long backoffMillis) {
        return new OrchestrationEngine(policyEngine, auditLog, metrics, maxRetries, backoffMillis);
    }

    @Bean
    public ShortenerOps shortenerOps(UrlShortenerService service, TokenBucketRateLimiter rateLimiter,
                                     SafetySettings safetySettings) {
        return new DefaultShortenerOps(service, rateLimiter, safetySettings);
    }

    @Bean
    public ScenarioCatalog scenarioCatalog(
            ShortenerOps ops,
            @Value("${orchestrator.llm.api-key:${OPENAI_API_KEY:}}") String apiKey,
            @Value("${orchestrator.llm.model:gpt-4o-mini}") String model,
            @Value("${orchestrator.llm.endpoint:https://api.openai.com/v1/chat/completions}") String endpoint) {
        UnaryOperator<AgentExecutor> llmWrapper =
                (apiKey == null || apiKey.isBlank())
                        ? UnaryOperator.identity()
                        : agent -> new LlmEnrichedAgent(agent, apiKey, model, endpoint);
        return new ScenarioCatalog(ops, llmWrapper);
    }
}
