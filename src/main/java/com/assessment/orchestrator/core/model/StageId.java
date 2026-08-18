package com.assessment.orchestrator.core.model;

/** SDLC stages the orchestrator coordinates. */
public enum StageId {
    REQUIREMENTS,
    IMPACT_ANALYSIS,   // brownfield only
    ARCHITECTURE,
    IMPLEMENTATION,
    TESTING,
    DOCUMENTATION,
    RELEASE_READINESS
}
