# Delivery Plan

## Plan rules

Milestones are ordered; later exploratory work must not redefine an earlier security or availability contract without an ADR. A milestone can be marked complete only when its acceptance evidence is recorded and `.agent-state/status.json` is updated. `NOT IMPLEMENTED` checks are expected during foundation work and are not evidence of success.

## Milestones

### M0 — Repository foundation (complete)

- Establish the constitution, lifecycle state, documentation map, Gradle multi-module skeleton, infrastructure/test directories, and honest command entry points.
- Pin Java 17 and Spring Boot 2.7.x compatibility expectations.
- Acceptance: repository structure exists, documentation links resolve, state JSON parses, shell scripts pass syntax checks, Gradle discovers all modules, and the foundation is committed locally.

### M1 — Architecture and specification (ready for review)

- Resolved D001-D015 through ADRs 0001-0008 and normative data, payload, context, queue, client, metric, tenancy, Grafana, ECS, Terraform, upgrade, audit, testing, performance, and rollout contracts.
- Added repository-local Codex skills for repeatable architecture, starter, payload, partner-security, Loki, Grafana, performance, test, Terraform/ECS, and release gates. Each skill reads the authoritative contracts, produces an explicit verdict, and records valid findings in repository state.
- Challenged contradictory/insufficient requirements explicitly: full sanitized is not raw capture; arbitrary SLF4J logs remain internal; Grafana OSS requires backend query enforcement; initial stateful ECS topology is not HA; local-account production policy remains external.
- Remaining questions in `docs/decisions-needed.md` are accountable organizational/deployment/onboarding inputs with safe defaults, not silent security-critical design gaps.
- Acceptance: documentation consistency checks pass and the M1 design is committed locally for review. No product functionality is implemented.

### M2 — Core SDK (ready for review)

- Implemented immutable framework-independent partner context, request/response/event/envelope models, searchable high-cardinality identifier metadata, capture modes, safe payload tree and omission metadata, fail-closed schema sanitizer, monotonic kill switches, fixed-dimension health state, publisher SPI, and two bounded MPSC queues with event and byte caps.
- The dispatcher uses producer-side non-blocking `offer`, drop-newest saturation, one bounded retry holding slot on its daemon thread, partner-pure publisher batches, fixed priority fairness, exception containment, and a maximum two-second configurable shutdown bound.
- M2 security evidence covers credential/OTP/card removal, required PII masks, PDF/JPEG/PNG/nested/unknown Base64 and document-array exclusion, malformed/encrypted/oversized handling, all capture modes, structural limits, trusted server context, unsafe defaults, and colliding application IDs across partner routing keys.
- Acceptance: the core unit suite proves event/byte boundedness, non-blocking saturation, exact drop signals, sanitizer/record-construction containment, publisher outage/recovery, concurrency, batch isolation, kill switches, and bounded shutdown. Downstream Alloy/Loki/Grafana assertions and the exact long-duration performance profiles remain M4/M5/M7/M9 gates and are not claimed by M2.

### M3 — Spring Boot auto-configuration and interceptors

- Implement conditional Spring Boot 2.7 auto-configuration and supported HTTP/client interception with one-starter integration.
- Preparatory fixture complete: `partner-observability-test-app` provides executable RestTemplate, WebClient, OkHttp, local mock-partner, transport failure, retry, encryption-boundary, multi-partner, and concurrency scenarios without implementing production interceptors.
- Acceptance: opt-in configuration, compatibility, context startup, request-path isolation, and disabled-mode tests pass.

### M4 — Payload safety and encrypted integration support

- Implement fail-closed payload classification and the approved design for observing supported encrypted integrations without weakening application encryption.
- Preparatory fixture complete: generated PDF/JPEG/opaque/document-array Base64 candidates and synthetic nested credential, OTP, card, and mask-required PII payloads are available for future sanitizer assertions.
- Acceptance: prohibited classes are absent before queue admission and from every sink, including error paths and malformed inputs.

### M5 — Alloy and Loki

- Define local Alloy/Loki configuration, per-partner Loki tenant routing, retention, limits, and safe structured metadata handling.
- Acceptance: backend outage cannot affect business results; tenant-crossing and cardinality tests fail closed.

### M6 — Prometheus metrics

- Implement SDK/collector/backend health and SLI metrics with bounded dimensions and documented recording/alerting rules.
- Acceptance: metric contract tests, cardinality budgets, and scrape integration checks pass.

### M7 — Grafana

- Provision data sources, dashboards, and server-enforced partner access patterns without client-side isolation assumptions.
- Acceptance: partner access tests and dashboard/query validation pass using synthetic tenants.

### M8 — Terraform and AWS ECS

- Build reusable Terraform modules and non-production examples for the approved AWS ECS topology, networking, identity, encryption, and secret references.
- Acceptance: formatting, validation, static security checks, and non-production plan review pass. No deployment is performed by default.

### M9 — Security, performance, and end-to-end verification

- Complete adversarial disclosure, tenant isolation, backend failure, saturation, throughput, latency, and Docker Compose end-to-end suites.
- Preparatory fixture complete: two partner lanes, colliding application IDs, bounded synchronous concurrency, and reactive concurrency are covered by test-app integration tests. This does not claim the M9 duration/throughput gates.
- Acceptance: explicit thresholds in `docs/acceptance-criteria.md` pass with retained test evidence and no real data.

### M10 — Release documentation and package readiness

- Finalize consumer guides, configuration reference, compatibility matrix, upgrade notes, artifacts, provenance, and release checklist.
- Acceptance: reproducible clean build, package smoke test, documentation review, dependency/license review, and human release approval.

## Current focus

M1 and M2 are implemented and ready for review. The framework-neutral core now provides the safe pre-queue boundary and bounded dispatcher; it deliberately contains no Spring-specific auto-configuration or backend integration. The synthetic test-app remains an authorized preparatory fixture for M3/M4/M9. Next implementation work is M3 after review accepts the M2 APIs and evidence. Later milestones must implement downstream defense in depth, isolation, and exact performance gates against the numeric contracts rather than silently changing them.
