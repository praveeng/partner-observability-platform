# Delivery Plan

## Plan rules

Milestones are ordered; later exploratory work must not redefine an earlier security or availability contract without an ADR. A milestone can be marked complete only when its acceptance evidence is recorded and `.agent-state/status.json` is updated. `NOT IMPLEMENTED` checks are expected during foundation work and are not evidence of success.

## Milestones

### M0 — Repository foundation (complete)

- Establish the constitution, lifecycle state, documentation map, Gradle multi-module skeleton, infrastructure/test directories, and honest command entry points.
- Pin Java 17 and Spring Boot 2.7.x compatibility expectations.
- Acceptance: repository structure exists, documentation links resolve, state JSON parses, shell scripts pass syntax checks, Gradle discovers all modules, and the foundation is committed locally.

### M1 — Architecture and specification (HTTPS/TLS revision ready for review)

- Resolved D001-D015 through ADRs 0001-0010 and added ADR 0011 plus the normative HTTPS/TLS contract for outbound clients, callback/Grafana ALB ingress, ACM rotation, private ECS targets, TLS ownership, custom CA trust, safe failure metadata, local-fixture isolation, and future mTLS extensibility.
- Schema 2 defines distinct outbound request/response, async acknowledgement, callback request/response/processing, and business-event records. Read-time correlation uses typed co-occurring identifiers in a tenant-fixed bounded graph resolver, with no application-side correlation database.
- Added repository-local Codex skills for repeatable architecture, starter, payload, partner-security, Loki, Grafana, performance, test, Terraform/ECS, and release gates. Each skill reads the authoritative contracts, produces an explicit verdict, and records valid findings in repository state.
- Challenged contradictory/insufficient requirements explicitly: full sanitized is not raw capture; arbitrary rendered SLF4J logs remain internal while only exact startup-approved statement mappings may create sanitized category events; Grafana OSS requires backend query enforcement; initial stateful ECS topology is not HA; local-account production policy remains external.
- Selected 443-only external ALB listeners rather than port-80 redirects because a plaintext first hop violates the invariant and redirects can alter callback POST/authentication semantics. The starter is deliberately not a TLS policy enforcement proxy and never mutates host client TLS configuration.
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
- Added disabled-by-default compatibility for selected existing SLF4J/Logback statements. Startup configuration must match an exact unformatted template plus an exact/trailing-package logger pattern, with an optional exact marker and configured scalar argument schema. It emits only a configured `PartnerBusinessEventRecord` under trusted registry-matching partner context, sanitizes before the shared bounded dispatcher, never formats messages or reads throwables, and leaves existing appenders/CloudWatch semantics unchanged.
- Automatic RestTemplate capture uses already-materialized request bytes and tees response bytes only as business code consumes them. WebClient, OkHttp, streaming, one-shot, encrypted, unsupported, and oversize bodies degrade to metadata/omission rather than being replayed or buffered.
- `partner-observability-test-app` proves enabled and disabled behavior, all three clients, 4xx/5xx/timeout/connection classification, trusted retry attempt metadata, async acknowledgement bridging, callback request/response/correlation, independent duplicate/retry attempts, processing failure, outbound and callback large-document omission, unknown/wrong-partner isolation, publisher failure, and queue saturation without business behavior changes.
- Added immutable `transportSecurity` and bounded `transportFailureClass` facts to outbound request/response and asynchronous acknowledgement records. TLS classification walks only a bounded cause chain of known exception types; it never reads exception messages, certificates, peer values, trust stores, keys, or TLS secrets. A fixed-dimension internal Micrometer counter exposes safe failure classes.
- Generated-certificate integration tests prove the starter leaves trusted, untrusted-certificate, and wrong-host outcomes identical when disabled/enabled for RestTemplate, Reactor Netty WebClient, and OkHttp. They also prove reuse of service-owned request factories/connectors and preservation of OkHttp socket factory, trust manager, hostname verifier, pinner, and connection specifications. Static production-source checks reject TLS setters, permissive trust/hostname implementations, and HTTPS-to-HTTP rewrite literals.
- Scoped M3 acceptance: focused core, auto-configuration, and synthetic application tests pass with no failures or skips, including metadata-only WebFlux transport/Reactor context, MDC/task-context restoration, payload safety, TLS behavior equivalence, and bounded TLS metadata. Full WebFlux body decoration is intentionally unsupported and remains metadata-only; M4 downstream sanitization and M9 duration/full-certificate profiles remain open.
- Verification on 2026-08-23: the selected-log suite passes 12/12 with no skips, including missing and foreign-registry context denial, independent unsafe-content cases, first-stage masking, arbitrary-object non-rendering, and unchanged disabled/failing Logback behavior. The full auto-configuration module, `scripts/build.sh`, `scripts/test.sh`, `scripts/test-security.sh --core`, the real Alloy/Loki data-plane gate, and the real Alloy/Prometheus metrics-plane gate pass. Repository-wide verification does not pass: Terraform is unavailable, the exact M9 performance profiles report `NOT IMPLEMENTED`, and the documentation/baseline check finds trailing blank lines in two Terraform files. A local all-worktree snapshot commit was explicitly requested after these results; it does not imply whole-platform security, performance, or release readiness.

