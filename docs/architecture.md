# Architecture

## Status and scope

This is the M1 implementable architecture for the Partner Observability Platform. It defines contracts for M2-M10; it does not claim that product functionality exists. The decisions are recorded in ADRs 0001-0008 under `decisions/`.

The supported runtime is Java 17 and Spring Boot 2.7.x. The platform uses SLF4J/Logback, Grafana Alloy, Loki, Prometheus, Grafana, Docker Compose locally, and Terraform-managed AWS ECS. Kubernetes and Helm are prohibited.

## Architectural priorities

1. Business availability is more important than telemetry completeness.
2. Disclosure safety and partner isolation are more important than capture breadth.
3. The application is the authoritative sanitization boundary; Alloy is defense in depth.
4. Telemetry delivery is explicitly best-effort and at-most-once from the application.
5. Indexed dimensions are small and bounded; transaction identifiers remain searchable as structured metadata.
6. Every market/environment deployment is operationally independent.

## Three data classes

The implementation must keep these classes distinct in types, routes, stores, access policy, and documentation:

| Class | Meaning | Permitted destination |
| --- | --- | --- |
| Partner exchange data | Raw plaintext/ciphertext exchanged with a partner or created by business logic, including request/response objects | Business process only; never placed in a telemetry queue or observability backend |
| Partner-safe derived observability | Bounded events produced from allowlisted fields after removal, masking, and binary/Base64 exclusion | The partner's Loki tenant and partner-scoped Prometheus series/Grafana organization |
| Internal-only information | Platform configuration, security/audit events, infrastructure details, stack traces, raw application logs, and operator telemetry | Internal CloudWatch/internal Grafana surfaces; never a partner tenant or partner datasource |

“Full sanitized payload” means a complete safe projection of the allowlisted textual/scalar fields that fit the limits in `payload-policy.md`. It never means a byte-for-byte payload copy. Prohibited, unknown, binary, encoded-binary, and oversized content remains absent.

## Runtime topology

```text
Partner request or outbound partner call
                 |
                 v
Spring Boot 2.7 partner service
  server-derived PartnerContext
  interceptors / explicit plaintext API / marked safe-log appender
                 |
       classify + sanitize + bound
                 |
      non-blocking offer only
                 v
  bounded priority MPSC queues ---- saturation/policy failure ---> drop counters
                 |
        one daemon dispatcher
                 |
     bounded batch, short timeout
                 v
internal TLS load balancer
                 |
Alloy ingress proxy: authenticate source and map source + partner key
                 |
Grafana Alloy: validate schema, sanitize again, stamp fixed tenant route
          /                                      \
 partner-safe event pipeline                    metric pipeline
          |                                      |
          v                                      v
 authenticated Loki gateway                 Prometheus remote-write receiver
          |                                      |
 Loki tenant per partner                     shared bounded-cardinality TSDB
          \                                      /
           server-side query isolation gateways
                          |
              Grafana organization per partner
```

Application threads never perform the network hop. Alloy, Loki, Prometheus, Grafana, DNS, AWS APIs, and configuration services are not application readiness dependencies.

## Repository module responsibilities

### `partner-observability-core`

- Immutable telemetry envelope and record types.
- Trusted `PartnerContext`, policy snapshot, capture modes, and kill-switch model.
- Streaming classifier/sanitizer and bounded safe-tree representation.
- Non-blocking MPSC queues, byte budgets, dispatcher, batching, and transport SPI.
- Context carrier APIs, explicit observation API, health metrics SPI, and deterministic sampling.
- No dependency on Spring, Reactor, OkHttp, Logback, Alloy, or Loki clients.

### `partner-observability-spring-boot-autoconfigure`

- Conditional properties and bean wiring.
- Servlet, RestTemplate, WebClient, OkHttp, Reactor, task-executor, MDC, Actuator, Micrometer, and Logback integration.
- Configuration validation and management-only kill-switch endpoint.
- Optional dependencies guarded by classpath and bean conditions.

### `partner-observability-spring-boot-starter`

- Single supported application dependency.
- Transitive core/autoconfiguration and metadata only; no behavior.

### `partner-observability-test-app`

- Synthetic MVC/reactive/client/encryption scenarios for verification only.

## Capture lifecycle

