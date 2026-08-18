package com.assessment.orchestrator.core.agent;

import com.assessment.orchestrator.core.model.Artifact;
import com.assessment.orchestrator.core.model.DecisionRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Outcome of one agent execution. Built via the factory methods. */
public class AgentResult {

    private final boolean success;
    private final String summary;
    private final List<Artifact> artifacts;
    private final List<DecisionRecord> decisions;
    private final boolean needsClarification;
    private final String clarificationQuestion;
    private final List<String> clarificationOptions;
    private final Runnable compensation;        // how to undo this stage's side effects
    private final String compensationDescription;

    private AgentResult(boolean success, String summary, List<Artifact> artifacts,
                        List<DecisionRecord> decisions, boolean needsClarification,
                        String clarificationQuestion, List<String> clarificationOptions,
                        Runnable compensation, String compensationDescription) {
        this.success = success;
        this.summary = summary;
        this.artifacts = artifacts;
        this.decisions = decisions;
        this.needsClarification = needsClarification;
        this.clarificationQuestion = clarificationQuestion;
        this.clarificationOptions = clarificationOptions;
        this.compensation = compensation;
        this.compensationDescription = compensationDescription;
    }

    public static AgentResult success(String summary, List<Artifact> artifacts, List<DecisionRecord> decisions) {
        return new AgentResult(true, summary, copy(artifacts), copy(decisions), false, null,
                Collections.emptyList(), null, null);
    }

    public static AgentResult successWithCompensation(String summary, List<Artifact> artifacts,
                                                      List<DecisionRecord> decisions,
                                                      Runnable compensation, String compensationDescription) {
        return new AgentResult(true, summary, copy(artifacts), copy(decisions), false, null,
                Collections.emptyList(), compensation, compensationDescription);
    }

    public static AgentResult failure(String summary) {
        return new AgentResult(false, summary, Collections.emptyList(), Collections.emptyList(),
                false, null, Collections.emptyList(), null, null);
    }

    public static AgentResult clarificationNeeded(String question, List<String> options,
                                                  List<DecisionRecord> decisions) {
        return new AgentResult(false, "clarification required", Collections.emptyList(), copy(decisions),
                true, question, copy(options), null, null);
    }

    private static <T> List<T> copy(List<T> in) {
        return in == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(in));
    }

    public boolean isSuccess() { return success; }
    public String getSummary() { return summary; }
    public List<Artifact> getArtifacts() { return artifacts; }
    public List<DecisionRecord> getDecisions() { return decisions; }
    public boolean isNeedsClarification() { return needsClarification; }
    public String getClarificationQuestion() { return clarificationQuestion; }
    public List<String> getClarificationOptions() { return clarificationOptions; }
    public Runnable getCompensation() { return compensation; }
    public String getCompensationDescription() { return compensationDescription; }
}
