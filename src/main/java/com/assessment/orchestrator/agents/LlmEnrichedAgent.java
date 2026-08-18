package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.agent.AgentContext;
import com.assessment.orchestrator.core.agent.AgentExecutor;
import com.assessment.orchestrator.core.agent.AgentResult;
import com.assessment.orchestrator.core.model.Artifact;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional LLM integration, implemented as a decorator. The deterministic agent stays
 * the source of truth for state changes and validation; when an OpenAI-compatible API
 * key is configured, the LLM adds a reviewer-style narrative artifact on top. Any LLM
 * failure falls back to the deterministic result, so the LLM is never on the critical
 * path.
 */
public class LlmEnrichedAgent implements AgentExecutor {

    private final AgentExecutor delegate;
    private final String apiKey;    // null/blank disables LLM calls
    private final String model;
    private final String endpoint;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public LlmEnrichedAgent(AgentExecutor delegate, String apiKey, String model, String endpoint) {
        this.delegate = delegate;
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint;
    }

    @Override
    public String name() {
        return delegate.name() + (enabled() ? "+llm" : "");
    }

    private boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws Exception {
        AgentResult base = delegate.execute(ctx);
        if (!enabled() || !base.isSuccess() || base.getArtifacts().isEmpty()) {
            return base;
        }
        try {
            Artifact primary = base.getArtifacts().get(0);
            String review = callLlm(
                    "You are a senior engineer reviewing an SDLC artifact. In 5 sentences or fewer, "
                            + "summarize it and flag any risk.",
                    "Requirement: " + ctx.getRequirement() + "\n\nArtifact:\n" + primary.getContent());
            List<Artifact> artifacts = new ArrayList<>(base.getArtifacts());
            artifacts.add(new Artifact(primary.getName().replace(".md", "") + "-llm-review.md",
                    "markdown", "# LLM Review (" + model + ")\n\n" + review,
                    primary.getProducedBy(), Instant.now()));
            return AgentResult.success(base.getSummary() + " (+LLM review)", artifacts, base.getDecisions());
        } catch (Exception e) {
            // graceful degradation: the deterministic output stands on its own
            return base;
        }
    }

    private String callLlm(String system, String user) throws Exception {
        String body = "{\"model\":\"" + escape(model) + "\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + escape(system) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escape(user) + "\"}],"
                + "\"max_tokens\":300}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("LLM call failed: HTTP " + response.statusCode());
        }
        return extractContent(response.body());
    }

    /** Minimal extraction of choices[0].message.content, avoiding a JSON dependency. */
    static String extractContent(String json) {
        String key = "\"content\":";
        int idx = json.indexOf(key);
        if (idx < 0) return "(no content)";
        int start = json.indexOf('"', idx + key.length());
        if (start < 0) return "(no content)";
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                if (c == 'n') sb.append('\n');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
