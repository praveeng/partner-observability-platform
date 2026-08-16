# Acceptance Criteria

## M1 architecture/specification gate

M1 is ready for review when:

- Requirements 1-50 in the task are addressed by the architecture, contracts, threat model, deployment model, acceptance criteria, or ADRs.
- The three data classes—partner exchange, partner-safe derived observability, internal-only—have distinct types/routes/access.
- Every M0 security-critical decision has a concrete design and ADR; remaining questions are external policy/sizing inputs and do not permit unsafe implementation defaults.
- Queue/event/payload/cardinality/timeout/retention defaults and hard maxima agree across documents.
- One Loki tenant and Grafana organization per partner plus server-side Loki/Prometheus query isolation are specified.
- Java 17/Spring Boot 2.7, AWS ECS, Terraform, Docker Compose, no Kubernetes/Helm, and no production action remain explicit.
- Documentation consistency checks pass and M1 is one clean local commit.

M1 approval authorizes implementation planning, not production deployment or production payload capture.

## Functional contract gates

- One starter dependency plus configuration integrates a supported Spring Boot 2.7 service; disabled mode requires no application code.
- Core has no Spring dependency. Optional RestTemplate, WebClient, OkHttp, Logback, Reactor, Actuator, and Micrometer integrations are classpath/bean conditional.
- Request, response, partner event, context, safe value, capture decision, and SLI models match `telemetry-contract.md`.
- RestTemplate/WebClient/OkHttp execute business I/O exactly once and preserve response bytes, streaming, cancellation, and exception semantics.
- Explicit pre-encryption/post-decryption API immediately creates a safe projection, never decrypts, and suppresses all observability failures.
- Context propagation/restoration works for servlet, configured executor, Reactor, callback wrappers, and MDC without stale cross-partner values.
- Marked structured safe logs work; arbitrary rendered SLF4J messages/throwables remain internal-only.
- All kill switches reduce capture immediately and cannot expand the startup allowlist.

## Availability and boundedness gates

- All producer paths use non-blocking bounded offers; source inspection and concurrency tests find no queue `put`, unbounded executor, unbounded buffer, or request-thread retry/network call.
- Defaults are high queue 256/4 MiB, normal queue 1,024/16 MiB, retry one batch/256 KiB, event 64 KiB, batch 128 events/256 KiB, flush 200 ms, connect 250 ms, request 1 second, shutdown drain 2 seconds.
- Queue and byte saturation drop newest with exact bounded reason metrics. No business response/status/exception changes under saturation.
- Alloy/Loki/Prometheus/Grafana/DNS/TLS/auth blackholes for 15 minutes cause bounded memory, drops, and zero observability-caused business failures.
- Dispatcher unexpected death is detected; capped restart/failure handling never shifts export onto a business thread.

## Disclosure gates

- Security corpus finds zero credentials, secrets, Authorization, tokens/JWTs, cookies, API keys, cryptographic keys, OTP/auth PINs, card data, documents/images/PDFs/signatures/binary/Base64, or unmasked phone/email/account/national ID/address in safe trees, queues, batches, Alloy outputs, Loki, metrics, dashboards, fallback diagnostics, or test reports.
- Binary/Base64/type/content/size rejection is proven before queue reservation/admission.
- Unknown paths/types/schemas/contents and classifier exceptions are omitted/dropped.
- Full sanitized mode retains every configured safe scalar within limits while removal/masking rules still win.
- Metadata-only contains no header/query/body values. None mode contains no partner record.
- Oversized bodies are omitted as a whole; no prefix is retained.
- Stage-one-only tests pass with Alloy sanitization disabled; stage-two tests independently reject injected unsafe lines.
- Only synthetic fixtures are used.

## Isolation and authorization gates

- Authenticated server context is the only partner source; spoofed public inputs/MDC cannot select context.
- Each source credential can emit only to its configured partners; unknown/conflicting source-partner mappings fail closed.
- Loki multi-tenancy is enabled, multi-tenant queries disabled, and each partner has a unique non-reused tenant.
- Ingress/query proxies strip and overwrite tenant/slot headers; direct backend access is denied.
- Each local Grafana partner user is a Viewer in exactly one organization with only that partner's provisioned datasources/dashboards.
- Arbitrary LogQL/search inputs remain inside the fixed tenant.
- `prom-label-proxy` tests cover query, query_range, series, labels/values if enabled, rules/alerts if exposed, conflicting/regex/absent matchers, and unsupported API denial.
- Concurrent thread/reactive/batch/retry/rotation/offboarding tests prove no partner leakage.