1. Resolve a trusted `PartnerContext` from the authenticated business context or an explicit trusted adapter. Untrusted request headers never directly establish it.
2. Resolve the immutable policy snapshot for `(market, environment, service, partner, api, direction)`.
3. Evaluate kill switches and deterministic sampling. Disabled or unsampled work stops before payload traversal.
4. Build bounded request/response/event metadata from configured identifiers and enums; never use raw URLs or exception text.
5. If the selected mode is `FULL_SANITIZED`, reject binary/document content and oversized candidates before parsing or copying.
6. Apply first-stage allowlisting, complete removal, masking, depth/count/string/output bounds, and Base64 exclusion.
7. Serialize an immutable safe event no larger than 64 KiB. Raw objects, byte arrays, streams, publishers, throwable graphs, and serializers are no longer referenced.
8. Reserve the queue byte budget and call non-blocking `offer`. Failure releases the reservation, records a bounded drop reason, and returns to business code.
9. A daemon dispatcher drains a bounded mixed batch, partitions it into bounded per-partner sub-batches, and sends each request with exactly one canonical partner key to Alloy. All transport exceptions are consumed internally.
10. Alloy parses only the fixed schema, performs defense-in-depth removal/masking/size checks, rejects invalid events, removes routing fields from the log line, and routes to a fixed partner tenant.

## Bounded asynchronous mechanism

The core uses bounded JCTools-style MPSC array queues rather than `BlockingQueue.put` or an executor with an implicit queue. Producers use `offer` only. One named daemon thread per application instance owns dispatch and transport state.

| Parameter | Default | Allowed range / behavior |
| --- | --- | --- |
| High-priority queue | 256 events and 4 MiB | 64-1,024 events; byte cap never above 16 MiB |
| Normal queue | 1,024 events and 16 MiB | 128-8,192 events; byte cap never above 64 MiB |
| Event serialized size | 64 KiB | Hard maximum, not configurable upward |
| Batch | 128 events or 256 KiB | Whichever occurs first |
| Flush interval | 200 ms | 50-1,000 ms |
| Connect timeout | 250 ms | Dispatcher only |
| Request timeout | 1 second | Dispatcher only |
| Retry holding slot | One batch, 256 KiB | One retry after 200 ms jitter; then drop |
| Shutdown drain | 2 seconds | JVM shutdown only; expiration drops remainder |

High priority contains failed API responses and explicit journey events. Normal priority contains successful request/response records and safe-log records. The dispatcher drains one high batch then up to three normal batches, with an explicit fairness check so neither queue starves. A drained batch may contain several partners; zero-copy views partition it by partner and each network request contains one partner only. The sum of sub-batches stays within the original 256 KiB batch bound. Priority changes observability loss order, never business outcome.

Admission also uses bounded per-partner token buckets (default 100 events/second, burst 200) and a service-wide bucket (1,000 events/second, burst 2,000). The configured partner registry is capped at 64 entries per market deployment, so rate-limiter state is bounded. Limits may be lowered per environment; increasing the hard partner cap requires an ADR and cardinality/load evidence.

Queue saturation uses drop-newest. Producers never evict or wait for existing entries. Drop reasons are the enum `DISABLED`, `NO_TRUSTED_CONTEXT`, `NOT_ALLOWLISTED`, `BINARY`, `BASE64`, `OVERSIZE`, `MALFORMED`, `RATE_LIMIT`, `QUEUE_EVENT_CAPACITY`, `QUEUE_BYTE_CAPACITY`, `SERIALIZATION`, `EXPORT_FAILURE`, or `SHUTDOWN_TIMEOUT`.

## Kill switches

An atomically replaced immutable policy snapshot provides these independently observable controls, evaluated in this order:

1. `enabled=false`: all partner telemetry capture and export stops.
2. `partners.<partnerKey>.enabled=false`: the partner is dropped before record construction.
3. `apis.<apiId>.enabled=false`: that API produces no partner records.
4. `payloadCaptureEnabled=false`: any `FULL_SANITIZED` policy is reduced to `METADATA_ONLY`.
5. Per-integration switches disable RestTemplate, WebClient, OkHttp, explicit API, or safe-log capture.
6. `exportEnabled=false`: queues are atomically drained to drop counters and new events are not admitted.

Defaults are global disabled until a service is onboarded, metadata-only for newly enabled APIs, and safe-log capture disabled. Runtime changes may be made through a management-network-only Actuator endpoint protected by the service's existing operator authentication; normal durable changes use configuration and ECS rollout. A runtime switch can only reduce capture. It cannot enable a field, partner, or API absent from the startup allowlist.

## HTTP client interception

All interceptors create request metadata immediately, call business transport exactly once, and contain their own errors. Response metadata is recorded from headers/status and duration. Payload observation follows downstream consumption and never consumes or closes a stream on behalf of business code.

### RestTemplate

