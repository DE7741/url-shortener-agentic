package com.assessment.orchestrator.core.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reliability metrics: success rate, retry/rollback frequency, MTTR, e2e latency.
 * MTTR here means the mean time from a recorded failure to the workflow's next
 * recovery (a stage completing after retry or fallback).
 */
public class MetricsRegistry {

    private static class WorkflowMetrics {
        Instant startedAt;
        Instant endedAt;
        String finalStatus;
        int retries;
        int rollbacks;
        final List<Instant> failures = new ArrayList<>();
        final List<Long> recoveryMillis = new ArrayList<>();
        Instant openFailureAt; // earliest unrecovered failure
    }

    private final Map<String, WorkflowMetrics> byWorkflow = new ConcurrentHashMap<>();

    public void workflowStarted(String id) {
        WorkflowMetrics m = new WorkflowMetrics();
        m.startedAt = Instant.now();
        byWorkflow.put(id, m);
    }

    public void workflowEnded(String id, String finalStatus) {
        WorkflowMetrics m = byWorkflow.get(id);
        if (m == null) return;
        synchronized (m) {
            m.endedAt = Instant.now();
            m.finalStatus = finalStatus;
        }
    }

    public void retryRecorded(String id) {
        WorkflowMetrics m = byWorkflow.get(id);
        if (m == null) return;
        synchronized (m) { m.retries++; }
    }

    public void rollbackRecorded(String id) {
        WorkflowMetrics m = byWorkflow.get(id);
        if (m == null) return;
        synchronized (m) { m.rollbacks++; }
    }

    public void failureRecorded(String id) {
        WorkflowMetrics m = byWorkflow.get(id);
        if (m == null) return;
        synchronized (m) {
            Instant now = Instant.now();
            m.failures.add(now);
            if (m.openFailureAt == null) m.openFailureAt = now;
        }
    }

    /** Called when a previously failing stage subsequently succeeds. */
    public void recoveryRecorded(String id) {
        WorkflowMetrics m = byWorkflow.get(id);
        if (m == null) return;
        synchronized (m) {
            if (m.openFailureAt != null) {
                m.recoveryMillis.add(Duration.between(m.openFailureAt, Instant.now()).toMillis());
                m.openFailureAt = null;
            }
        }
    }

    public Map<String, Object> snapshot() {
        int total = 0, completed = 0, failed = 0, safeStopped = 0, inFlight = 0;
        long retries = 0, rollbacks = 0, failures = 0;
        List<Long> latencies = new ArrayList<>();
        List<Long> recoveries = new ArrayList<>();

        for (WorkflowMetrics m : byWorkflow.values()) {
            synchronized (m) {
                total++;
                if (m.endedAt == null) {
                    inFlight++;
                } else {
                    latencies.add(Duration.between(m.startedAt, m.endedAt).toMillis());
                    if ("COMPLETED".equals(m.finalStatus)) completed++;
                    else if ("SAFE_STOPPED".equals(m.finalStatus)) safeStopped++;
                    else failed++;
                }
                retries += m.retries;
                rollbacks += m.rollbacks;
                failures += m.failures.size();
                recoveries.addAll(m.recoveryMillis);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalWorkflows", total);
        out.put("inFlight", inFlight);
        out.put("completed", completed);
        out.put("failed", failed);
        out.put("safeStopped", safeStopped);
        int finished = completed + failed + safeStopped;
        out.put("successRate", finished == 0 ? null : (double) completed / finished);
        out.put("totalRetries", retries);
        out.put("totalRollbacks", rollbacks);
        out.put("totalFailures", failures);
        out.put("avgEndToEndLatencyMillis", average(latencies));
        out.put("mttrMillis", average(recoveries));
        return out;
    }

    private static Double average(List<Long> values) {
        if (values.isEmpty()) return null;
        long sum = 0;
        for (long v : values) sum += v;
        return (double) sum / values.size();
    }
}