## Labels, metadata, metrics, and dashboards

- Loki streams use no more than the fixed eight labels; identifier and tenant values are absent from labels.
- Application/loan/correlation/request/partner/event/interaction IDs are validated structured metadata and searchable within a bounded time range.
- Structured metadata stays within 32 entries/8 KiB; event lines stay within 64 KiB.
- Micrometer exposes only precomputed contract meters/tags, <=10,000 series per application and <=100,000 initial series per market Prometheus.
- Alloy overwrites trusted scrape labels, drops unapproved labels/metrics, and remote-write failure cannot affect applications.
- Partner dashboards provide identifier search, journey timeline, request/response detail with omission status, and SLA/SLI volume/success/rejection/error/latency/freshness views.
- “No data” is not rendered as zero/success; formulas and exclusions are visible.

## Deployment and retention gates

- Docker Compose provides a synthetic logical stack with the same security/sanitization/tenant shape before end-to-end acceptance.
- Terraform modules match `deployment-model.md`, format/validate/static-security checks pass, and reviewed non-production plans contain no unexpected public exposure or plaintext secrets.
- PROD/STAGE/DEV stacks are independent; DEV fixtures/endpoints refer only to mocks.
- Loki uses S3 TSDB v13, structured metadata, compactor retention 384h, two-hour delete delay, and an 18-day lifecycle backstop with telemetry versioning disabled.
- A synthetic aged-data test proves data is inaccessible after the retention window plus deletion delay/backstop tolerance.
- Prometheus retention is 16 days plus size cap; Grafana/EFS/backup/internal audit policies remain separate.
- ECS task failure/restart and state restore drills document expected non-HA downtime without business impact.
- No Kubernetes, Helm, production credentials/state, or autonomous production apply exists.

## Test strategy

### Unit and property tests

- Immutable models, schema serialization, limits, identifier validators, masking, removal precedence, content detection, rate/sampling, byte budgets, kill-switch lattice, outcome mapping, and metric cardinality calculator.
- Property/fuzz tests generate nested/Unicode/malformed/adversarial data and assert prohibited sentinels never appear.

### Concurrency and framework tests

- MPSC multi-producer/single-consumer races, queue byte accounting, priority fairness, shutdown, retry slot, dispatcher recovery, and exact drop accounting.
- Spring context slices and synthetic MVC/WebFlux services for RestTemplate, WebClient, OkHttp, Logback, MDC, `@Async`, Reactor, cancellation, one-shot/duplex/streaming bodies, encryption hooks, and disabled/missing optional dependencies.

### Integration/security/end-to-end tests

- Docker Compose application -> Alloy -> Loki/Prometheus -> Grafana with at least two synthetic partners and internal-only data.
- Direct cross-tenant ingest/query, Grafana organization/API, proxy/header/PromQL bypass, credential rotation, stale config, and network-denial tests.
- Backend fault injection for latency, reset, invalid response, auth denial, full disk/limits, component restart, and compactor failure.
- Dashboard JSON/query linting and browser tests for search/timeline/detail/SLI isolation.
- Terraform format, validate, TFLint/Checkov-equivalent approved static checks, policy tests, and non-production plan assertions.

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

If host variability makes a latency percentage statistically invalid, both absolute distributions and confidence intervals are reported; the gate is not silently waived. M9 may tighten thresholds through an ADR but cannot weaken business isolation/boundedness.

## Rollout and migration gates

1. Inventory services, trusted partner identity source, clients, encryption ordering, APIs, payload schemas, current logs, and owners.
2. Deploy/validate an empty market stack with synthetic tenants and no application traffic.
3. Add starter with global disabled; prove no behavior/performance regression.
4. Enable SDK health/metrics and metadata-only for one DEV mock API; then STAGE.
5. Verify source/tenant/query/dashboards and operate kill switches/backend failure drills.
6. Add explicit plaintext hooks only where automatic clients cannot see safe plaintext.
7. Approve field schemas and enable full sanitized per API/partner, never globally.
8. Canary one service/partner, soak, then expand partner-by-partner with rollback criteria.
9. PROD enablement requires external security, service-owner, partner-access, operations, and production-change approvals.

Rollback first sets payload metadata-only, then partner/API off, then global off. Removing the starter is the last step and never required to protect business traffic.
