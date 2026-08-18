# Architecture Overview

## Components

```
                        +--------------------------------------------------+
   human (REST)  <----> |  Governance API  (/api/workflows/*)              |
   approvals,           +--------------------------------------------------+
   clarifications,                          |
   replan, stop                             v
                        +--------------------------------------------------+
                        |  OrchestrationEngine (framework-free core)       |
                        |   - dependency graph traversal (waves + barrier) |
                        |   - entry gates: approval gate, policy gate      |
                        |   - exit gates: artifact policy scan             |
                        |   - bounded retry -> fallback -> rollback        |
                        |   - safe-stop, dynamic re-planning               |
                        +---------+------------+------------+-------------+
                                  |            |            |
                           PolicyEngine    AuditLog    MetricsRegistry
                                  |
                        +---------v----------------------------------------+
                        |  SDLC agents (AgentExecutor SPI)                 |
                        |  requirements | impact-analysis | architecture   |
                        |  implementation | testing | documentation        |
                        |  release-readiness   [+ optional LLM decorator]  |
                        +---------+----------------------------------------+
                                  |  ShortenerOps (narrow autonomy surface)
                        +---------v----------------------------------------+
                        |  URL shortener target system                     |
                        |  service / store / rate limiter / safety settings|
                        +--------------------------------------------------+
```

## Orchestration model

The workflow is an explicit dependency graph (`WorkflowDefinition`), validated at
construction: every dependency must exist and the graph must be acyclic, checked with
Kahn's algorithm. The stages for the standard scenarios:

```
REQUIREMENTS -> [IMPACT_ANALYSIS]* -> ARCHITECTURE -> IMPLEMENTATION(A,H)
    -> { TESTING || DOCUMENTATION }  -> RELEASE_READINESS(A,H)

* brownfield only     (A) approval checkpoint    (H) high-impact    || parallel
```

Execution is non-linear and stateful, not a task chain. Each iteration the engine
computes the set of ready stages (all dependencies completed) and runs them as a
parallel wave with a synchronization barrier. TESTING and DOCUMENTATION genuinely run
concurrently, and RELEASE_READINESS waits on both.

Entry gates run before a stage is dispatched. The approval gate parks the workflow in
AWAITING_APPROVAL until a human decides. The policy gate enforces change control: a
high-impact stage can never execute without an approval on record. That rule holds even
in auto-approve demo mode, where the approval is synthesized but still audited.

Exit gates run before outputs are committed. The policy engine scans artifacts for
things like leaked credentials, and a stage that "succeeds" but emits a violating
artifact is failed with its outputs discarded.

Context and lineage carry across stages. Agents get an immutable `AgentContext`
(requirement, prior artifacts, params, attempt number, clarification resolution) and
return an `AgentResult`. The engine, never the agent, commits artifacts and
`DecisionRecord`s into workflow state. Every artifact says which stage produced it and
every decision has an actor, a rationale, and a timestamp.

## Reliability controls

| Control | Mechanism |
|---|---|
| Bounded retries | per-stage attempt budget (`orchestrator.max-retries`, default 2) with linear backoff |
| Fallback | optional per-stage fallback agent, invoked after retries exhaust |
| Rollback | agents register compensations (undo closures) on success; on a fatal failure the engine runs them in reverse order and marks stages ROLLED_BACK |
| Safe-stop | cooperative stop honored at stage boundaries; approval rejection also safe-stops |
| Metrics | success rate, retry/rollback counts, failure count, MTTR (failure to recovery), end-to-end latency |
| Audit | append-only in-memory trail plus a JSONL file per workflow; every gate decision, attempt, retry, rollback, and human action gets a record |

## Controlled autonomy

Three boundaries keep the agents inside their lane. First, the ops surface: agents act
on the live system only through `ShortenerOps`, a deliberately narrow interface, so
nothing outside it can be touched. Second, agents propose and the engine commits: agent
output only enters workflow state after exit gates pass. Third, human checkpoints:
IMPLEMENTATION (before the system is altered) and RELEASE_READINESS (before sign-off)
need explicit approval, and ambiguous requirements pause for a human interpretation
instead of letting an agent guess.

## Dynamic re-planning

Two triggers. When a clarification is resolved, the REQUIREMENTS stage re-runs with the
human's interpretation and everything downstream is transitively invalidated: statuses
reset, stage-produced artifacts removed, and stage approvals revoked, since an approval
of the old plan shouldn't bless the new one. When the requirement is amended through
`POST /{id}/replan`, all stages invalidate and re-run, even on a completed workflow.
Lineage and audit history are preserved in both cases.

## Key decisions

1. Framework-free core. The engine and business logic have no Spring or Jackson
   dependencies. It unit-tests in milliseconds without a container, and the
   orchestration logic reads as plain Java.
2. Deterministic agents with a pluggable LLM decorator. The prototype has to be
   runnable with zero external dependencies. LLM calls (OpenAI-compatible, via
   `java.net.http`) are additive: they enrich artifacts with a review narrative and
   degrade gracefully on any failure. The LLM is never on the critical path and never
   holds write authority.
3. Real side effects instead of simulated ones. The implementation agent actually
   reconfigures the live rate limiter and safety settings, and the testing agent runs
   live probes against the running service. Rollback therefore demonstrably restores
   real state, which the tests assert.
4. In-memory store behind a `UrlStore` interface. Right-sized for a prototype; a
   JDBC or Redis implementation swaps in without touching the services.
5. Per-workflow coordinator lock plus a stage thread pool. State transitions are
   serialized (easy to reason about, no lost updates) while stages within a wave run
   concurrently. The trade-off is noted in ENGINEERING_SUMMARY.
