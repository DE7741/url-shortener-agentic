package com.assessment.orchestrator.core.agent;

import com.assessment.orchestrator.core.model.Artifact;

import java.util.Collections;
import java.util.Map;

/** Read-only view of workflow state handed to an agent for one execution attempt. */
public class AgentContext {

    private final String workflowId;
    private final String requirement;
    private final String scenarioType;
    private final Map<String, Artifact> artifacts;
    private final Map<String, String> params;
    private final int attempt;
    private final String clarificationResolution; // null until a human resolves ambiguity

    public AgentContext(String workflowId, String requirement, String scenarioType,
                        Map<String, Artifact> artifacts, Map<String, String> params,
                        int attempt, String clarificationResolution) {
        this.workflowId = workflowId;
        this.requirement = requirement;
        this.scenarioType = scenarioType;
        this.artifacts = Collections.unmodifiableMap(artifacts);
        this.params = Collections.unmodifiableMap(params);
        this.attempt = attempt;
        this.clarificationResolution = clarificationResolution;
    }

    public String getWorkflowId() { return workflowId; }
    public String getRequirement() { return requirement; }
    public String getScenarioType() { return scenarioType; }
    public Map<String, Artifact> getArtifacts() { return artifacts; }
    public Map<String, String> getParams() { return params; }
    public int getAttempt() { return attempt; }
    public String getClarificationResolution() { return clarificationResolution; }

    public String param(String key, String defaultValue) {
        String v = params.get(key);
        return v == null ? defaultValue : v;
    }
}
