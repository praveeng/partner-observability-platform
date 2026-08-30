# Acceptance Criteria

## M1 architecture/specification gate

M1 is ready for review when:

- Requirements 1-59 in the revised task are addressed by the architecture, contracts, threat model, deployment model, acceptance criteria, or ADRs.
- Both synchronous outbound exchanges and asynchronous initiation/acknowledgement/callback journeys are explicit. Callbacks are first-class record, integration, and dashboard types, not generic inbound logs.
- Long-lived correlation uses every available typed identifier, acknowledgement bridge records, and a deterministic tenant-fixed bounded resolver; it does not depend solely on an HTTP correlation ID or add a business-path store.
- The three data classes—partner exchange, partner-safe derived observability, internal-only—have distinct types/routes/access.
- Every M0 security-critical decision plus the HTTPS/TLS ownership and ingress boundary has a concrete design and ADR; remaining questions are external policy/sizing inputs and do not permit unsafe implementation defaults.
- Queue/event/payload/cardinality/timeout/retention defaults and hard maxima agree across documents.
- One Loki tenant and Grafana organization per partner plus server-side Loki/Prometheus query isolation are specified.
- Java 17/Spring Boot 2.7, AWS ECS, centrally owned Terraform, Docker Compose, no Kubernetes/Helm,
  and no production action from this repository remain explicit.
- Documentation consistency checks pass and M1 is one clean local commit.

M1 approval authorizes implementation planning, not production deployment or production payload capture.

## Authoritative local completion gate

`./scripts/verify-all.sh` is the authoritative local completion gate. It requires Java 17, the
pinned Gradle 7.6.4 wrapper, Docker with the Compose v2 plugin and a reachable daemon, Bash, Git,
curl, jq, and ripgrep. A missing or wrong-version prerequisite fails preflight; no suite is silently
skipped. The gate uses a Gradle clean build, reruns focused test tasks, a task-specific Gradle cache,
digest-pinned Compose images for runtime and configuration validation, unique Compose projects with
disposable volumes, UTC/C locale, and a final per-stage `PASS`/`FAIL` summary. It validates the
enterprise infrastructure requirements contract and rejects repository-owned Terraform artifacts;
it does not require or invoke a Terraform CLI. Every security command is a normal mandatory stage,
so any non-zero security result fails the aggregate gate.

The following matrix is normative. “Required completion runner” identifies the command that must provide the final automated evidence. Supporting evidence does not turn a missing end-to-end boundary into a pass.

