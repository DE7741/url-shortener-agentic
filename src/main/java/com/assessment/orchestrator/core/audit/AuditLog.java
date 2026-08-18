package com.assessment.orchestrator.core.audit;

import com.assessment.orchestrator.core.model.StageId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Audit trail: append-only in-memory log plus a JSONL file per workflow, which
 * survives process restarts and supports offline review.
 */
public class AuditLog {

    private final Map<String, List<AuditEvent>> events = new ConcurrentHashMap<>();
    private final Path directory; // null disables file persistence (used by tests)

    public AuditLog(Path directory) {
        this.directory = directory;
        if (directory != null) {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new IllegalStateException("cannot create audit directory " + directory, e);
            }
        }
    }

    public AuditEvent record(String workflowId, StageId stage, String actor, String action, String detail) {
        AuditEvent event = new AuditEvent(Instant.now(), workflowId, stage, actor, action, detail);
        events.computeIfAbsent(workflowId, k -> new CopyOnWriteArrayList<>()).add(event);
        persist(event);
        return event;
    }

    public List<AuditEvent> forWorkflow(String workflowId) {
        return new ArrayList<>(events.getOrDefault(workflowId, List.of()));
    }

    private void persist(AuditEvent e) {
        if (directory == null) return;
        String line = "{\"ts\":\"" + e.getTimestamp() + "\""
                + ",\"workflow\":\"" + escape(e.getWorkflowId()) + "\""
                + ",\"stage\":" + (e.getStage() == null ? "null" : "\"" + e.getStage() + "\"")
                + ",\"actor\":\"" + escape(e.getActor()) + "\""
                + ",\"action\":\"" + escape(e.getAction()) + "\""
                + ",\"detail\":\"" + escape(e.getDetail()) + "\"}\n";
        Path file = directory.resolve(e.getWorkflowId() + ".jsonl");
        try {
            Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            // best-effort in the prototype; the in-memory trail stays authoritative
            System.err.println("audit persistence failed: " + ex.getMessage());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
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
