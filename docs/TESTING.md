# Testing Approach

## Layers

The first layer is unit tests on the framework-free core, which run in milliseconds
with no Spring context.

CodeGeneratorTest covers the base62 alphabet, length bounds, uniqueness over 10k draws,
and custom-code validation. UrlShortenerServiceTest covers create/resolve/delete,
custom-code conflicts, TTL expiry made deterministic with an injected mutable Clock,
URL validation, https-only enforcement, and the subdomain-aware blocklist.
TokenBucketRateLimiterTest covers burst admission up to capacity, per-client isolation,
time-based refill, runtime reconfiguration, and rejection of invalid config.

OrchestrationEngineTest is the heart of the suite. It uses small fake agents to check
the engine's semantics in isolation: dependency-graph execution with a parallel wave
and barrier (a diamond graph), approval pause and resume, rejection leading to
safe-stop, a bounded retry recovering from a transient failure (with retries and MTTR
showing up in metrics), a persistent failure running the compensation and leaving the
stage ROLLED_BACK and the workflow SAFE_STOPPED, fallback-agent recovery after retries
exhaust, the clarification pause/resume flow with lineage recorded, re-planning
invalidating and re-running all stages, the exit gate discarding an artifact that
contains a secret, and auto-approve mode still writing governance records.

The second layer is end-to-end integration tests (ScenarioEndToEndTest), which boot the
full Spring Boot app on a random port and drive it through the public REST API the way
a reviewer would. They run the greenfield scenario to completion and check all six
artifacts plus a non-trivial audit trail; run brownfield with transient failure
injection and check the retry in stage attempts, the live rate-limit change via
/api/admin/settings, and the retry count in /api/workflows/metrics; run the ambiguous
scenario through its clarification pause, resolve it via the API, and verify both the
lineage and that http:// URLs are now actually rejected; and exercise the shortener API
itself (custom codes, stats, 404 semantics, delete).

There's also a third layer baked into the system itself: the testing agent runs live
probes as a pipeline stage, and the release-readiness agent won't sign off without a
complete artifact set and a green test verdict.

## Failure injection

The reliability paths are testable features, not dead branches you have to code-review.
Setting failureInjection to transient or persistent makes the testing agent fail in a
controlled way, and that's how retry, MTTR, fallback, rollback, and safe-stop get
exercised in both the unit and integration suites.

## What I deliberately didn't test

Load and performance - out of scope for the prototype; the token bucket is the only
performance-sensitive piece and it's covered functionally. The LLM decorator's remote
call - it's fail-open by design, the deterministic path is fully covered, and the JSON
content extractor is a pure function that could get its own tests later.