| # | Mandatory requirement | Automated evidence | Required completion runner |
| ---: | --- | --- | --- |
| 1 | Gradle clean build | All module compilation, tests, checks, and archives from cleaned outputs | `./gradlew --no-daemon clean build` in `verify-all.sh` |
| 2 | Unit tests | Framework-independent model, policy, payload, context, and dispatch tests | `:sure-partner-observability-core:test --rerun-tasks` |
| 3 | Spring Boot starter tests | Auto-configuration module tests plus the one-starter synthetic application tests | Auto-configuration and test-app `test --rerun-tasks` tasks |
| 4 | Bounded queue tests | `BoundedTelemetryQueueTest` and `BoundedAsyncDispatcherTest` | BUILD / CORE bounded-queue stage |
| 5 | Telemetry failure isolation tests | Dispatcher publisher failure/retry tests and starter/encrypted publisher-failure business assertions | BUILD / CORE failure-isolation stage |
| 6 | RestTemplate integration tests | Normal/error/timeout/retry/concurrency behavior, starter capture, and TLS equivalence in `SyntheticPartnerClientsIntegrationTest`, `PartnerObservabilityStarterIntegrationTest`, and `TlsInstrumentationIntegrationTest` | OUTBOUND CLIENTS stage |
| 7 | WebClient integration tests | Normal/reactive-concurrency behavior, starter capture, and TLS equivalence in the same client suites | OUTBOUND CLIENTS stage |
| 8 | OkHttp integration tests | Normal behavior, starter capture, client-setting preservation, and TLS equivalence in the same client suites | OUTBOUND CLIENTS stage |
| 9 | Encrypted integration tests | `EncryptedRestTemplateFixtureIntegrationTest` and `PartnerPlaintextSchemaTest` | Encrypted integration stage |
| 10 | Async request acknowledgement | `returns202AcknowledgementsWithAnOptionalPartnerReferenceBridge` plus emitted acknowledgement assertions | CALLBACKS / ASYNC stage |
| 11 | Callback request capture | Synthetic callback journey and `CallbackRequestRecord` assertions | CALLBACKS / ASYNC stage |
| 12 | Callback response capture | Synthetic callback journey and `CallbackResponseRecord` assertions | CALLBACKS / ASYNC stage |
| 13 | Callback processing result | `separatesSuccessfulAndFailedCallbackProcessingFromHttpReceipt` and processing-record assertions | CALLBACKS / ASYNC stage |
| 14 | Callback correlation using `applicationId` | `supportsEachCallbackCorrelationShape` | CALLBACKS / ASYNC stage |
| 15 | Callback correlation using `partnerReferenceId` without `applicationId` | `supportsEachCallbackCorrelationShape` explicitly asserts the absent application ID and retained partner reference | CALLBACKS / ASYNC stage |
| 16 | Duplicate callback | `modelsRetryDuplicateAndOutOfOrderDeliveriesAsSeparateAttempts` and starter duplicate-attempt telemetry assertions | CALLBACKS / ASYNC stage |
| 17 | Callback retry | Retry delivery/attempt assertions in the lifecycle and starter suites | CALLBACKS / ASYNC stage |
| 18 | Out-of-order callback | Out-of-order sequence and unique attempt assertions | CALLBACKS / ASYNC stage |
| 19 | Unknown callback reference | Unknown partner-reference assertions after a late callback | CALLBACKS / ASYNC stage |
| 20 | Wrong-partner callback isolation | `failsClosedForInvalidSignatureAndWrongPartnerWithoutTrustedCallbackFacts` plus queue-absence assertions | CALLBACKS / ASYNC and security stages |
| 21 | Callback authentication/signature failure | Invalid-signature 401 and absence of trusted callback facts | CALLBACKS / ASYNC and security stages |
| 22 | Callback processing failure | Failed processing, background failure, and response-write separation assertions | CALLBACKS / ASYNC stage |
| 23 | Callback containing Base64 document | Hostile 5/8 MiB callback fixtures plus pre-queue/Loki absence assertions | CALLBACKS / ASYNC, payload, and data-plane security stages |
| 24 | Callback PII masking | Hostile callback retention-absence assertions plus sanitizer and Alloy mask assertions | CALLBACKS / ASYNC, payload, and data-plane security stages |
| 25 | Payload-safety tests | `PayloadSafetyTest` and `ApplicationPayloadSafetyTest` mandatory corpus | PAYLOAD / LOG SAFETY and security stages |
| 26 | Base64/document exclusion | Core, fixture, encrypted-flow, dispatcher, and Alloy sink-absence tests | PAYLOAD / LOG SAFETY and security stages |
| 27 | SLF4J compatibility | `PartnerSafeLogCompatibilityTest` | SLF4J/Logback compatibility stage |
| 28 | Secret leakage | Core removal/value corpus, selected-log tests, encrypted-flow tests, and Alloy sink scan | Security completion gate |
| 29 | PII masking | Core deterministic masks and Alloy retained-result assertions | Security completion gate |
| 30 | Binary leakage | Pre-queue binary/type/Base64 corpus and downstream absence assertions | Security completion gate |
| 31 | Docker Compose startup | Compose configuration validation and unique disposable local stacks | Data-plane and metrics-plane integration runners |
| 32 | Alloy health | Compose dependency/health wait, Alloy config validation, and live self-metrics | `test/integration/run-local-data-plane.sh` |
| 33 | Loki health | Loki config verification, Compose health wait, ingest, and query assertions | `test/integration/run-local-data-plane.sh` |
| 34 | Prometheus health | Promtool validation, Compose health wait, live query/flags/rules assertions | `test/integration/run-local-metrics-plane.sh` |
| 35 | Grafana health | Grafana `/api/health`, generated local-account authentication, one Viewer-only organization per synthetic partner, fixed datasource provisioning, and authorization denial are exercised against real containers | `scripts/test-grafana.sh` |
| 36 | End-to-end outbound request/response visibility | Real A/B RestTemplate journeys produce paired request/response records with searchable IDs and sanitized detail through each Viewer-authenticated Grafana datasource | `scripts/test-end-to-end.sh` |
| 37 | End-to-end async request -> acknowledgement -> callback journey | Real HTTP 202 journeys produce an ordered async request/ack/callback receipt/processing/response timeline through Alloy and the authorized Grafana path | `scripts/test-end-to-end.sh` |
| 38 | Transaction search | Application, loan, correlation, and partner-reference searches return application-originated records through the partner-fixed Grafana datasource | `scripts/test-end-to-end.sh` |
| 39 | Callback reference search | Application-originated callback references return only their correlated records through the partner-fixed Grafana datasource | `scripts/test-end-to-end.sh` |
| 40 | Event visibility | An exact configured partner-safe application statement becomes a correlated `PARTNER_EVENT` through the SDK dispatcher, Alloy, tenant Loki, and authorized Grafana path | `scripts/test-end-to-end.sh` |
| 41 | Metric correctness | Real success/timeout/retry traffic produces request counters, latency samples, throughput, and viable p50/p95/p99 queries through the fixed Grafana Prometheus datasource | `scripts/test-end-to-end.sh` |
| 42 | Callback metric correctness | Real callback success/retry/failure traffic produces delivery, processing, latency, and throughput values through the fixed Grafana Prometheus datasource | `scripts/test-end-to-end.sh` |
| 43 | Tenant-isolation tests | Equivalent A/B application traffic, forged tenant/slot headers, and authorized query manipulation remain fixed to the authenticated organization | `scripts/test-security.sh` and `scripts/test-end-to-end.sh` |
| 44 | Callback tenant-isolation tests | Each Viewer can retrieve only its own real callback/event records; foreign callback and loan searches return none | `scripts/test-security.sh` and `scripts/test-end-to-end.sh` |
| 45 | Same `applicationId` across partners isolation | Real A/B application traffic with the shared application ID remains partner-pure through both Viewer query paths | `scripts/test-end-to-end.sh` |
| 46 | Same `callbackReferenceId` across partners isolation | Real A/B callback traffic with the shared callback reference remains partner-pure through both Viewer query paths | `scripts/test-end-to-end.sh` |
| 47 | Enterprise infrastructure ownership contract | Required STAGE/PROD capabilities, inputs/outputs, centralized ownership, no repository Terraform artifacts, no database, discovered profile model, and LOCAL/DEV independence | `scripts/test-enterprise-infrastructure-contract.sh` |
| 48 | Dashboard/provisioning validation | JSON/provisioning/topology lint plus real Grafana API validation proves both organizations receive only their read-only fixed datasources and the generic Partner Operations dashboard | `scripts/test-grafana.sh --validate-only` |
| 49 | Documentation/configuration consistency | Mapping completeness, JSON/shell syntax, version, retention, tenancy, no-Kubernetes/Helm, and mandatory-command checks | `scripts/test-docs.sh` |

