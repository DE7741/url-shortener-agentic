# Final Engineering Summary

## Plan and rationale

The assignment really asks for two things that are easy to conflate: a URL shortener,
and an agentic system that engineers the URL shortener. So the plan was:

1. Build a clean, framework-free target system (the shortener core) with deliberate
   runtime levers - a reconfigurable rate limiter, safety settings, TTL - so the agents
   have real, reversible operations to perform instead of simulated ones.
2. Build the orchestration engine as an explicit dependency-graph executor with gates
   rather than a linear chain, because every governance requirement (approvals, policy,
   rollback, re-planning) attaches naturally to graph nodes and edges.
3. Drive all three required scenarios (greenfield, brownfield, ambiguous) through the
   same engine with scenario-specific graphs and agents, to show the model generalizes
   instead of being hard-coded per demo.
4. Make the reliability paths (retry, fallback, rollback, safe-stop) demonstrable on
   demand through controlled failure injection, and observable through the audit trail
   and metrics.

## Artifacts produced per run

requirements.md (normalized requirement plus acceptance criteria), impact-analysis.md
(brownfield only), design.md (approach, alternatives, trade-offs), implementation-notes.md
(what changed, before/after), test-report.md (live probe results), release-notes.md, and
readiness-checklist.md. On top of those: the decision lineage, the audit trail
(`./data/audit/<workflow>.jsonl`), and the reliability metrics.

## Risks, trade-offs, and failure scenarios considered

| Risk / trade-off | Position taken |
|---|---|
| Agent alters production state incorrectly | Narrow `ShortenerOps` surface; approval gate before IMPLEMENTATION; compensation registered before the stage completes; rollback proven by test |
| A "successful" agent emits unsafe output | Exit-gate artifact scan; violating outputs are discarded, never committed |
| Ambiguous requirement gets mis-implemented | The requirements agent refuses to guess; the workflow parks until a human selects an interpretation, and the choice lands in the lineage |
| A flaky step wedges the pipeline | Bounded retries with backoff, an optional fallback agent, then rollback and safe-stop; MTTR is measured |
| Parallel stages corrupt shared state | The engine commits results under a per-workflow lock after the wave barrier; agents get immutable context snapshots |
| Approval fatigue in demos hides governance | autoApprove mode synthesizes approvals but still writes decision and audit records, so the governance trail never has holes |
| LLM unavailability breaks the system | The LLM is a decorator, never on the critical path; any failure degrades to the deterministic result |
| Coordinator waits on a wave while holding the workflow lock | Accepted for the prototype (approvals only arrive while parked); noted under limitations |

## Assumptions

Single-node deployment, and in-memory state is acceptable for a prototype (the
interfaces exist for a real store). AuthN/AuthZ is handled outside the service at a
gateway; the admin and governance endpoints would be locked down in production.
"Implementation" by agents means runtime-configuration-level change through an approved
surface - the agents don't rewrite source code in this prototype, which keeps the
change-control story honest: every mutation is enumerable, reversible, and gated.
The failure-injection parameter stands in for real-world nondeterministic failures.

## Limitations (known and accepted)

Workflow state doesn't survive a process restart, though the audit JSONL does; a
persistence layer behind `WorkflowState` would be the first production hardening step.
The requirements agent's ambiguity detection is heuristic (marker terms) - in a real
deployment this is exactly where an LLM adds the most value, and the seam for it
already exists. Approvals and stops are honored at stage boundaries, not mid-stage.
Reconfiguring the rate limiter resets its buckets, which opens a brief window of extra
allowance; the design artifact calls this out. The LLM decorator builds and parses its
JSON by hand instead of pulling in a client SDK, which I considered acceptable since
it's optional and fail-open.

## Validation

There are 20+ unit tests over the framework-free core, including ten engine-semantics
tests covering parallelism, gates, retries, rollback, clarification, re-planning, and
policy enforcement. Four end-to-end tests boot the full application and drive all three
scenarios through the public API, asserting live system effects (the rate limit
actually changed, http URLs actually rejected) rather than just workflow status. A
standalone harness (`verification/Harness.java`) also exercised the orchestration core
end-to-end during development: 23/23 checks passed, including metrics (success rate 0.8
across 5 runs with one intentional safe-stop, 3 retries, 1 rollback, MTTR around 100ms)
and a 27-event audit trail for the failure run.
