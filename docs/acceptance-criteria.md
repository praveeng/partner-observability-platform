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
- Java 17/Spring Boot 2.7, AWS ECS, Terraform, Docker Compose, no Kubernetes/Helm, and no production action remain explicit.
- Documentation consistency checks pass and M1 is one clean local commit.

M1 approval authorizes implementation planning, not production deployment or production payload capture.

## Functional contract gates

- One starter dependency plus configuration integrates a supported Spring Boot 2.7 service; disabled mode requires no application code.
- Core has no Spring dependency. Optional RestTemplate, WebClient, OkHttp, Logback, Reactor, Actuator, and Micrometer integrations are classpath/bean conditional.
- Schema-2 outbound request/response, async acknowledgement, callback request/response/processing, business event, context, correlation identifiers, safe value, capture decision, and SLI models match `telemetry-contract.md`.
- RestTemplate/WebClient/OkHttp execute business I/O exactly once and preserve response bytes, streaming, cancellation, and exception semantics.
- RestTemplate/WebClient/OkHttp use the service-owned HTTPS transport unchanged. Starter activation does not create, install, replace, or mutate SSL contexts/socket factories, trust/hostname managers, WebClient connectors/SSL providers, OkHttp pinners/connection specifications, proxies, DNS, redirects, or TLS policies.
- Async client mappings emit one outbound request and one acknowledgement terminal record without double-counting a generic response; accepted, rejected, timeout, cancellation, and transport failure mappings are tested.
- Configured MVC/WebFlux callback interception preserves authentication/signature ordering, route mapping, body bytes, status, exceptions, async dispatch, backpressure, cancellation, and buffer ownership. Unconfigured inbound traffic emits no partner callback record.
- Callback receipt/retry, authentication/validation, processing start/terminal, and response write are distinct facts. HTTP 2xx/202 is never treated as proof of business completion.
- Explicit pre-encryption/post-decryption API immediately creates a safe projection, never decrypts, and suppresses all observability failures.
- Context propagation/restoration works for servlet, MVC async dispatch, configured executor, Reactor, callback/background wrappers, and MDC without stale cross-partner values or source-object retention.
- Marked structured safe logs work; arbitrary rendered SLF4J messages/throwables remain internal-only.
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
- RestTemplate, WebClient, and OkHttp pass synthetic valid-chain tests and preserve their original unknown-CA, expired/not-yet-valid, incomplete-chain, and hostname-mismatch failures with the starter both disabled and enabled.
- Trust-all `TrustManager`/SSL contexts and permissive `HostnameVerifier` implementations are absent from production code/configuration. Static checks cover client SSL/TLS setter methods in observability modules.
- An HTTPS-to-HTTP redirect is rejected and no SDK retry, alternate client, trust bypass, or plaintext fallback occurs. The original business error/result is identical with instrumentation disabled/enabled.
- Custom partner CA trust is service-scoped, read-only, security-reviewed, hostname-validating, and delivered through approved secret/artifact references. Invalid/missing/retired trust fails closed without changing global JVM trust or exposing material.
- External callback and Grafana ALBs have only a 443 HTTPS listener, an ACM certificate ARN, a pinned approved TLS-1.2-or-newer policy, and no port-80 listener/security-group rule.
- Callback/Grafana ECS tasks use private subnets, `assign_public_ip=false`, no internet-gateway route, and target ingress only from the owning ALB security group. Direct task connection attempts fail.
- Callback tests prove spoofed forwarding headers cannot establish TLS or partner context and that ALB TLS does not replace signature/authentication/decryption.
- ACM/custom-CA rotation attaches new trust/certificate before old removal, verifies hostname/chain, supports rollback, and never exposes a private key in ECS, Git, Terraform plan/output, logs, or telemetry.
- Partner-safe failure records contain only the fixed TLS security/failure enums and allowed metadata. Certificate chain/subject/issuer/SAN/serial/fingerprint, peer URL/host/address, cipher debug, exception text/stack, trust-store path/bytes/password, client/private key, and signature/session material are absent at queue, wire, Loki, metrics, dashboards, and diagnostics.
- ALB handshake failures before trusted callback context remain internal-only and create no partner record or expected-partner fallback.
- The local HTTP exception works only under an isolated `LOCAL_SYNTHETIC` loopback/Docker profile with synthetic data and cannot be selected by any ECS environment manifest.

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
- Terraform modules match `deployment-model.md`, format/validate/static-security checks pass, and reviewed non-production plans contain no unexpected public exposure or plaintext secrets.
- Terraform policy tests prove 443-only Grafana ALB/ACM attachment, approved TLS policy, private targets/no public task IP, exact ALB-to-target security-group edges, certificate ARN-only inputs, and no port-80 listener/rule. Callback ALB evidence remains owned by the host service and is an onboarding prerequisite.
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
- Deterministic model tests cover every callback stage and legal/illegal transition, acknowledgement bridging, duplicate/retry attempt identities, late/out-of-order sorting, missing application ID, unknown reference, timeout-then-callback, parsing/processing/write failures, and 202-before-completion.

### Concurrency and framework tests

- MPSC multi-producer/single-consumer races, queue byte accounting, priority fairness, independent callback-stage loss, shutdown, retry slot, dispatcher recovery, and exact drop accounting.
- Spring context slices and synthetic MVC/WebFlux services for RestTemplate, WebClient, OkHttp, callback filters/advice/decorators, Logback, MDC, servlet async, `@Async`, Reactor, cancellation, one-shot/duplex/streaming bodies, authentication/signature/decryption ordering, explicit processing hooks, and disabled/missing optional dependencies.
- Synthetic TLS servers/CAs for all three clients, covering valid/invalid chains, expiry, hostname mismatch, downgrade redirect, unchanged client configuration, structured type-only failure classification, and exception/message/secret absence.

### Integration/security/end-to-end tests

- Docker Compose application -> Alloy -> Loki/Prometheus -> Grafana with at least two synthetic partners, synchronous and async/callback journeys, colliding typed identifiers, and internal-only data.
- Direct cross-tenant ingest/query, Grafana organization/API, proxy/header/PromQL bypass, credential rotation, stale config, and network-denial tests.
- Backend fault injection for latency, reset, invalid response, auth denial, full disk/limits, component restart, and compactor failure.
- Dashboard JSON/query linting and browser tests for typed search, bounded graph resolution, late/out-of-order/duplicate callback timeline, detail, SLI, and isolation.
- Terraform format, validate, TFLint/Checkov-equivalent approved static checks, policy tests, and non-production plan assertions.
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