Requirements 35, 36–46, and 48 are implemented as of 2026-08-24. The Grafana runner owns provisioning and portal isolation; the end-to-end runner separately builds and drives the synthetic Spring application through the real SDK, Alloy, Loki/Prometheus, fixed query gateway, and Viewer-authenticated Grafana paths. It proves request/response and async/callback journeys, typed search, partner-safe events, real metrics/SLIs, same-ID isolation, PII masking, secret removal, and Base64 omission. Neither direct synthetic OTLP seeding nor direct Loki queries are used as requirements 36–46 acceptance evidence.

The existing performance profiles remain cross-cutting completion criteria even though they are outside the numbered 1-49 task list. `verify-all.sh` therefore also requires `scripts/test-performance.sh`; a smoke or shortened run is not a substitute for the exact acceptance table below.

## Functional contract gates

- One starter dependency plus configuration integrates a supported Spring Boot 2.7 service; disabled mode requires no application code.
- Core has no Spring dependency. Optional RestTemplate, WebClient, OkHttp, Logback, Reactor, Actuator, and Micrometer integrations are classpath/bean conditional.
- Schema-2 outbound request/response, async acknowledgement, callback request/response/processing, business event, context, correlation identifiers, safe value, capture decision, and SLI models match `telemetry-contract.md`.
- RestTemplate/WebClient/OkHttp execute business I/O exactly once and preserve response bytes, streaming, cancellation, and exception semantics.
- Automatic outbound selection requires a configuration-owned HTTPS origin and exact scheme/host/effective-port/method/path match. Missing/plaintext origins fail startup; only explicit `local-synthetic=true` LOCAL literal-loopback fixtures may use HTTP.
- RestTemplate/WebClient/OkHttp use the service-owned HTTPS transport unchanged. Starter activation does not create, install, replace, or mutate SSL contexts/socket factories, trust/hostname managers, WebClient connectors/SSL providers, OkHttp pinners/connection specifications, proxies, DNS, redirects, or TLS policies.
- Async client mappings emit one outbound request and one acknowledgement terminal record without double-counting a generic response; accepted, rejected, timeout, cancellation, and transport failure mappings are tested.
- Configured MVC/WebFlux callback interception preserves authentication/signature ordering, route mapping, body bytes, status, exceptions, async dispatch, backpressure, cancellation, and buffer ownership. Unconfigured inbound traffic emits no partner callback record.
- Callback receipt/retry, authentication/validation, processing start/terminal, and response write are distinct facts. HTTP 2xx/202 is never treated as proof of business completion.
- Explicit pre-encryption/post-decryption API immediately creates a safe projection, never decrypts, and suppresses all observability failures.
- Context propagation/restoration works for servlet, MVC async dispatch, configured executor, Reactor, callback/background wrappers, and MDC without stale cross-partner values or source-object retention.
- Disabled-by-default selected SLF4J compatibility preserves existing appenders and semantics, requires exact logger/template plus trusted partner context, optionally requires an exact marker, sanitizes only configured scalar arguments, and never copies arbitrary rendered messages, Authorization values, Base64/binary, oversized content, exceptions, or stack traces.
- All kill switches reduce capture immediately and cannot expand the startup allowlist.
- Automatic and explicit observations share interaction/attempt IDs and emit at most one request/response payload record.

