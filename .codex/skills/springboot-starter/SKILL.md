---
name: springboot-starter
description: Guide or review the Java 17 and Spring Boot 2.7 starter, auto-configuration, HTTP client interception, context propagation, and SDK dependency boundaries. Use when implementing or changing core SDK, starter, auto-configuration, RestTemplate, WebClient, OkHttp, MDC, or reactive integration.
---

# Spring Boot Starter

Keep normal service adoption to one starter dependency plus configuration while preserving business availability.

## Load authoritative requirements

Read `AGENTS.md`, `PLANS.md`, `.agent-state/status.json`, `docs/architecture.md`, `docs/telemetry-contract.md`, `docs/payload-policy.md`, `docs/security-invariants.md`, `docs/acceptance-criteria.md`, ADR 0002, ADR 0003, and relevant unresolved decisions. Use repository documents for current capacities, timeouts, schemas, and supported versions rather than restating them here.

## Workflow

1. Determine whether the change belongs in core, auto-configuration, starter, or the test app; preserve the dependency direction defined by the architecture.
2. Trace enablement, disabled mode, bean conditions, optional client-library classpaths, capture policy selection, context creation, terminal completion, queue offer, and exception containment.
3. For each supported client, prove exactly-one request and response record per completed exchange, including exception, cancellation, streaming, and retry behavior as applicable.
4. Prove thread-local/MDC cleanup and explicit context bridging across executors and Reactor. Never treat MDC alone as authoritative partner identity.
5. Run the applicable checks and report evidence.

## Required checks

- Java 17 and Spring Boot 2.7 compatibility are encoded in build and tests.
- The starter transitively supplies required modules; optional RestTemplate, WebClient, and OkHttp integrations activate only when their classes and required configuration exist.
- Disabled mode creates no telemetry work and does not require observability backends.
- Interceptors observe bounded copies without globally buffering, consuming, or reserializing business bodies.
- Plaintext hooks are explicit and safe for pre-encryption/post-decryption paths that automatic interceptors cannot see.
- Partner context comes from trusted server configuration/resolution, is immutable for a record, propagates across async/reactive boundaries, and is cleared after terminal completion.
- Every queue and retry path is bounded, non-blocking on business threads, drop-on-saturation, and exception-contained.
- Business modules depend only on the public SDK contract, never on Loki, Alloy, Grafana, or Prometheus internals.
- Tests cover duplicate-registration, multiple builders, missing optional dependencies, exception paths, cancellations, large/streaming bodies, and context leakage.

## Commands

Run commands that exist for the current milestone:

```bash
git diff --check
rg -n "sourceCompatibility|toolchain|JavaLanguageVersion|org.springframework.boot" *.gradle partner-observability-* --glob '*.gradle' --glob '*.java'
rg -n "RestTemplate|WebClient|OkHttp|MDC|Reactor|Context" partner-observability-* test
rg -n "loki|alloy|grafana" partner-observability-core partner-observability-spring-boot-autoconfigure partner-observability-spring-boot-starter --glob '*.java'
./gradlew check
./scripts/test.sh
./scripts/test-security.sh
```

When a module-specific test task exists, run it in addition to the aggregate checks. Missing tests, non-zero exits, or `NOT IMPLEMENTED` are `FAIL` for the corresponding implemented claim.

## Verdict

`PASS` requires all applicable checks above, passing unit/integration/security evidence, correct module boundaries, and no business-path availability regression. `FAIL` on missing required coverage, ambiguous ownership, client-controlled context, blocking backend access, unbounded buffering, duplicate emission, or exception leakage.

Never weaken, delete, skip, quarantine, or relax a test or requirement merely to obtain `PASS`; fix the implementation or record the failure.

## Record valid findings

Set `.agent-state/status.json` to `VERIFYING` during validation, then `READY_FOR_REVIEW` for a scoped pass or `IN_PROGRESS` for failure. Use only existing fields: put a concise outcome in `summary`, concrete remediation in `nextActions`, and genuine blockers in `blockers`. Update `PLANS.md` when milestone evidence changes, and add unresolved design choices to `docs/decisions-needed.md`. Respect the constitution's restriction on `BLOCKED` and `COMPLETE`.