- A `ClientHttpRequestInterceptor` uses configured `apiId` mappings and the current trusted context.
- Request bytes are considered only for textual allowlisted content types and within the raw candidate cap; otherwise payload is omitted.
- The response is wrapped with a tee input stream that copies at most the candidate cap while the application reads. It does not eagerly buffer the response or enable `BufferingClientHttpRequestFactory` globally.
- If the body is not consumed, only response metadata is emitted. Streaming and multipart endpoints are forced to metadata-only/no-payload.

### WebClient

- An `ExchangeFilterFunction` obtains context with `Mono.deferContextual`; Reactor context, not ThreadLocal, is authoritative.
- Request and response `DataBuffer` publishers are decorated to inspect supported textual chunks as they pass. The collector has a strict byte cap and releases its own copies; it never aggregates an unlimited body or changes demand.
- Cancellation, error, empty, streaming, and multi-subscription behavior emits at most one response record using an atomic terminal guard.
- Payload capture is opt-in. Metadata-only remains the default because body decoration is more invasive.

### OkHttp

- An application interceptor captures method, configured route/API, status, and duration.
- It never calls `RequestBody.writeTo` merely for observability because bodies may be one-shot, duplex, encrypted, or side-effectful.
- A response source wrapper can tee already-consumed supported textual bytes up to the cap; it never calls `peekBody` above a limit.
- Full request plaintext requires the explicit API. Streaming/duplex bodies are metadata-only.

Interceptor ordering is documented per service. When an explicit observation already owns payload capture, automatic interceptors emit metadata only and reuse the same `interactionId`, preventing duplicate payloads.

## Context and MDC propagation

- `PartnerContext` is immutable. Servlet entry installs it in a scoped ThreadLocal and selected safe MDC keys, then restores the previous values in `finally`.
- `InheritableThreadLocal` is forbidden. An opt-in `TaskDecorator` captures a context snapshot for known Spring executors and always clears/restores it.
- Reactor stores the context under a library-owned key. WebClient and reactive server integrations use `deferContextual`; an MDC bridge scopes values to each signal and restores the previous MDC immediately.
- `CompletableFuture`, custom executors, callbacks, and messaging integrations use explicit `ContextSnapshot.wrap(Runnable/Callable)` utilities. Unknown executors do not inherit context automatically.
- Context is never cached in singleton interceptors or transport batches. Each safe event carries its routing context immutably, which prevents cross-partner batch leakage.

MDC exposes only `correlationId`, `requestId`, `applicationId`, and `loanId` when each passes its configured identifier validator. It never contains Loki tenant credentials, raw partner headers, PII, payloads, or secrets. MDC is convenience for internal log correlation, not tenant authorization.

## Existing SLF4J/Logback logs

Arbitrary rendered logs cannot be made reliably partner-safe after formatting, so they remain internal-only and follow the service's normal ECS/CloudWatch route. They are never tailed wholesale into partner Loki.

The optional `PartnerSafeAppender` accepts only events sent to the dedicated logger `partner.observability.safe` with marker `PARTNER_SAFE`. It ignores the rendered message and throwable. It reads allowlisted structured key/value arguments plus trusted context, passes them through the same sanitizer and bounded queues, and emits a `PartnerEventRecord`. Logger name, level, literal template ID, and stable error code may be retained; raw format strings, argument `toString()`, stack traces, and exception messages are excluded. This is disabled by default.

## Encrypted integrations and explicit observation API

Instrumentation never decrypts data for observability and never captures ciphertext as a payload. If automatic interception occurs after encryption or before decryption, it records metadata only.

Applications that already possess authorized plaintext use a scoped API at the existing boundary:

```java
try (PartnerObservation observation = observations.begin(apiId, direction)) {
    observation.captureRequest(plaintextDomainObject); // sanitizes immediately
    PartnerReply reply = existingEncryptedCall();
    observation.captureResponse(reply.getAuthorizedPlaintext());
    observation.succeed(statusCode);
}
```

`captureRequest`/`captureResponse` reject bytes, streams, documents, arbitrary serializers, and unsupported types. They synchronously derive only a bounded safe tree and do not retain the domain object. Observability failures return a no-op result. `close()` emits a safe failure metadata record if no outcome was set, without using exception text. Pre-encryption capture is placed immediately before the existing encryption call; post-decryption capture is placed immediately after successful existing decryption. Encryption keys, nonces, ciphertext, and cryptographic diagnostics are removed.

## Metrics path