## Availability and boundedness gates

- All producer paths use non-blocking bounded offers; source inspection and concurrency tests find no queue `put`, unbounded executor, unbounded buffer, or request-thread retry/network call.
- Defaults are high queue 256/4 MiB, normal queue 1,024/16 MiB, retry one batch/256 KiB, event 64 KiB, batch 128 events/256 KiB, flush 200 ms, connect 250 ms, request 1 second, shutdown drain 2 seconds.
- Queue and byte saturation drop newest with exact bounded reason metrics. No business response/status/exception changes under saturation.
- Alloy/Loki/Prometheus/Grafana/DNS/TLS/auth blackholes for 15 minutes cause bounded memory, drops, and zero observability-caused business failures.
- Dispatcher unexpected death is detected; capped restart/failure handling never shifts export onto a business thread.

## Disclosure gates

- Security corpus finds zero credentials, secrets, Authorization, tokens/JWTs, cookies, API keys, cryptographic keys, OTP/auth PINs, card data, documents/images/PDFs/signatures/binary/Base64, or unmasked phone/email/account/national ID/address in safe trees, queues, batches, Alloy outputs, Loki, metrics, dashboards, fallback diagnostics, or test reports.
- Binary/Base64/type/content/size rejection is proven before queue reservation/admission.
- A 10 MB Base64 document and binary callback candidates leave no matching content, source reference, or proportional copied allocation in any queued submission; normal large non-Base64 text follows configured size policy without being misclassified as Base64.
- Unknown paths/types/schemas/contents and classifier exceptions are omitted/dropped.
- Full sanitized mode retains every configured safe scalar within limits while removal/masking rules still win.
- Metadata-only contains no header/query/body values. None mode contains no partner record.
- Oversized bodies are omitted as a whole; no prefix is retained.
- Stage-one-only tests pass with Alloy sanitization disabled; stage-two tests independently reject injected unsafe lines.
- Only synthetic fixtures are used.

