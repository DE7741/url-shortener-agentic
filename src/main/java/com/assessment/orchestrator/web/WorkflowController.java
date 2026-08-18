package com.assessment.orchestrator.web;

import com.assessment.orchestrator.core.audit.AuditEvent;
import com.assessment.orchestrator.core.audit.AuditLog;
import com.assessment.orchestrator.core.engine.OrchestrationEngine;
import com.assessment.orchestrator.core.engine.WorkflowState;
import com.assessment.orchestrator.core.graph.WorkflowDefinition;
import com.assessment.orchestrator.core.metrics.MetricsRegistry;
import com.assessment.orchestrator.core.model.StageId;
import com.assessment.orchestrator.scenario.ScenarioCatalog;
import com.assessment.orchestrator.scenario.ScenarioType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** REST surface for starting workflows, human governance actions, and observability. */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final OrchestrationEngine engine;
    private final ScenarioCatalog catalog;
    private final AuditLog auditLog;
    private final MetricsRegistry metrics;

    public WorkflowController(OrchestrationEngine engine, ScenarioCatalog catalog,
                              AuditLog auditLog, MetricsRegistry metrics) {
        this.engine = engine;
        this.catalog = catalog;
        this.auditLog = auditLog;
        this.metrics = metrics;
    }

    /**
     * Start a scenario workflow.
     * Body: { "scenario": "greenfield|brownfield|ambiguous",
     *         "requirement": optional override,
     *         "autoApprove": optional bool (demo mode),
     *         "failureInjection": "none|transient|persistent" }
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, Object> body) {
        String scenarioRaw = String.valueOf(body.getOrDefault("scenario", "greenfield"))
                .toUpperCase(Locale.ROOT);
        ScenarioType type = ScenarioType.valueOf(scenarioRaw);

        String requirement = body.get("requirement") == null
                ? catalog.defaultRequirement(type)
                : String.valueOf(body.get("requirement"));

        Map<String, String> params = new HashMap<>();
        params.put("autoApprove", String.valueOf(body.getOrDefault("autoApprove", "false")));
        params.put("failureInjection", String.valueOf(body.getOrDefault("failureInjection", "none")));

        WorkflowDefinition definition = catalog.build(type);
        WorkflowState state = engine.start(definition, requirement, type.name(), params);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(WorkflowViews.summary(state));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkflowState s : engine.all()) {
            out.add(WorkflowViews.summary(s));
        }
        return out;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return WorkflowViews.full(engine.get(id));
    }

    /** Body: { "stage": "IMPLEMENTATION", "approver": "peer", "approved": true, "comment": "..." } */
    @PostMapping("/{id}/approvals")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody Map<String, Object> body) {
        StageId stage = StageId.valueOf(String.valueOf(body.get("stage")).toUpperCase(Locale.ROOT));
        String approver = String.valueOf(body.getOrDefault("approver", "human"));
        boolean approved = Boolean.parseBoolean(String.valueOf(body.getOrDefault("approved", "true")));
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        engine.approve(id, stage, approver, approved, comment);
        return WorkflowViews.full(engine.get(id));
    }

    /** Body: { "selectedOption": "HTTPS_ONLY: ...", "resolver": "peer" } */
    @PostMapping("/{id}/clarifications")
    public Map<String, Object> clarify(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String option = String.valueOf(body.get("selectedOption"));
        String resolver = String.valueOf(body.getOrDefault("resolver", "human"));
        engine.resolveClarification(id, option, resolver);
        return WorkflowViews.full(engine.get(id));
    }

    /** Body: { "requirement": "...", "actor": "peer" } - triggers dynamic re-planning. */
    @PostMapping("/{id}/replan")
    public Map<String, Object> replan(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String requirement = String.valueOf(body.get("requirement"));
        String actor = String.valueOf(body.getOrDefault("actor", "human"));
        engine.replan(id, requirement, actor);
        return WorkflowViews.full(engine.get(id));
    }

    /** Safe-stop control. */
    @PostMapping("/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        String actor = body == null ? "human" : String.valueOf(body.getOrDefault("actor", "human"));
        engine.requestStop(id, actor);
        return WorkflowViews.summary(engine.get(id));
    }

    @GetMapping("/{id}/audit")
    public List<Map<String, Object>> audit(@PathVariable String id) {
        engine.get(id); // 404 semantics for an unknown workflow
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditEvent e : auditLog.forWorkflow(id)) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("timestamp", e.getTimestamp());
            ev.put("stage", e.getStage());
            ev.put("actor", e.getActor());
            ev.put("action", e.getAction());
            ev.put("detail", e.getDetail());
            out.add(ev);
        }
        return out;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
