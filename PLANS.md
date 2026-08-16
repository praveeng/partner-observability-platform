# Delivery Plan

## Plan rules

Milestones are ordered; later exploratory work must not redefine an earlier security or availability contract without an ADR. A milestone can be marked complete only when its acceptance evidence is recorded and `.agent-state/status.json` is updated. `NOT IMPLEMENTED` checks are expected during foundation work and are not evidence of success.

## Milestones

### M0 — Repository foundation (complete)

- Establish the constitution, lifecycle state, documentation map, Gradle multi-module skeleton, infrastructure/test directories, and honest command entry points.
- Pin Java 17 and Spring Boot 2.7.x compatibility expectations.
- Acceptance: repository structure exists, documentation links resolve, state JSON parses, shell scripts pass syntax checks, Gradle discovers all modules, and the foundation is committed locally.

### M1 — Architecture and specification (revised; ready for review)

- Resolved D001-D015 through ADRs 0001-0010 and normative data, payload, context, queue, outbound client, first-class async acknowledgement/callback, deterministic correlation, metric, tenancy, Grafana, ECS, Terraform, upgrade, audit, testing, performance, and rollout contracts.
- Schema 2 defines distinct outbound request/response, async acknowledgement, callback request/response/processing, and business-event records. Read-time correlation uses typed co-occurring identifiers in a tenant-fixed bounded graph resolver, with no application-side correlation database.
- Added repository-local Codex skills for repeatable architecture, starter, payload, partner-security, Loki, Grafana, performance, test, Terraform/ECS, and release gates. Each skill reads the authoritative contracts, produces an explicit verdict, and records valid findings in repository state.
- Challenged contradictory/insufficient requirements explicitly: full sanitized is not raw capture; arbitrary SLF4J logs remain internal; Grafana OSS requires backend query enforcement; initial stateful ECS topology is not HA; local-account production policy remains external.
- Remaining questions in `docs/decisions-needed.md` are accountable organizational/deployment/onboarding/callback-semantic inputs with safe defaults, not silent security-critical design gaps.
- Acceptance: documentation consistency checks pass and the M1 design is committed locally for review. No product functionality is implemented.

### M2 — Core SDK (safe schema-1 baseline; schema-2 extension required)

- Implemented immutable framework-independent partner context, request/response/event/envelope models, searchable high-cardinality identifier metadata, `FULL_SANITIZED`/`METADATA_ONLY`/`NO_PAYLOAD` modes, safe payload tree and omission metadata, fail-closed nested-path/field-name/type sanitizer, registered DTO extractors, monotonic kill switches, fixed-dimension health state, publisher SPI, and two bounded MPSC queues with event and byte caps.
- The dispatcher uses producer-side non-blocking `offer`, drop-newest saturation, one bounded retry holding slot on its daemon thread, partner-pure publisher batches, fixed priority fairness, exception containment, and a maximum two-second configurable shutdown bound.
- M2 security evidence covers credential/OTP/card removal, required PII masks, Authorization/JWT/known-secret value variants, PDF/JPEG/PNG/nested/non-obvious 10 MB Base64 and document-array exclusion, malformed/encrypted/oversized handling, all capture modes, DTO type confusion, structural/string/output limits, safe optional binary hashing, trusted server context, unsafe defaults, pre-queue byte accounting, and colliding application IDs across partner routing keys.
- Acceptance: the core unit suite proves event/byte boundedness, non-blocking saturation, exact drop signals, sanitizer/record-construction containment, publisher outage/recovery, concurrency, batch isolation, kill switches, and bounded shutdown. Downstream Alloy/Loki/Grafana assertions and the exact long-duration performance profiles remain M4/M5/M7/M9 gates and are not claimed by M2.
- Before M3 acceptance, extend the sealed model to the schema-2 records/correlation identifiers and legal callback lifecycle transitions in `telemetry-contract.md`. Preserve the proven payload/queue boundary; do not reinterpret schema-1 records as callback facts.

### M3 — Spring Boot auto-configuration and outbound/inbound interceptors

- Implement conditional Spring Boot 2.7 auto-configuration, outbound RestTemplate/WebClient/OkHttp interception, and configured inbound Spring MVC/WebFlux callback transport/context interception with one-starter integration.
- Preparatory fixture complete: `partner-observability-test-app` provides executable RestTemplate, WebClient, OkHttp, local mock-partner, transport failure, retry, encryption-boundary, multi-partner, and concurrency scenarios without implementing production interceptors.
- Acceptance: opt-in configuration, compatibility, authentication/filter ordering, request/response semantic preservation, async/reactive context, request-path isolation, and disabled-mode tests pass.

### M4 — Payload/semantic integration, second-stage safety, and encrypted support

- Integrate the M2 fail-closed first-stage classifier with outbound/callback interceptors and explicit plaintext/callback-processing hooks, then implement Alloy schema-2 defense in depth without weakening application authentication or encryption.
- Preparatory fixture complete: generated PDF/JPEG/opaque/document-array Base64 candidates and synthetic nested credential, OTP, card, and mask-required PII payloads are available for future sanitizer assertions.
- Acceptance: prohibited classes are absent before queue admission and from every sink, including error paths and malformed inputs.

### M5 — Alloy and Loki

- Define local Alloy/Loki configuration, per-partner Loki tenant routing, retention, limits, safe structured metadata handling, and schema N/N-1 migration for async/callback records.
- Acceptance: backend outage cannot affect business results; tenant-crossing and cardinality tests fail closed.

### M6 — Prometheus metrics

- Implement SDK/collector/backend health and SLI metrics with bounded dimensions and documented recording/alerting rules.
- Acceptance: metric contract tests, cardinality budgets, and scrape integration checks pass.

### M7 — Grafana and journey query

- Provision datasources, the tenant-fixed bounded journey resolver, callback-aware search/timeline/detail/SLI dashboards, and server-enforced partner access patterns without client-side isolation assumptions.
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

The revised M1 architecture is ready for review; this task changed no application functionality. The hardened M2 first-stage payload/queue boundary remains valid and previously passed 41 core plus 16 synthetic test-app tests, but its schema-1 three-record model is now explicitly incomplete for the expanded async/callback contract. The clean Gradle build/test suite and implemented M2 core security gate pass in the current worktree. Whole-platform security and performance checks remain intentionally non-zero (`NOT IMPLEMENTED`) until M4-M9 and are not misreported as M1 failures or successes. After architecture review, the next implementation slice is the M2 schema-2 record/correlation-profile/lifecycle extension, followed by M3 outbound and callback interception. Later milestones must preserve the proven pre-queue safety boundary, implement downstream defense in depth/isolation and the bounded tenant-fixed query resolver, and run exact performance gates rather than silently changing numeric contracts.