## Isolation and authorization gates

- Authenticated server context is the only partner source; spoofed public inputs/MDC cannot select context.
- Each source credential can emit only to its configured partners; unknown/conflicting source-partner mappings fail closed.
- Callback authentication/signature failure, wrong-partner identity, and route/context conflict produce no partner record and no expected-partner fallback; internal evidence contains no body or untrusted identifier.
- Loki multi-tenancy is enabled, multi-tenant queries disabled, and each partner has a unique non-reused tenant.
- Ingress/query proxies strip and overwrite tenant/slot headers; direct backend access is denied.
- Each local Grafana partner user is a Viewer in exactly one organization with only that partner's provisioned datasources/dashboards.
- Arbitrary LogQL/search inputs remain inside the fixed tenant.
- `prom-label-proxy` tests cover query, query_range, series, labels/values if enabled, rules/alerts if exposed, conflicting/regex/absent matchers, and unsupported API denial.
- Concurrent thread/reactive/batch/retry/rotation/offboarding tests prove no partner leakage.
- Tenant-fixed journey resolution cannot cross a tenant or configured correlation profile even when all seven identifier values collide. Weak IDs cannot merge incompatible stable branches; singleton conflicts, regex/malformed seeds, oversized ranges, more than eight profile candidates, excessive expansion, and direct resolver bypass fail closed or return explicit bounded partial/conflict status.

## HTTPS/TLS transport gates

- Every external partner API, acknowledgement/response, callback/webhook, ECS DEV mock, and partner Grafana connection is HTTPS/TLS. Configuration mutation tests reject HTTP in DEV/STAGE/PROD.
- A different origin using an approved partner method/path creates no automatic observation, and semantically overlapping callback route templates fail startup before list order can choose a partner.
- RestTemplate, WebClient, and OkHttp pass synthetic valid-chain tests and preserve their original unknown-CA, expired/not-yet-valid, incomplete-chain, and hostname-mismatch failures with the starter both disabled and enabled.
- Trust-all `TrustManager`/SSL contexts and permissive `HostnameVerifier` implementations are absent from production code/configuration. Static checks cover client SSL/TLS setter methods in observability modules.
- An HTTPS-to-HTTP redirect is rejected and no SDK retry, alternate client, trust bypass, or plaintext fallback occurs. The original business error/result is identical with instrumentation disabled/enabled.
- Custom partner CA trust is service-scoped, read-only, security-reviewed, hostname-validating, and delivered through approved secret/artifact references. Invalid/missing/retired trust fails closed without changing global JVM trust or exposing material.
- External callback and Grafana ALBs have only a 443 HTTPS listener, an ACM certificate ARN, a pinned approved TLS-1.2-or-newer policy, and no port-80 listener/security-group rule.
- Callback/Grafana ECS tasks use private subnets, `assign_public_ip=false`, no internet-gateway route, and target ingress only from the owning ALB security group. Direct task connection attempts fail.
- Callback tests prove spoofed forwarding headers cannot establish TLS or partner context and that ALB TLS does not replace signature/authentication/decryption.
- ACM/custom-CA rotation attaches new trust/certificate before old removal, verifies hostname/chain,
  supports rollback, and never exposes a private key in ECS, Git, central Terraform plan/output,
  logs, or telemetry.
