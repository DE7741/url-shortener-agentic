# Agentic Software Engineering System - URL Shortener

This is a working prototype that takes a plain-English requirement and drives it through
a full SDLC using an agentic execution model. There are two layers to it:

1. The target system: a URL shortener service with shorten/redirect/analytics APIs,
   TTL expiration, per-client rate limiting, runtime safety controls, and a health endpoint.
2. The orchestration layer: an agentic SDLC coordinator that plans, executes, validates,
   and governs changes to the target system. It runs on an explicit dependency graph with
   entry/exit gates, human approval checkpoints, bounded retries, fallback, rollback,
   safe-stop, policy guardrails, an audit trail, reliability metrics, and dynamic re-planning.

The principle I built around: agents execute under defined autonomy boundaries, and humans
own oversight, approvals, and final quality.

## Setup

You need JDK 17+ and Maven 3.8+.

```bash
mvn clean test          # unit + integration tests (all three scenarios run end-to-end)
mvn spring-boot:run     # starts on http://localhost:8080
```


```bash
OPENAI_API_KEY=sk-... mvn spring-boot:run
```

## Quick demo (3 scenarios)

### 1. Greenfield - "Add link expiration (TTL)"

```bash
# start with human-in-the-loop approvals
curl -s -X POST localhost:8080/api/workflows -H 'Content-Type: application/json' \
  -d '{"scenario":"greenfield"}'
# note the returned "id", then watch it pause at IMPLEMENTATION:
curl -s localhost:8080/api/workflows/<id> | jq '.status, .pendingApprovals'

# approve the high-impact stages as they arrive
curl -s -X POST localhost:8080/api/workflows/<id>/approvals -H 'Content-Type: application/json' \
  -d '{"stage":"IMPLEMENTATION","approver":"peer","approved":true,"comment":"design reviewed"}'
curl -s -X POST localhost:8080/api/workflows/<id>/approvals -H 'Content-Type: application/json' \
  -d '{"stage":"RELEASE_READINESS","approver":"peer","approved":true}'

curl -s localhost:8080/api/workflows/<id> | jq '.status, .artifacts[].name'
```

### 2. Brownfield - "Raise redirect burst capacity without raising sustained rate"

This one adds an IMPACT_ANALYSIS stage (codebase reasoning). You can also inject failures
to watch the reliability controls work:

```bash
# transient: attempt 1 fails, a bounded retry recovers (watch MTTR in metrics)
curl -s -X POST localhost:8080/api/workflows -H 'Content-Type: application/json' \
  -d '{"scenario":"brownfield","autoApprove":true,"failureInjection":"transient"}'

# persistent: retries exhaust -> rollback restores the rate limiter -> safe-stop
curl -s -X POST localhost:8080/api/workflows -H 'Content-Type: application/json' \
  -d '{"scenario":"brownfield","autoApprove":true,"failureInjection":"persistent"}'

curl -s localhost:8080/api/workflows/<id>/audit | jq '.[].action'
curl -s localhost:8080/api/workflows/metrics | jq
```

### 3. Ambiguous - "Make short links safer"

The requirements agent spots the unmeasurable term, refuses to guess, and pauses until
a human picks an interpretation:

```bash
curl -s -X POST localhost:8080/api/workflows -H 'Content-Type: application/json' \
  -d '{"scenario":"ambiguous","autoApprove":true}'
curl -s localhost:8080/api/workflows/<id> | jq '.pendingClarification'

curl -s -X POST localhost:8080/api/workflows/<id>/clarifications -H 'Content-Type: application/json' \
  -d '{"selectedOption":"HTTPS_ONLY: reject non-HTTPS target URLs at creation time","resolver":"peer"}'

# the hardening is live:
curl -s -X POST localhost:8080/api/urls -H 'Content-Type: application/json' \
  -d '{"url":"http://insecure.example.com"}'          # -> 400 policy error
```

### Re-planning

```bash
curl -s -X POST localhost:8080/api/workflows/<id>/replan -H 'Content-Type: application/json' \
  -d '{"requirement":"Add link expiration with a default TTL of 30 days","actor":"peer"}'
# all stages invalidate and re-run; decision lineage and audit history are preserved
```










Orchestrator:

| Endpoint | Description |
 

| `POST /api/workflows` `{scenario, requirement?, autoApprove?, failureInjection?}` | start a workflow |
| `GET /api/workflows` / `GET /api/workflows/{id}` | list / full state (stages, artifacts, decision lineage) |
| `POST /api/workflows/{id}/approvals` | human approval checkpoint (approve/reject) |
| `POST /api/workflows/{id}/clarifications` | resolve an ambiguity |
| `POST /api/workflows/{id}/replan` | amend requirement, triggers re-planning |
| `POST /api/workflows/{id}/stop` | safe-stop |
| `GET /api/workflows/{id}/audit` | audit trail |
| `GET /api/workflows/metrics` | success rate, retries, rollbacks, MTTR, e2e latency |

## Project layout

```
src/main/java/com/assessment/
  shortener/            target system (framework-free core + thin Spring web layer)
    domain|store|service|ratelimit|web
  orchestrator/
    core/               framework-free orchestration engine
      graph/            dependency graph + stage nodes
      engine/           coordinator: waves, gates, retries, rollback, re-planning
      agent/            agent SPI (AgentExecutor / AgentContext / AgentResult)
      policy/           guardrails (secret scan, change control)
      audit/            append-only audit log (memory + JSONL)
      metrics/          reliability metrics
    agents/             SDLC agents, the ShortenerOps surface, LLM decorator
    scenario/           scenario catalog (builds the graph per scenario)
    web/                REST surface for workflows, governance, observability
```

More detail in `docs/ARCHITECTURE.md` (design and control flow),
`docs/ENGINEERING_SUMMARY.md` (plan, risks, trade-offs, assumptions, limitations),
and `docs/TESTING.md` (testing approach).