### M4 — Payload/semantic integration, second-stage safety, and encrypted support

- Implemented the minimum explicit pre-encryption/post-decryption API: a configured API scope, typed reflection-free schemas that cannot widen `safe-fields`, bounded schema discovery, automatic transport joining that discards ciphertext, and manual status-only completion for unsupported transports. Binary/stream/throwable/key/cryptographic-parameter source types fail closed. It is disabled by default and provides an inert bean when observability is disabled.
- The test application proves separate sanitized logical request and response capture, two trusted configured partner routes, ciphertext-body suppression, key/IV/credential removal, large Base64 exclusion before queueing, hook/publisher failure containment, and successful encrypted traffic while disabled.
- Verification on 2026-08-23: the forced typed-schema/encrypted-flow/disabled suite, full `./gradlew check --rerun-tasks`, `scripts/test.sh`, and `scripts/test-security.sh --core` pass. Architecture, partner-security, payload-safety, and starter static boundary checks found no scoped rejection. The aggregate `scripts/test-security.sh` and `scripts/verify-all.sh` remain non-zero only because Alloy/Loki/Grafana end-to-end security and exact M9 performance profiles report `NOT IMPLEMENTED`; no downstream, whole-platform, or performance acceptance claim is made.
- Alloy schema-2 defense in depth is implemented in M5; supported decoded-body advice remains constrained by the M4 typed-hook contract and must not weaken authentication or encryption.
- Preparatory fixture complete: generated PDF/JPEG/opaque/document-array Base64 candidates and synthetic nested credential, OTP, card, and mask-required PII payloads are available on both outbound and callback paths for future sanitizer assertions. The fixture lifecycle ledger retains only bounded identifier/outcome projections, not hostile callback bodies.
- Acceptance: prohibited classes are absent before queue admission and from every sink, including error paths and malformed inputs.

### M5 — Alloy and Loki (local data plane ready for review)

- Implemented a digest-pinned LOCAL_SYNTHETIC Compose stack with Alloy 1.18, Loki 3.7 single-binary TSDB v13, an internal-only backend network, loopback-only exposed ports, and fixed authenticated A/B/C ingress/query routes.
- Each authenticated source/partner pair maps to a route-specific Alloy receiver and fixed opaque Loki tenant. Unknown/conflicting routes fail closed; tenant headers are stripped; callback bodies and OTLP metadata cannot choose a tenant.
- Alloy accepts OTLP/HTTP logs for schema N/N-1, enforces the seven schema-2 and three legacy record names, bounds queues/retries/body/metadata, removes routing and unknown metadata, drops credential/card/Base64-shaped records, masks PII again, and emits native receiver/exporter/drop/queue metrics.
- Loki multi-tenancy and structured metadata are enabled. The exact eight indexed labels remain bounded; transaction identifiers and API/callback/timeline fields remain structured metadata and are tested with exact LogQL metadata searches.
- Local filesystem retention approximates cleanup with 24-hour retention and a two-hour delete delay. Production remains the documented encrypted S3-backed 384-hour retention with an 18-day lifecycle backstop and is not represented by this local stack.
- Verification on 2026-08-23: real Compose integration validates configuration and proves A/B/C outbound/callback isolation, colliding identifier isolation, route/header/body spoof resistance, prohibited sink absence, PII masking, exact label allowlisting, schema N/N-1 names, structured-metadata search, correlated journeys, and Alloy self-metrics. The focused M5 suite passes; Grafana query authorization, deployed-network, and exact M9 performance gates remain honestly non-zero/NOT IMPLEMENTED.
- Acceptance: local tenant-crossing and cardinality tests fail closed; the application-side bounded dispatcher tests continue to prove backend outage cannot alter business results.

### M6 — Prometheus metrics (ready for review)

- Implemented a startup-fixed Micrometer meter manifest for outbound sync/async and callback health. Bounded meters cover success, 4xx, 5xx, timeout, connection failure, trusted retry attempts, in-flight work, fixed-bucket latency, async acknowledgement outcomes/latency, authenticated callback receipt/retry/duplicate, explicit processing success/failure/rejection/latency, callback response class/write result, and internal-only authentication/context denials.
- The sole partner metric dimension is trusted opaque `partner_slot`; transaction identifiers are absent from every meter API and scrape. Legal configured meter combinations are pre-registered, runtime observations cannot add a tag value, and startup rejects the exact Prometheus series calculation above 10,000. The single starter dependency now includes Actuator and the Spring Boot 2.7 Prometheus Micrometer registry.
- Alloy scrapes the private synthetic SDK-compatible endpoint, validates the local A/B/C slots and bounded tag enums, strips arbitrary labels/metrics, overwrites trusted market/environment/service, and remote-writes through a bounded queue to digest-pinned Prometheus 3.12.0. Prometheus has private backend networking, loopback-only local query exposure, a Docker volume, 16-day/1 GB local retention limits, and disabled admin/lifecycle APIs.
- Added 22 recording rules for outbound throughput/success/availability/error/timeout/retry and p50/p95/p99, async acknowledgement acceptance, and callback throughput/processing/rejection/retry/duplicate/2xx/4xx/5xx/p50/p95/p99. No contractual threshold is enabled; an unloaded example accepts only later approved environment-owned thresholds. Request-to-callback latency remains intentionally absent because there is no trusted durable timestamp adapter and no transaction map is permitted.
- Verification on 2026-08-23: focused Micrometer tests pass across all requested outcome cases and 100,000 callbacks without meter growth; `promtool` validates all configuration/rules; the real Compose test proves scrape/relabel/remote-write, trusted label overwrite, unknown slot/metric and transaction-label removal, A/B/C slot series, retention flags, rules, and Alloy self-metrics. Full build/security/aggregate results are recorded in the M6 evidence file; M7 query authorization and M9 performance remain separate gates.
- Acceptance: metric contract tests, cardinality budgets, scrape integration checks, documentation, and state are current.

