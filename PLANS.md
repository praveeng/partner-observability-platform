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

### M2 — Core SDK (schema-2 integration slice ready for review)

- Implemented immutable framework-independent partner context, request/response/event/envelope models, searchable high-cardinality identifier metadata, `FULL_SANITIZED`/`METADATA_ONLY`/`NO_PAYLOAD` modes, safe payload tree and omission metadata, fail-closed nested-path/field-name/type sanitizer, registered DTO extractors, monotonic kill switches, fixed-dimension health state, publisher SPI, and two bounded MPSC queues with event and byte caps.
- The dispatcher uses producer-side non-blocking `offer`, drop-newest saturation, one bounded retry holding slot on its daemon thread, partner-pure publisher batches, fixed priority fairness, exception containment, and a maximum two-second configurable shutdown bound.
- M2 security evidence covers credential/OTP/card removal, required PII masks, Authorization/JWT/known-secret value variants, PDF/JPEG/PNG/nested/non-obvious 10 MB Base64 and document-array exclusion, malformed/encrypted/oversized handling, all capture modes, DTO type confusion, structural/string/output limits, safe optional binary hashing, trusted server context, unsafe defaults, pre-queue byte accounting, and colliding application IDs across partner routing keys.
- Acceptance: the core unit suite proves event/byte boundedness, non-blocking saturation, exact drop signals, sanitizer/record-construction containment, publisher outage/recovery, concurrency, batch isolation, kill switches, and bounded shutdown. Downstream Alloy/Loki/Grafana assertions and the exact long-duration performance profiles remain M4/M5/M7/M9 gates and are not claimed by M2.
- Implemented the schema-2 seven-record model, immutable `InteractionContext`, all seven correlation identifier types, acknowledgement/callback stages, callback attempt identity, and envelope lifecycle consistency checks. Schema-1 types remain available only as N-1 migration bodies and cannot be placed in schema-2 envelopes or reinterpreted as callback facts.

### M3 — Spring Boot auto-configuration and outbound/inbound interceptors

- Implemented conditional Spring Boot 2.7 auto-configuration, validated secure-default properties, fixed configured partner/API/callback registries, RestTemplate/WebClient/OkHttp instrumentation, MVC callback transport plus explicit semantic lifecycle API, safe metadata-only WebFlux callback transport, scoped MDC/executor/Reactor context, Micrometer hooks, Actuator health, and monotonic kill switches through the one-dependency starter.
- Automatic RestTemplate capture uses already-materialized request bytes and tees response bytes only as business code consumes them. WebClient, OkHttp, streaming, one-shot, encrypted, unsupported, and oversize bodies degrade to metadata/omission rather than being replayed or buffered.
- `partner-observability-test-app` proves enabled and disabled behavior, all three clients, 4xx/5xx/timeout/connection classification, trusted retry attempt metadata, async acknowledgement bridging, callback request/response/correlation, independent duplicate/retry attempts, processing failure, outbound and callback large-document omission, unknown/wrong-partner isolation, publisher failure, and queue saturation without business behavior changes.
- Scoped M3 acceptance: 84 focused core, auto-configuration, and synthetic application tests pass with no failures or skips, including metadata-only WebFlux transport/Reactor context and MDC/task-context restoration. Full WebFlux body-decoration/backpressure testing is not claimed because the implementation intentionally remains metadata-only; M4 downstream sanitization and M9 duration profiles remain open.

### M4 — Payload/semantic integration, second-stage safety, and encrypted support

- Complete the explicit pre-encryption/post-decryption APIs and supported decoded-body advice, then implement Alloy schema-2 defense in depth without weakening application authentication or encryption. M3 already applies the M2 classifier to supported RestTemplate bytes and explicit trusted callback payloads; uncertain, streaming, reactive, OkHttp, and encrypted bodies remain metadata-only.
- Preparatory fixture complete: generated PDF/JPEG/opaque/document-array Base64 candidates and synthetic nested credential, OTP, card, and mask-required PII payloads are available on both outbound and callback paths for future sanitizer assertions. The fixture lifecycle ledger retains only bounded identifier/outcome projections, not hostile callback bodies.
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
- Preparatory fixture complete: two partner lanes, colliding application and callback-reference IDs, bounded synchronous/reactive/callback concurrency, multiple callbacks, and async lifecycle failure modes are covered by test-app integration tests. This does not claim the M9 duration/throughput gates.
- Acceptance: explicit thresholds in `docs/acceptance-criteria.md` pass with retained test evidence and no real data.

### M10 — Release documentation and package readiness

- Finalize consumer guides, configuration reference, compatibility matrix, upgrade notes, artifacts, provenance, and release checklist.
- Acceptance: reproducible clean build, package smoke test, documentation review, dependency/license review, and human release approval.

## Current focus

The schema-2 M2/M3 SDK slice is ready for review: core lifecycle/correlation types, bounded dispatch, one-starter auto-configuration, three outbound adapters, trusted MVC callbacks, metadata-only WebFlux callbacks, metrics/health hooks, and synthetic failure/saturation evidence are implemented. The test application still provides all 51 requested scenarios and now asserts production interceptor output as well as fixture behavior. M4 remains open for explicit pre-encryption/post-decryption capture and Alloy schema-2 defense in depth; M5-M8 backend/isolation/deployment work and exact M9 duration/throughput profiles are also intentionally `NOT IMPLEMENTED`. Aggregate verification must remain non-zero until those downstream gates exist.