- Partner-safe failure records contain only the fixed TLS security/failure enums and allowed metadata. Certificate chain/subject/issuer/SAN/serial/fingerprint, peer URL/host/address, cipher debug, exception text/stack, trust-store path/bytes/password, client/private key, and signature/session material are absent at queue, wire, Loki, metrics, dashboards, and diagnostics.
- ALB handshake failures before trusted callback context remain internal-only and create no partner record or expected-partner fallback.
- The local HTTP exception works only under the canonical `local` Spring profile with the isolated
  `LOCAL_SYNTHETIC` loopback/Docker fixture guard and cannot be selected by DEV/STAGE/PROD.
- The runnable Spring Boot application has properties-only `local`, `dev`, `stage`, and `prod`
  configuration; context/binding and static isolation gates pass for every profile.

## Labels, metadata, metrics, and dashboards

- Loki streams use no more than the fixed eight labels; identifier and tenant values are absent from labels.
- Application/loan/original-correlation/partner-reference/external-transaction/callback-reference/request/event/interaction/callback-attempt IDs are validated structured metadata and searchable within a bounded time range.
- Structured metadata stays within 32 entries/8 KiB; event lines stay within 64 KiB.
- Micrometer exposes only precomputed contract meters/tags, <=10,000 series per application and <=100,000 initial series per market Prometheus.
- Alloy overwrites trusted scrape labels, drops unapproved labels/metrics, and remote-write failure cannot affect applications.
- Partner dashboards provide typed identifier search, correlation confidence/coverage, an outbound/acknowledgement/callback processing timeline, record detail with omission status, and sync/async/callback SLI volume/success/rejection/error/latency/retry/write/freshness views.
- “No data” is not rendered as zero/success; formulas and exclusions are visible.

## Deployment and retention gates

- Docker Compose provides a synthetic logical stack with the same security/sanitization/tenant shape before end-to-end acceptance.
- The centralized enterprise Terraform repository implements the generic capabilities in
  `docs/enterprise-infrastructure/`; its reviewed STAGE/PROD plan/policy evidence must show no
  unexpected public exposure, plaintext secrets, or cross-market sharing.
- Central infrastructure evidence proves 443-only Grafana ALB/ACM attachment, approved TLS policy,
  private targets/no public task IP, exact ALB-to-target security-group edges, certificate ARN-only
  inputs, and no port-80 listener/rule. Callback ALB evidence remains host-service-owned and is an
  onboarding prerequisite.
- STAGE and PROD stacks are independent. Existing AWS DEV mock behavior and the local Docker stack
  are unchanged and are not prerequisites for the central Stage/Prod integration.
- Loki uses S3 TSDB v13, structured metadata, compactor retention 384h, two-hour delete delay, and an 18-day lifecycle backstop with telemetry versioning disabled.
- A synthetic aged-data test proves data is inaccessible after the retention window plus deletion delay/backstop tolerance.
- Prometheus retention is 16 days plus size cap; Grafana/EFS/backup/internal audit policies remain separate.
- ECS task failure/restart and state restore drills document expected non-HA downtime without business impact.
- No Kubernetes, Helm, production credentials/state, repository-owned enterprise Terraform, or
  autonomous production apply exists.

## Test strategy

### Unit and property tests