Micrometer records bounded counters, gauges, and timers in process. Alloy discovers private `/actuator/prometheus` targets through configured AWS Cloud Map DNS names, scrapes at 30-second intervals, drops non-contract metrics/labels, stamps trusted market/environment/service labels, validates each `partner_slot` against the source service's configured finite set, and remote-writes to Prometheus with its receiver explicitly enabled. Metrics do not use the event queues; meter registration itself is bounded by configuration.

The only approved partner dimension is `partner_slot`, an opaque onboarding value from `p001` to `p064`. It is derived from trusted context and enforced by Alloy relabeling. Raw partner IDs and every transaction identifier are prohibited metric labels. Details and SLI formulas are in `metrics-sli.md`.

## Tenant routing and query isolation

- Loki runs with multi-tenancy enabled and exactly one opaque tenant ID per `(market, environment, partner)`.
- The SDK sends a canonical partner routing key, not `X-Scope-OrgID`, to the private Alloy ingress.
- The Alloy ingress proxy authenticates a service credential and maps `(servicePrincipal, partnerKey)` to a fixed internal receiver/pipeline. Unknown or conflicting pairs are rejected; client tenant headers are stripped.
- Each generated Alloy partner pipeline overwrites routing attributes and uses a fixed Loki tenant value. It never derives tenant from event payload alone.
- Loki has no public endpoint. An authenticated reverse proxy strips incoming tenant headers and injects the mapped tenant on writes/queries.
- Multi-tenant Loki queries are disabled. Operator cross-tenant work uses separate audited internal credentials and one tenant at a time.

Grafana OSS uses one organization per partner. Each organization contains only provisioned, server-proxy datasources and dashboards for that partner. Local users are individual Viewer accounts in exactly one partner organization; partner users are never organization or server admins and Explore is disabled for the partner role where supported.

Each partner Loki datasource authenticates to the query gateway, which maps its credential to one fixed tenant. Each Prometheus datasource authenticates to an Nginx auth layer that injects a fixed `X-Partner-Slot`; a single `prom-label-proxy` parses every supported Prometheus query and enforces `partner_slot=<fixed>`. Grafana-supplied tenant/slot headers are stripped first. Direct backend network access is denied by security groups.

Grafana organizations isolate OSS datasources from other organizations, but they do not replace backend authorization. The gateway controls are mandatory. Datasource permission features that require Grafana Enterprise are not assumed.

## Loki data model and partner experience

Indexed labels are fixed to `service_name`, `deployment_environment`, `market`, `event_domain`, `event_type`, `direction`, `outcome`, and `severity`, with a maximum of eight labels per stream. Tenant identity is not duplicated as a label. API name, status code, error code, product, and journey stage remain line fields or structured metadata to avoid multiplying streams.

`event_id`, `interaction_id`, `application_id`, `loan_id`, `correlation_id`, `request_id`, and `partner_reference` are structured metadata, never labels. Loki uses TSDB index with schema v13 and structured metadata enabled. The safe JSON line contains bounded display fields and the sanitized payload projection.

Partner dashboards provide:

- Application/loan search: a required time range and identifier input filters structured metadata after a low-cardinality stream selector. Identifier input is syntax/length validated but is not authorization.
- Journey timeline: request, response, and explicit event records are sorted by `occurredAt`, with `eventSequence` only as a tie-breaker within one observation. Concurrent services are displayed as concurrent, not given a false total order.
- Detail view: selecting `eventId` shows request/response metadata and sanitized JSON; omitted payloads show mode and omission reason.
- SLA/SLI dashboard: volume, success rate, error rate/code, latency p50/p95/p99, queue drops, and telemetry coverage from the partner-scoped Prometheus datasource.

## Market deployment topology

There is one independent platform stack for each `(AWS account, market, environment, ECS cluster)` tuple:

- Production account: `<market>-PROD` cluster and PROD stack.
- Staging account: `<market>-STAGE` and `<market>-DEV` clusters and independent STAGE/DEV stacks.
- DEV integration services call mock partner services only.

Every stack is deployed into the same ECS cluster/VPC as its partner integration services but uses dedicated ECS services, task roles, security groups, Cloud Map names, storage prefixes, and secrets. No telemetry crosses market/environment boundaries.

The initial cost-conscious topology is:

| ECS service | PROD desired count | DEV/STAGE | State |
| --- | ---: | ---: | --- |
| Alloy ingress proxy + Alloy | 2 | 1 | Stateless; generated config |
| Loki single-binary | 1 | 1 | S3 TSDB v13; encrypted EFS for WAL/cache/compactor work |
| Prometheus | 1 | 1 | Encrypted EFS TSDB, 16-day and size retention caps |
| Grafana | 1 | 1 | Encrypted EFS SQLite initially; local accounts |
| Query gateway + prom-label-proxy | 2 | 1 | Stateless; generated auth mapping |

