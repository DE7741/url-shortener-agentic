package com.assessment.orchestrator.web;

import com.assessment.orchestrator.core.engine.WorkflowState;
import com.assessment.orchestrator.core.model.Artifact;
import com.assessment.orchestrator.core.model.DecisionRecord;
import com.assessment.orchestrator.core.model.StageExecution;
import com.assessment.orchestrator.core.model.StageId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps engine state to JSON-friendly views. */
final class WorkflowViews {

    private WorkflowViews() {}

    static Map<String, Object> summary(WorkflowState state) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", state.getId());
        view.put("workflow", state.getDefinition().getName());
        view.put("scenarioType", state.getScenarioType());
        view.put("status", state.getStatus());
        view.put("statusReason", state.getStatusReason());
        view.put("requirement", state.getRequirement());
        view.put("createdAt", state.getCreatedAt());
        view.put("endedAt", state.getEndedAt());
        return view;
    }

    static Map<String, Object> full(WorkflowState state) {
        Map<String, Object> view = summary(state);

        Map<String, Object> stages = new LinkedHashMap<>();
        for (Map.Entry<StageId, StageExecution> e : state.getExecutions().entrySet()) {
            StageExecution exec = e.getValue();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("status", exec.getStatus());
            s.put("attempts", exec.getAttempts());
            s.put("startedAt", exec.getStartedAt());
            s.put("endedAt", exec.getEndedAt());
            s.put("lastError", exec.getLastError());
            s.put("dependsOn", state.getDefinition().getNodes().get(e.getKey()).getDependsOn());
            stages.put(e.getKey().name(), s);
        }
        view.put("stages", stages);

        List<Map<String, Object>> artifacts = new ArrayList<>();
        synchronized (state.getArtifacts()) {
            for (Artifact a : state.getArtifacts().values()) {
                Map<String, Object> av = new LinkedHashMap<>();
                av.put("name", a.getName());
                av.put("type", a.getType());
                av.put("producedBy", a.getProducedBy());
                av.put("createdAt", a.getCreatedAt());
                av.put("content", a.getContent());
                artifacts.add(av);
            }
        }
        view.put("artifacts", artifacts);

        List<Map<String, Object>> decisions = new ArrayList<>();
        for (DecisionRecord d : new ArrayList<>(state.getDecisions())) {
            Map<String, Object> dv = new LinkedHashMap<>();
            dv.put("id", d.getId());
            dv.put("stage", d.getStage());
            dv.put("actor", d.getActor());
            dv.put("decision", d.getDecision());
            dv.put("rationale", d.getRationale());
            dv.put("timestamp", d.getTimestamp());
            decisions.add(dv);
        }
        view.put("decisionLineage", decisions);

        view.put("pendingApprovals", state.getPendingApprovals());
        WorkflowState.PendingClarification pc = state.getPendingClarification();
        if (pc != null) {
            Map<String, Object> pcv = new LinkedHashMap<>();
            pcv.put("stage", pc.getStage());
            pcv.put("question", pc.getQuestion());
            pcv.put("options", pc.getOptions());
            view.put("pendingClarification", pcv);
        }
        view.put("approvals", state.getApprovals());
        return view;
    }
}
