package com.assessment.orchestrator.core.agent;

/**
 * A unit of autonomous work for one SDLC stage. Implementations can be deterministic
 * (the default) or LLM-backed; the engine treats both the same, since the autonomy
 * boundaries live in the engine rather than the agent.
 */
public interface AgentExecutor {

    String name();

    AgentResult execute(AgentContext context) throws Exception;
}
