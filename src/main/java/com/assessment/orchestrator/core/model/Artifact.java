package com.assessment.orchestrator.core.model;

import java.time.Instant;

/** An output produced by a stage (design doc, code notes, test report, ...). */
public class Artifact {

    private final String name;
    private final String type;         // e.g. "markdown", "json", "config-change"
    private final String content;
    private final StageId producedBy;
    private final Instant createdAt;

    public Artifact(String name, String type, String content, StageId producedBy, Instant createdAt) {
        this.name = name;
        this.type = type;
        this.content = content;
        this.producedBy = producedBy;
        this.createdAt = createdAt;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getContent() { return content; }
    public StageId getProducedBy() { return producedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
