# Delivery Plan

## Repository identity

The repository/project is Sure Partner Observability (`sure-partner-observability`). SDK modules and artifacts use `sure-partner-observability-*`, Java code uses `com.samsung.sure.partner.observability.*`, and the Gradle group is `com.samsung.sure`. Public configuration, telemetry contracts, and established runtime identifiers remain unchanged for compatibility.

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
- Added repository-local Codex skills for repeatable architecture, starter, payload, partner-security,
  Loki, Grafana, performance, test, enterprise-infrastructure/ECS, and release gates. Each skill reads
  the authoritative contracts, produces an explicit verdict, and records valid findings in
  repository state.
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
- `sure-partner-observability-test-app` proves enabled and disabled behavior, all three clients, 4xx/5xx/timeout/connection classification, trusted retry attempt metadata, async acknowledgement bridging, callback request/response/correlation, independent duplicate/retry attempts, processing failure, outbound and callback large-document omission, unknown/wrong-partner isolation, publisher failure, and queue saturation without business behavior changes.
- Added immutable `transportSecurity` and bounded `transportFailureClass` facts to outbound request/response and asynchronous acknowledgement records. TLS classification walks only a bounded cause chain of known exception types; it never reads exception messages, certificates, peer values, trust stores, keys, or TLS secrets. A fixed-dimension internal Micrometer counter exposes safe failure classes.
- Generated-certificate integration tests prove the starter leaves trusted, untrusted-certificate, and wrong-host outcomes identical when disabled/enabled for RestTemplate, Reactor Netty WebClient, and OkHttp. They also prove reuse of service-owned request factories/connectors and preservation of OkHttp socket factory, trust manager, hostname verifier, pinner, and connection specifications. Static production-source checks reject TLS setters, permissive trust/hostname implementations, and HTTPS-to-HTTP rewrite literals.
- Adversarial review on 2026-08-23 found and fixed cross-origin outbound misclassification by requiring exact configured HTTPS origins, plus configuration-order-dependent callback matching by rejecting overlapping route templates. Failing-first regressions are retained. Runtime TLS coverage now includes expired certificates for all three clients without unsafe exception-text classification.
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

### M7 — Grafana and journey query (B001 boundary ready for review)

- Implemented real Grafana 11.6.5 provisioning with one organization per synthetic partner, generated local Viewer accounts, no anonymous/signup/Explore/Viewer editing, and the same generic Partner Operations dashboard loaded into both isolated organizations.
- Each organization has only fixed, non-editable Loki and Prometheus proxy datasources. Datasource secrets come from generated environment values through `secureJsonData`; Nginx strips client routing headers and injects a server-fixed Loki tenant or Prometheus slot through the internal `prom-label-proxy`. Loki, Prometheus, and the label proxy have no partner-reachable network path.
- The generic dashboard implements typed transaction search, first/last/current overview with elapsed-time reduction, an ascending timeline that keeps callback receipt and processing distinct, selected sanitized detail including omission/retry/error fields, and outbound/callback SLI panels without fabricated contractual thresholds.
- `test/integration/run-local-grafana.sh` is the executable boundary. It validates live health/provisioning/authentication, exact single-Viewer membership, datasource/dashboard secrecy, A/B searches and colliding application IDs, timeline/detail/payload absence, fixed-tenant/slot queries, API/Explore/org/header/UID/PromQL bypasses, SLI values, and safe gateway audit records. `scripts/verify-all.sh` invokes the full runner at requirement 35 and the live `--validate-only` mode at requirement 48.
- Verification on 2026-08-24: `./scripts/test-grafana.sh`, `./scripts/test-grafana.sh --validate-only`, `./scripts/test-security.sh --data-plane`, and `./scripts/test-security.sh --metrics-plane` pass in the current worktree. This closes B001/requirements 35 and 48; the separately implemented M9 runner owns the application-originated requirements 36–46 proof.
- Acceptance: partner access tests and dashboard/query validation pass using synthetic tenants.

### M8 — Enterprise infrastructure requirements and AWS ECS boundary (ready for review)

- The separate centralized enterprise Terraform repository exclusively owns ECS base services,
  networking, IAM, encrypted persistence, ingress, service discovery, and base Alloy/Loki/Prometheus/
  Grafana runtime for STAGE and PROD. This repository owns the generic, implementation-neutral
  requirements and GHA handoff in `docs/enterprise-infrastructure/`.
- The contract requires private tasks/subnets, least-privilege security-group edges and IAM,
  HTTPS-443 Grafana ingress with ACM, WAF/IP-allowlist hooks, encrypted Loki S3 storage, bounded
  infrastructure logging, health checks, secret references, and stable outputs for the application
  release workflow. It preserves one market/environment deployment with multiple isolated partners.
- LOCAL remains the existing Docker topology and requires no AWS. DEV remains AWS plus mocked
  partners and receives no new infrastructure requirement. Only STAGE and PROD consume this
  enterprise integration contract.
- This repository continues to own application-level Alloy policy, Loki tenant/retention policy,
  Prometheus rules/metrics, Grafana dashboards/alerts, Java artifacts, and tests. After a manually
  reviewed and manually executed central Terraform change, enterprise GHA deploys or updates those
  application assets. The current architecture requires no relational database or Liquibase.