- Immutable models, schema serialization, limits, identifier validators, masking, removal precedence, content detection, rate/sampling, byte budgets, kill-switch lattice, outcome mapping, and metric cardinality calculator.
- Property/fuzz tests generate nested/Unicode/malformed/adversarial data and assert prohibited sentinels never appear.
- Deterministic model tests cover every callback stage and legal/illegal transition, acknowledgement bridging, duplicate/retry attempt identities, late/out-of-order sorting, missing application ID, unknown reference, timeout-then-callback, parsing/processing/write failures, and 202-before-completion.

### Concurrency and framework tests

- MPSC multi-producer/single-consumer races, queue byte accounting, priority fairness, independent callback-stage loss, shutdown, retry slot, dispatcher recovery, and exact drop accounting.
- Spring context slices and synthetic MVC/WebFlux services for RestTemplate, WebClient, OkHttp, callback filters/advice/decorators, selected Logback templates/markers, unchanged existing appenders, publisher failure, missing/multiple partner context, stack/exception exclusion, large/Base64/Authorization arguments, MDC, servlet async, `@Async`, Reactor, cancellation, one-shot/duplex/streaming bodies, authentication/signature/decryption ordering, explicit processing hooks, and disabled/missing optional dependencies.
- Synthetic TLS servers/CAs for all three clients, covering valid/invalid chains, expiry, hostname mismatch, downgrade redirect, unchanged client configuration, structured type-only failure classification, and exception/message/secret absence.

### Integration/security/end-to-end tests

- Docker Compose application -> Alloy -> Loki/Prometheus -> Grafana with at least two synthetic partners, synchronous and async/callback journeys, colliding typed identifiers, and internal-only data.
- Direct cross-tenant ingest/query, Grafana organization/API, proxy/header/PromQL bypass, credential rotation, stale config, and network-denial tests.
- Backend fault injection for latency, reset, invalid response, auth denial, full disk/limits, component restart, and compactor failure.
- Dashboard JSON/query linting and browser tests for typed search, bounded graph resolution, late/out-of-order/duplicate callback timeline, detail, SLI, and isolation.
- This repository validates the machine-readable enterprise infrastructure contract and absence of
  Terraform implementation/state. The centralized repository supplies its own format, validate,
  approved static-policy, and reviewed STAGE/PROD plan evidence before application deployment.
- ALB/ACM/SG route policy, spoofed forwarded-header, direct-task denial, certificate/trust rotation, and isolated-local-HTTP end-to-end tests.

## Performance strategy and thresholds

Benchmarks run on a pinned Java 17 runtime with fixed CPU/memory, warmup, GC/JFR capture, baseline service, three repetitions, and reported median/worst. The test environment and raw aggregate results are retained without payload samples.

| Profile | Workload | Acceptance |
| --- | --- | --- |
| Disabled | 1,000 requests/s, no capture | Added local p99 <=25 microseconds; CPU <=1 percentage point; zero allocations after warm path where measurable |
| Metadata | 1,000 events/s for 30 min, 1 KiB metadata | Producer p99 <=150 microseconds; mean <=50 microseconds; request p99 regression <=2% or 1 ms, whichever is larger |
| Full sanitized | 250 events/s for 30 min, 32 KiB safe textual candidate | Producer p99 <=2 ms; mean <=500 microseconds; CPU <=5 percentage points versus baseline |
| Saturation | Backend blackhole, 2,000 attempts/s for 15 min | Offer p99 <=100 microseconds; no business errors; queue bytes/events never exceed caps; heap plateaus within caps +32 MiB |
| Mixed soak | 80% metadata success, 10% errors, 10% full; 1,000 attempts/s for 60 min | No leak/deadlock/context crossing; exact bounded drop metrics; business p99 regression <=5% |
| Reactive | 500 concurrent streaming/cancelled calls for 30 min | No DataBuffer leak warnings, double subscription/terminal event, demand change, or unbounded accumulation |
| Callback MVC | 500 authenticated metadata callbacks/s for 30 min plus 10% async completion | Local capture p99 <=250 microseconds excluding host authentication/business work; no status/exception/body change; bounded queue/memory |
| Callback WebFlux | 500 concurrent callback bodies with 20% cancellation for 30 min | No DataBuffer leak/demand change/double record/context crossing; candidate memory stays within configured caps |
| Journey query | Synthetic retained tenant at initial sizing, 10 concurrent users and 32-key/3-round worst case | p95 <=5 s, no request exceeds the 10 s gateway deadline, all time/key/round/record/2 MiB response limits hold, and no cross-tenant query occurs |