### M7 — Grafana and journey query

- Provision datasources, the tenant-fixed bounded journey resolver, callback-aware search/timeline/detail/SLI dashboards, and server-enforced partner access patterns without client-side isolation assumptions.
- Acceptance: partner access tests and dashboard/query validation pass using synthetic tenants.

### M8 — Terraform and AWS ECS (ready for review)

- Implemented the exact reusable module boundaries for an existing market VPC/ECS cluster: composition, network, identity, Loki storage, Alloy, Loki, Prometheus, Grafana, query gateway, and internal alarms. All five services use private subnets and no public IP; stateful services remain single-task while only stateless Alloy/query gateway have bounded CPU autoscaling.
- Grafana has one allowlisted/WAF-hooked public ALB listener on HTTPS 443 with ACM and a pinned approved TLS policy. Port 80 is absent. Alloy uses a private TLS NLB; Grafana, Loki, Prometheus, Alloy, query gateway, Actuator targets, EFS, S3, Secrets Manager/SSM, and AWS endpoints have explicit SG/prefix-list paths without unrestricted internal rules.
- Loki uses encrypted S3/EFS, fixed 384-hour compactor retention, two-hour delete delay, disabled object versioning, and an 18-day S3 expiration backstop. Prometheus has encrypted EFS plus fixed 16-day and environment-size retention; Grafana has encrypted EFS SQLite and daily seven-day backup selection. CloudWatch is limited to configurable-retention internal container/platform logs and alarms.
- DEV/STAGE/PROD validation examples use three synthetic partners and versioned artifact/secret ARN inputs. DEV is mock-only; PROD is disabled unless an external human workflow supplies explicit enablement and a change reference. Partner callback ALBs remain service-owned 443/ACM/private-target paths recorded as onboarding evidence; observability creates no callback or plaintext listener.
- Verification on 2026-08-23: Terraform 1.11.4 formatting and AWS-provider schema validation pass; repository policy tests prove listener/ACM/private-task/SG/IAM/storage/secret/onboarding boundaries; a fully mocked provider `command = plan` test passes without AWS access, credentials, state, or apply. TFLint, Checkov, and Trivy were unavailable locally. Evidence and scoped review verdicts are recorded under `terraform/M8-EVIDENCE.md`.
- Acceptance: formatting, validation, static security checks, and the permitted fully local/mock plan review pass. No real-account plan or deployment was performed.

### M9 — Security, performance, and end-to-end verification

- Complete adversarial disclosure, tenant isolation, backend failure, saturation, throughput, latency, and Docker Compose end-to-end suites.
- Preparatory fixture complete: two partner lanes, colliding application and callback-reference IDs, bounded synchronous/reactive/callback concurrency, multiple callbacks, and async lifecycle failure modes are covered by test-app integration tests. This does not claim the M9 duration/throughput gates.
- Extend the M3 trusted/untrusted/wrong-host client suite with expired/not-yet-valid and incomplete-chain certificates, redirect/downgrade policy, callback forwarding-header/direct-task denial, ALB/ACM/SG policy, certificate/custom-CA rotation, end-to-end TLS secret absence, and proof that local HTTP fixtures cannot escape `LOCAL_SYNTHETIC` isolation.
- Acceptance: explicit thresholds in `docs/acceptance-criteria.md` pass with retained test evidence and no real data.

### M10 — Release documentation and package readiness

- Finalize consumer guides, configuration reference, compatibility matrix, upgrade notes, artifacts, provenance, and release checklist.
- Acceptance: reproducible clean build, package smoke test, documentation review, dependency/license review, and human release approval.

## Current focus

M8 existing-cluster Terraform is ready for review with private ECS tasks, exact SG/IAM/storage boundaries, 443-only Grafana ingress, private Alloy TLS ingress, configuration-driven A/B/C examples, and local mocked-plan evidence. The selected-log and M4 encrypted-hook slices remain in the current worktree. Grafana/query authorization is still M7, while real AWS plan/runtime reachability, certificate/restore drills, and exact duration/throughput profiles remain M9/external-workflow gates; no deployment, production-readiness, whole-platform security, or performance claim is made.