Single stateful tasks mean the initial design does not claim backend high availability. Task restart or upgrade can temporarily remove dashboards/ingestion without affecting business traffic. The migration trigger to Loki simple-scalable mode and an external Grafana database is sustained resource use above 70%, inability to meet the approved query SLO, or an approved backend HA requirement.

Loki objects use an encrypted S3 bucket per stack. Compactor retention is exactly `384h` (16 days), with a two-hour delete delay and an 18-day S3 lifecycle safety backstop. S3 versioning is disabled for telemetry objects so deleted payloads are not retained as noncurrent versions. Prometheus uses 16-day time retention plus a configurable size cap. Internal audit/config backups have a separate policy and bucket/prefix and are not partner telemetry.

## Configuration-driven onboarding

A versioned market manifest is the single non-secret source of truth. It contains market/environment, service principals and Cloud Map scrape names, partners, opaque tenant ID, `partner_slot`, Grafana organization, API IDs, route templates, direction, capture mode, field allowlist, identifier validators, rate/sample limits, local-user references, and secret ARNs. It never contains passwords or keys.

Validation enforces uniqueness, naming regexes, one tenant/slot/org per partner, no more than 64 partners, no more than 64 APIs per service, safe defaults, known capture modes/data classes, fixed retention, and source-to-partner authorization. A reviewed manifest change generates Alloy/gateway/Grafana artifacts and Terraform inputs. Onboarding order is DEV with mocks, STAGE, then PROD; removal disables capture/query first, waits 16 days, then removes tenant configuration and credentials.

## Upgrade and compatibility strategy

- Pin container images by version and digest; scan before promotion.
- Promote the same artifact DEV -> STAGE -> PROD with recorded soak and rollback criteria.
- SDK follows semantic versioning and supports event schema N and N-1 in Alloy during rolling upgrades.
- Additive safe fields require policy/cardinality review. Renames/removals use dual-read/dual-write only for safe fields and a documented expiry.
- Loki schema changes are appended with a future UTC `from` date and are never edited in place or rolled back destructively.
- Stateless services use ECS rolling/blue-green updates with health checks. Stateful Loki, Prometheus, and Grafana updates use backups where applicable, one-step version upgrades, compatibility checks, and an explicit maintenance plan.
- Kill switches can revert capture to metadata-only/off independently of an SDK rollback.

## Auditability

Git history and ADRs record schema, policy, dashboard, manifest, and Terraform changes. CI artifacts retain validation results and image/config digests. AWS CloudTrail covers infrastructure and secret access; ALB access logs and ECS/CloudWatch internal logs cover ingress and admin actions. Grafana provisioning is immutable to partner Viewers; server-admin and local-account actions use named operator identities and a ticket/approver reference. Partner telemetry is operational evidence, not an audit ledger.

Grafana OSS does not provide a complete tamper-proof audit/MFA solution. Production identity assurance and formal audit-retention requirements remain explicit questions in `decisions-needed.md`; they cannot be inferred from local accounts.

## Failure and cost posture

Backend outage, DNS/TLS/auth failure, malformed response, dispatcher death, queue saturation, invalid context/config, sanitizer error, and shutdown timeout all result in bounded telemetry loss and safe health signals. A supervisor may restart the dispatcher with capped backoff; it never runs work on business threads. Invalid startup policy prevents the observability beans from enabling capture but does not fail the Spring application context unless an operator explicitly selects strict non-production validation.

Cost is controlled through 16-day retention, S3 single-store Loki, single stateful tasks initially, bounded capture/rate/sample limits, eight labels, partner/API cardinality caps, compressed asynchronous batches, no traces, no raw logs, no binaries, and scale triggers based on measured saturation rather than speculative HA. Security boundaries are not relaxed to save cost.

## Verification and rollout

Testing is layered across unit/property/fuzz, framework contract, concurrency, Docker Compose, tenant security, dashboard/query, Terraform/static, failure injection, and performance suites. Exact gates are in `acceptance-criteria.md`.

Existing partner services roll out in phases: inventory/integration-point mapping; deploy empty backends; add starter disabled; enable health metrics; enable metadata-only for one synthetic/low-risk API; validate tenant/search/dashboards; enable explicit plaintext hooks where needed; approve full-sanitized fields per API; expand partner-by-partner; retain kill-switch and rollback evidence. No phase enables production payload capture without security and service-owner approval.