If host variability makes a latency percentage statistically invalid, both absolute distributions and confidence intervals are reported; the gate is not silently waived. M9 may tighten thresholds through an ADR but cannot weaken business isolation/boundedness.

Q015, Q015-A, and the conflict-resolution addendum complete the executable interpretation of this
table. `test/performance/profiles.json` is the authoritative machine-readable mapping from the nine
top-level profiles to P01-P24 scenario assertions and evidence. Existing non-null values above take
precedence: in particular 500 reactive calls, 10% asynchronous MVC completion, the 80/10/10 mixed
soak, and all 30/60-minute durations remain unchanged.

The previously unspecified Disabled and Journey query measured durations are 900 seconds. Disabled
runs at 1,000 requests/s (900,000 scheduled starts; at least 891,000 must start). Journey query uses
10 VUs, 750 ms think time, at least 5,000 successful queries, and 500,000 synthetic records spread
50/30/20 over a 16-day window with at least two tenants and 10% colliding-identifier searches.
Unless a longer duration is specified in the table, each repetition has 180 seconds warm-up, its
full measured duration, and 120 seconds cool-down. Actual measured time may be at most five seconds
short only for runner accounting. Each profile runs three repetitions.

The test application is fixed at 2 vCPU/2 GiB with `-Xms512m -Xmx1024m`, 256 MiB metaspace, G1GC,
and identical JFR settings for matched comparisons. The full run requires at least 8 logical CPUs
and 12 GiB available to both host and Docker; it fails rather than scaling down. Quantitative p95,
p99, CPU, and peak-heap verdicts use the three-run median plus the approved 1.25x worst-run guard.
Every hard safety gate passes in all three repetitions. Equivalent workloads use a disabled matched
baseline on the same commit, host, limits, JVM, and workload hash within 60 minutes.

Raw JSON/JFR/container evidence stays untracked under `test/performance/evidence/<run-id>/`; a small
payload-free result set is created under `test/performance/results/<run-id>/` only after a complete
full pass. B003 closes only when `SPRING_PROFILES_ACTIVE=local PERF_MODE=full
./scripts/test-performance.sh` reports all nine profiles and all mapped scenarios passing. Smoke
mode is never B003 evidence.

## Rollout and migration gates

1. Inventory services, trusted partner identity source, clients, outbound HTTPS endpoints/redirect/custom-CA policy, callback ALB/DNS/ACM/SG ownership, callback APIs/routes, authentication/signature/decryption/idempotency/202/background-completion semantics, payload schemas, current logs, and owners.
2. Deploy/validate an empty market stack with synthetic tenants and no application traffic.
3. Add starter with global disabled; prove no behavior/performance regression.
4. Enable SDK health/metrics and metadata-only for one DEV mock synchronous API; then one async acknowledgement/callback journey; then STAGE.
5. Verify source/tenant/query/correlation/timeline/dashboards and operate kill switches/backend failure drills.
6. Add explicit plaintext hooks only where automatic clients cannot see safe plaintext.
7. Approve field schemas and enable full sanitized per API/partner, never globally.
8. Canary one service/partner, soak, then expand partner-by-partner with rollback criteria.
9. PROD enablement requires external security, service-owner, partner-access, operations, and production-change approvals.

Rollback first sets payload metadata-only, then partner/API off, then global off. Removing the starter is the last step and never required to protect business traffic.