- The former repository-owned Terraform modules and mocked provider test were classified as
  enterprise implementation, their useful constraints were migrated into the contract and ADR 0013,
  and the active Terraform trees were retired. Their 2026-08-23 validation remains historical
  evidence only and is not a claim about the centralized repository.
- Acceptance: `scripts/test-enterprise-infrastructure-contract.sh` passes, no Terraform
  implementation/state exists here, local workflows remain independent, and reviewed central
  STAGE/PROD plan/policy evidence is required before any real rollout. No AWS access or deployment
  was performed.
- Verification on 2026-08-30: the contract validator, compatibility alias, adversarial static gate,
  documentation gate, and clean Gradle build pass. The aggregate local gate passed 20/23 stages,
  including all Java/Spring, Alloy/Loki, Prometheus, application end-to-end, contract, live
  Grafana validate-only, and documentation stages. B003 failed exactly at the pre-existing Q015
  guard; the full Grafana stage and its repetition inside security hit the known bounded SLI
  readiness timeout, then the unchanged full Grafana runner passed standalone. No regression is
  attributed to this documentation/ownership change.

### M9 — Security, performance, and end-to-end verification

- Complete adversarial disclosure, tenant isolation, backend failure, saturation, throughput, latency, and Docker Compose end-to-end suites.
- Preparatory fixture complete: two partner lanes, colliding application and callback-reference IDs, bounded synchronous/reactive/callback concurrency, multiple callbacks, and async lifecycle failure modes are covered by test-app integration tests. This does not claim the M9 duration/throughput gates.
- B002 is implemented by `test/integration/run-local-end-to-end.sh`. It builds the application and SDK, drives real RestTemplate and HTTP 202/delayed-callback journeys for both fixed synthetic partners, and proves requirements 36–46 through Alloy, tenant Loki/Prometheus, the query gateway, and Viewer-authenticated Grafana datasource/dashboard APIs. The same run proves typed search, ordered callback detail, selected partner-safe events, metrics/SLIs, bidirectional and colliding-ID isolation, PII masking, secret/card/OTP removal, and a successful 5 MiB callback with Base64 omission.
- Verification on 2026-08-24: the focused B002 runner passes; the authoritative `scripts/verify-all.sh` result is `FINAL RESULT: FAIL (1 of 22 stages failed)`, with requirements 36–46 and the complete local security gate passing. Only the independently open B003 full-duration performance gate reports `NOT IMPLEMENTED`.
- Extend the M3 trusted/untrusted/wrong-host client suite with expired/not-yet-valid and incomplete-chain certificates, redirect/downgrade policy, callback forwarding-header/direct-task denial, ALB/ACM/SG policy, certificate/custom-CA rotation, end-to-end TLS secret absence, and proof that local HTTP fixtures cannot escape `LOCAL_SYNTHETIC` isolation.
- Acceptance: explicit thresholds in `docs/acceptance-criteria.md` pass with retained test evidence and no real data.
- The 2026-08-24 adversarial review update closes the local Grafana/query-authorization and application-to-authorized-query blockers. The production verdict remains BLOCKED by callback ALB staging evidence, redirect/chain/rotation drills, and full-duration resilience/performance tests.

### M10 — Release documentation and package readiness

- Finalize consumer guides, configuration reference, compatibility matrix, upgrade notes, artifacts, provenance, and release checklist.
- Acceptance: reproducible clean build, package smoke test, documentation review, dependency/license review, and human release approval.

## Enterprise Java naming migration (ready for review)

- Migrated all 185 production and test Java sources, package paths, imports, reflection references,
  and Spring Boot discovery metadata to `com.samsung.sure.partner.observability.*`, with no
  compatibility namespace.
- Renamed all four Java/Spring Gradle modules and artifacts to `sure-partner-observability-*`, set
  the Gradle group to `com.samsung.sure`, and updated build, Docker, integration, documentation,
  and repository-local agent references without changing public configuration or telemetry names.
- Added the permanent enterprise naming gate and a one-starter consumer context test. The naming
  gate, clean build, packaged startup, security, the then-existing Terraform validation, Grafana,
  and end-to-end checks passed. That Terraform result is retained only as historical evidence after
  the enterprise ownership boundary changed in ADR 0013.
  Aggregate verification passed 21/23 stages: the known B003/Q015 blocker remained non-zero and
  one Grafana SLI readiness check timed out transiently even though the unchanged full Grafana
  runner passed standalone and again within the aggregate security gate.
- B001 and B002 remain ready for review. B003 remains blocked on the same Q015 inputs; no
  performance profile, threshold, runtime identifier, or deployment state was reset or invented.

## Current focus

The canonical Spring runtime model is now `local`, `dev`, `stage`, and `prod`, with properties-only
configuration for the runnable synthetic application, external activation, lowercase telemetry
identity, and a permanent isolation gate. This configuration migration does not change B001/B002
evidence or close B003.

The current focus after B001 and B002 is the remaining host callback ALB staging evidence,
redirect/certificate lifecycle drills, and exact M9 saturation/soak profiles. The local Grafana
organization/query boundary and application-originated requirements 36–46 boundary are ready for
review, and the SDK binds automatic outbound observations to exact configured HTTPS origins and
rejects ambiguous callback routes. M8 now records a ready-for-review central-enterprise-infrastructure
requirements boundary for STAGE/PROD; it does not claim that the central repository has implemented
or deployed it, nor does it make a production-readiness, whole-platform security, or performance
claim.
