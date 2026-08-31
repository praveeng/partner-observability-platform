# Architecture

## Status and scope

This architecture defines contracts for M2-M10, including asynchronous acknowledgements, callbacks, and HTTPS/TLS transport ownership. The schema-2 core, scoped M3 Spring integration, selected-log bridge, and explicit encrypted-plaintext hooks are implemented; backend, query, deployment, remaining transport-policy evidence, and full performance work remain later milestones.

The supported runtime is Java 17 and Spring Boot 2.7.x. The platform uses SLF4J/Logback, Grafana Alloy, Loki, Prometheus, Grafana, Docker Compose locally, and AWS ECS provisioned by the separate centralized enterprise Terraform repository. Kubernetes and Helm are prohibited.

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

Transport classification refines, but does not add a raw telemetry class:

| Transport class | Examples | Treatment |
| --- | --- | --- |
| Partner exchange | HTTPS request/response/callback plaintext or ciphertext | Business process only; normal payload policy applies to any derived projection |
| Partner-safe transport metadata | Configured API ID, `TLS`, bounded handshake/certificate/hostname failure class, duration | May be included in the existing partner-safe record after trusted context and sanitization |
| Internal-only transport information | ALB request/target data, raw peer host/address, certificate subject/issuer/SAN/fingerprint/serial, renewal diagnostics | Internal operations only |
| Transport security secrets | Private/client keys, keystore/trust-store bytes and passwords, session/signature material | Never telemetry, logs, metrics, dashboards, Git, manifests, or Terraform values |

Detailed certificate identity is not required for partner-safe diagnosis. If a client cannot classify a TLS failure from structured exception types without reading an exception message, it emits a less-specific bounded TLS failure enum.

## Runtime topology

```text
Synchronous call, async initiation, callback, or explicit business event
                 |
                 v
Spring Boot 2.7 partner service
  server-derived PartnerContext
  outbound + callback interceptors / explicit semantic API / safe logger
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
              + bounded journey resolver
                          |
              Grafana organization per partner
```

Application threads never perform the network hop. Alloy, Loki, Prometheus, Grafana, DNS, AWS APIs, and configuration services are not application readiness dependencies.

## External HTTPS/TLS architecture

HTTPS/TLS is a hard boundary for all external partner traffic in `dev`, `stage`, and `prod`. Synchronous calls, asynchronous initiations and acknowledgements, callbacks/webhooks, ECS DEV mock partners, and partner Grafana access never use plaintext HTTP. The sole exception is the `local` profile's guarded `LOCAL_SYNTHETIC` Docker/loopback fixture with synthetic data and no path to a deployed environment or real partner.

```text
Outbound: private ECS task -- service-owned validated HTTPS client --> partner API

Inbound:  partner -- HTTPS :443 --> ALB + ACM
                                  [approved external TLS termination]
                                  --> private ECS callback target
                                  --> host auth/signature/decryption
                                  --> trusted callback interception
```

The host integration owns endpoint URI, redirect behavior, TLS versions/ciphers, `SSLContext`, trust manager, hostname verifier, custom CA, proxy, certificate pinning, and future client keys. The starter does not create, install, replace, mutate, relax, or inspect those settings. It uses the already-configured client and may classify only a structured terminal failure. It never retries a TLS handshake independently and never falls back or redirects from HTTPS to HTTP.

External callback and Grafana ALBs expose an HTTPS listener on port 443 only, use an approved TLS-1.2-or-newer policy and ACM certificate, and expose no port-80 listener or ingress rule. The ALB is the approved external termination boundary; it is not callback authentication. Callback ECS tasks use private subnets, no public IP, and an inbound rule only from the ALB security group. Host-service infrastructure owns callback ALBs; the centralized enterprise Terraform repository owns the Grafana ALB. Full certificate, custom trust, failure, rotation, local-fixture, and future-mTLS contracts are normative in `transport-security.md` and ADR 0011.

## Supported business interaction shapes

Synchronous partner integration is one observed exchange:

```text
Samsung/service -- HTTPS + OutboundApiRequestRecord --> Partner API
Samsung/service <-- HTTPS + OutboundApiResponseRecord -- Partner API
                  shared interactionId
```

Asynchronous partner integration is a journey of separate facts, not one long HTTP span:

```text
Samsung/service -- HTTPS + OutboundApiRequestRecord (ASYNC_REQUEST_SENT) --> Partner async API
Samsung/service <-- HTTPS + AsyncAcknowledgementRecord (ASYNC_ACK_RECEIVED) -- acknowledgement
                                  minutes or hours pass
Partner -- HTTPS/ALB + CallbackRequestRecord (CALLBACK_RECEIVED/RETRY_RECEIVED) --> Samsung/service
           CallbackProcessingEventRecord: AUTHENTICATED / VALIDATED / STARTED
           CallbackProcessingEventRecord: PROCESSED or PROCESSING_FAILED
Partner <-- HTTPS/ALB + CallbackResponseRecord (CALLBACK_RESPONSE_SENT) -------- Samsung/service
```

`CALLBACK_RECEIVED` and `CALLBACK_PROCESSED` are always separate. A callback HTTP response may occur before background processing completes, and a successful business result may precede a failed response write. The event timestamps preserve those facts instead of forcing a single synchronous model.

## Repository module responsibilities

### `sure-partner-observability-core`

- Immutable schema-2 outbound, acknowledgement, callback request/response/processing, and business-event records.
- Trusted `PartnerContext`, policy snapshot, capture modes, and kill-switch model.
- Streaming classifier/sanitizer and bounded safe-tree representation.
- Non-blocking MPSC queues, byte budgets, dispatcher, batching, and transport SPI.
- Context carrier APIs, explicit outbound/callback observation APIs, health metrics SPI, and deterministic sampling.
- No dependency on Spring, Reactor, OkHttp, Logback, Alloy, or Loki clients.

### `sure-partner-observability-spring-boot-autoconfigure`

- Conditional properties and bean wiring.
- Servlet MVC callback, WebFlux callback, RestTemplate, WebClient, OkHttp, Reactor, task-executor, MDC, Actuator, Micrometer, and Logback integration.
- Configuration validation and management-only kill-switch endpoint.
- Optional dependencies guarded by classpath and bean conditions.

### `sure-partner-observability-spring-boot-starter`

- Single supported application dependency.
- Transitive core/autoconfiguration and metadata only; no behavior.

### `sure-partner-observability-test-app`

- Synthetic MVC/reactive/client/encryption scenarios for verification only.

### `sure-partner-observability-local-test-support`

- Optional non-production auto-configuration for an explicitly selected real service's `local`
  runtime; deployed applications still use the ordinary starter dependency.
- Supplies one startup-validated fixed partner route to the existing bounded dispatcher. It cannot
  derive a tenant from a request, callback body, telemetry record, or dashboard input.
- Activates only for `local` plus `local-synthetic`, accepts only the isolated `/v1/logs` gateway,
  and contains no partner-specific Java behavior.

### Local verification layers

Generic local validation always uses the controlled MVC/reactive test applications and remains the
B001/B002/B003 boundary. Optional real-service validation requires one exact
`TARGET_PARTNER_SERVICE`; deterministic resolution reads only that direct SureWebServices child.
Its OpenAPI preparation retains structural contract metadata, compares partner-neutral interaction
patterns, and fails readiness for an operation without an explicit observability decision. A
reviewed target-owned adapter handles only service-specific startup, mock-partner behavior, and
journey driving. The platform owns the fixed-route gateway, local telemetry/query assertions, and
evidence contract. No runtime source generation, generated OpenAPI Java edits, artifact publication,
AWS access, or cross-service aggregation occurs.

## Capture lifecycle

1. Resolve a trusted `PartnerContext` from authenticated business/server state or an explicit trusted adapter. For callbacks, hold only the server ingress timestamp until authentication succeeds; untrusted route/header/body values never directly establish partner context.
2. Resolve the immutable policy snapshot for `(market, environment, service, partner, api, interactionKind, leg)`.
3. Evaluate kill switches and deterministic sampling. Disabled or unsampled work stops before payload traversal.
4. Build bounded record metadata and all available typed correlation identifiers from configured extractors/enums; never use raw URLs, exception text, or identifiers for authorization.
5. If the selected mode is `FULL_SANITIZED`, reject binary/document content and oversized candidates before parsing or copying.
6. Apply first-stage allowlisting, complete removal, masking, depth/count/string/output bounds, and Base64 exclusion.
7. Serialize an immutable safe event no larger than 64 KiB. Raw objects, byte arrays, streams, publishers, throwable graphs, and serializers are no longer referenced.
8. Reserve the queue byte budget and call non-blocking `offer`. Failure releases the reservation, records a bounded drop reason, and returns to business code.
9. A daemon dispatcher drains a bounded mixed batch, partitions it into bounded per-partner sub-batches, and sends each request with exactly one canonical partner key to Alloy. Callback records use the same mechanism; no callback thread waits for export. All transport exceptions are consumed internally.
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

High priority contains failed API/callback responses, callback processing failures, and explicit journey events. Normal priority contains successful request/response/acknowledgement/callback records and safe-log records. The dispatcher drains one high batch then up to three normal batches, with an explicit fairness check so neither queue starves. A drained batch may contain several partners; zero-copy views partition it by partner and each network request contains one partner only. The sum of sub-batches stays within the original 256 KiB batch bound. Priority changes observability loss order, never business outcome. Receipt and completion can therefore be lost independently; dashboards expose coverage rather than infer a missing fact.

Admission also uses bounded per-partner token buckets (default 100 events/second, burst 200) and a service-wide bucket (1,000 events/second, burst 2,000). The configured partner registry is capped at 64 entries per market deployment, so rate-limiter state is bounded. Limits may be lowered per environment; increasing the hard partner cap requires an ADR and cardinality/load evidence.

Queue saturation uses drop-newest. Producers never evict or wait for existing entries. Drop reasons are the enum `DISABLED`, `NO_TRUSTED_CONTEXT`, `NOT_ALLOWLISTED`, `BINARY`, `BASE64`, `OVERSIZE`, `MALFORMED`, `RATE_LIMIT`, `QUEUE_EVENT_CAPACITY`, `QUEUE_BYTE_CAPACITY`, `SERIALIZATION`, `EXPORT_FAILURE`, or `SHUTDOWN_TIMEOUT`.

## Kill switches

An atomically replaced immutable policy snapshot provides these independently observable controls, evaluated in this order:

1. `enabled=false`: all partner telemetry capture and export stops.
2. `partners.<partnerKey>.enabled=false`: the partner is dropped before record construction.
3. `apis.<apiId>.enabled=false`: that outbound or callback API produces no partner records.
4. `payloadCaptureEnabled=false`: any `FULL_SANITIZED` policy is reduced to `METADATA_ONLY`.
5. Per-integration switches disable RestTemplate, WebClient, OkHttp, MVC callback, WebFlux callback, explicit observation API, or safe-log capture. Callback switches can independently reduce request payload, response payload, and semantic processing events.
6. `exportEnabled=false`: queues are atomically drained to drop counters and new events are not admitted.

Defaults are global disabled until a service is onboarded, metadata-only for newly enabled APIs, and safe-log capture disabled. Runtime changes may be made through a management-network-only Actuator endpoint protected by the service's existing operator authentication; normal durable changes use configuration and ECS rollout. A runtime switch can only reduce capture. It cannot enable a field, partner, or API absent from the startup allowlist.

## Outbound HTTP client interception

All interceptors create request metadata immediately, call business transport exactly once, and contain their own errors. A configured mapping declares `SYNC` or `ASYNC_INITIATION`. Sync calls emit an outbound request and outbound response. Async calls emit an outbound request with `ASYNC_REQUEST_SENT` and exactly one `AsyncAcknowledgementRecord` terminal fact; they do not double-count the acknowledgement as a generic response. Response/acknowledgement metadata is recorded from headers/status and monotonic duration. Payload observation follows downstream consumption and never consumes or closes a stream on behalf of business code.

Every production-like mapping identifies an approved HTTPS endpoint. Manifest/CI checks reject HTTP and HTTPS-to-HTTP redirect policies; the starter neither rewrites nor blocks business requests because observability cannot become a behavior-changing policy enforcement point. Server certificate validation and hostname verification remain enabled in the service-owned client. A custom partner CA may only extend reviewed service-scoped trust and cannot enable trust-all, hostname bypass, expired certificates, or plaintext fallback.

### RestTemplate

- A `ClientHttpRequestInterceptor` uses configured `apiId` mappings and the current trusted context.
- It reuses the service-owned `ClientHttpRequestFactory` and never assigns an SSL context/socket factory, trust manager, hostname verifier, TLS strategy, connection manager, proxy, or redirect policy.
- Request bytes are considered only for textual allowlisted content types and within the raw candidate cap; otherwise payload is omitted.
- The response is wrapped with a tee input stream that copies at most the candidate cap while the application reads. It does not eagerly buffer the response or enable `BufferingClientHttpRequestFactory` globally.
- If the body is not consumed, only response metadata is emitted. Streaming and multipart endpoints are forced to metadata-only/no-payload.
- A configured async acknowledgement extractor may add partner/external references to the acknowledgement only after the application consumes a supported response or supplies the typed value explicitly.

### WebClient

- An `ExchangeFilterFunction` obtains context with `Mono.deferContextual`; Reactor context, not ThreadLocal, is authoritative.
- The filter reuses the service-owned connector/HTTP client and never replaces or configures its `SslProvider`, SSL context, trust manager, hostname verification, proxy, or redirect settings.
- Request and response `DataBuffer` publishers are decorated to inspect supported textual chunks as they pass. The collector has a strict byte cap and releases its own copies; it never aggregates an unlimited body or changes demand.
- Cancellation, error, empty, streaming, and multi-subscription behavior emits at most one response record using an atomic terminal guard.
- Payload capture is opt-in. Metadata-only remains the default because body decoration is more invasive.
- Reactor context owns the initiating identity; an async acknowledgement observed on another signal retains the immutable interaction snapshot rather than consulting the executing thread.

### OkHttp

- An application interceptor captures method, configured route/API, status, and duration.
- It never calls or changes `sslSocketFactory`, `hostnameVerifier`, `certificatePinner`, `connectionSpecs`, DNS, proxy, or redirect builder settings; it calls `chain.proceed` exactly once.
- It never calls `RequestBody.writeTo` merely for observability because bodies may be one-shot, duplex, encrypted, or side-effectful.
- A response source wrapper can tee already-consumed supported textual bytes up to the cap; it never calls `peekBody` above a limit.
- Full request plaintext requires the explicit API. Streaming/duplex bodies are metadata-only.
- Async acknowledgement extraction follows consumed response bytes or an explicit typed hook; `peekBody` and a second body subscription are prohibited.

Interceptor ordering is documented per service. When an explicit observation already owns payload capture, automatic interceptors emit metadata only and reuse the same `interactionId`, preventing duplicate payloads.

## Inbound callback/webhook interception

Callbacks are enabled only for manifest-listed route templates and a named server-owned authentication/context adapter. They are never captured by a generic inbound access-log switch. Transport interception supplies receipt/response facts; an explicit semantic API supplies authentication, validation, processing, retry/deduplication, and background-completion facts that HTTP status cannot prove.

The external callback request first reaches the host service's ALB HTTPS listener on 443. TLS terminates at the ALB/ACM trust boundary; port 80 is absent. The application trusts forwarded-protocol information only through its server-owned trusted-proxy configuration and private ALB-to-target security-group path, never from an arbitrary header. A successful TLS hop does not establish partner identity. TLS handshakes rejected at ALB remain internal-only and cannot create an expected-partner callback receipt.

The trust/capture order is fixed:

```text
transport ingress timestamp
  -> host authentication/signature verification
  -> trusted CallbackPartnerContextResolver (or stop; internal denial only)
  -> configured route/API and identifier policy
  -> callback request safe projection before controller/business processing
  -> explicit authentication/validation/processing events
  -> callback response safe projection after processing
  -> local response-write terminal fact
```

Authentication/signature failure, wrong-partner identity, or conflicting route/context never produces a partner record. The starter holds no unauthenticated payload while waiting for trust. It may retain the ingress timestamp and bounded transport flags as primitives; internal denial evidence contains only configured route ID and reason enum.

### Spring MVC callbacks

- A configured `OncePerRequestFilter` records monotonic timing and scopes the observation around async dispatch without changing status or exception behavior.
- A `HandlerInterceptor` resolves only a server-owned route template/API mapping after the host security chain; it never records a raw URI.
- `RequestBodyAdvice.afterBodyRead` receives an already-decoded registered DTO, creates the safe projection synchronously, and emits the callback request before controller invocation. A scoped exception observer recognizes configured `HttpMessageNotReadableException`/empty-body paths after trust exists and emits metadata-only `MALFORMED` plus the bounded parsing-failure fact without reading exception text or changing resolution.
- `ResponseBodyAdvice.beforeBodyWrite` creates the safe response projection after business handling and before serialization/encryption. The outer filter records final status and whether local write completion failed.
- A request/response wrapper may tee only supported textual bytes as business/framework code consumes them, up to the raw candidate cap. It cannot enable global content caching, reread the stream, interfere with a signature filter, or retain the servlet request/response.
- Servlet async processing installs an immutable context snapshot for each dispatched task and restores/clears it in `finally`. A `202` response ends the HTTP observation; later processing facts use the explicit callback scope.

### Spring WebFlux callbacks

- A post-authentication `WebFilter` writes the immutable context/observation to Reactor Context. Manifest-owned matching selects the API; a raw path does not select a tenant or policy.
- For explicitly enabled textual routes, decorated request `DataBuffer` publishers inspect bounded copies only as the configured decoder consumes them. Completion occurs before an annotated handler receives its decoded argument; failure/cancellation produces at most metadata.
- A decorated `ServerHttpResponse` observes supported response buffers after business processing and records one terminal fact using an atomic guard. It preserves demand, cancellation, buffer ownership/release, and error propagation.
- Full capture stays opt-in. Signature/decryption filters, functional routes, streaming bodies, or uncertain ordering use metadata-only automatic records plus the explicit typed callback API at the authorized plaintext boundary.
- Signal-scoped MDC is restored immediately. No singleton stores a callback context, body accumulator, or terminal flag shared across subscriptions.

## Context and MDC propagation

- `PartnerContext` is immutable. Servlet entry installs it in a scoped ThreadLocal and selected safe MDC keys, then restores the previous values in `finally`.
- `InheritableThreadLocal` is forbidden. An opt-in `TaskDecorator` captures a context snapshot for known Spring executors and always clears/restores it.
- Reactor stores the context under a library-owned key. WebClient and reactive server integrations use `deferContextual`; an MDC bridge scopes values to each signal and restores the previous MDC immediately.
- `CompletableFuture`, custom executors, callbacks, and messaging integrations use explicit `ContextSnapshot.wrap(Runnable/Callable)` utilities. Unknown executors do not inherit context automatically.
- Context is never cached in singleton interceptors or transport batches. Each safe event carries its routing context immutably, which prevents cross-partner batch leakage.
- A callback accepted before completion captures `PartnerContext`, `interactionId`, `callbackAttemptId`, and safe correlation identifiers in the explicit snapshot. It never captures request/response/domain objects, security principals, or mutable MDC maps. Snapshot restoration failure drops observability for the background fact and cannot fail business work.

MDC exposes only `originalCorrelationId`, `requestId`, `applicationId`, and `loanId` when each passes its configured identifier validator. A schema-1 compatibility adapter may also set the legacy `correlationId` key during a documented migration, but the two values cannot conflict. MDC never contains Loki tenant credentials, raw partner headers, PII, payloads, partner/callback references, or secrets. MDC is convenience for internal log correlation, not tenant authorization.

## Existing SLF4J/Logback logs

Arbitrary rendered logs cannot be made reliably partner-safe after formatting, so they remain internal-only and follow the service's normal ECS/CloudWatch route. They are never tailed wholesale into partner Loki.

The optional Logback compatibility bridge is disabled by default. Each startup-validated selection combines an exact logger or trailing package pattern, an exact unformatted SLF4J message template, a configured bounded category/journey stage, a minimum level, and optionally an exact marker. A logger/package or marker alone is never sufficient. Multiple exact statements may map to the same configured category.

Only explicitly indexed scalar arguments are projected under configured field/type/policy rules and passed through the first-stage sanitizer. The bridge never calls `getFormattedMessage()`, copies the raw template, reads MDC to select a tenant, invokes arbitrary argument `toString()`, or reads throwable proxies, exception messages, or stack traces. Unsupported, secret-shaped, binary/Base64, and oversized arguments are omitted under the normal fail-closed payload contract.

A selected record is emitted only inside a trusted `PartnerObservationContext` whose immutable partner context exactly matches the startup registry. Missing or foreign context drops the copy without a fallback tenant. The resulting `PartnerBusinessEventRecord` uses the existing bounded non-blocking dispatcher and no request/logging thread performs remote I/O.

The observer appender is attached alongside the root logger's existing appenders and does not replace appenders, change levels, filters, additivity, formatting, or the original event. Existing ECS/CloudWatch behavior therefore remains authoritative and unchanged when compatibility capture is disabled, drops, saturates, or publisher export fails. Non-additive loggers are not reconfigured merely to force capture. ADR 0012 defines the decision and migration rules.

## Encrypted integrations and explicit observation APIs

Instrumentation never decrypts data for observability and never captures ciphertext as a payload. If automatic interception occurs after encryption or before decryption, it records metadata only.

Applications that already possess authorized plaintext use a scoped API at the existing boundary:

```java
try (PartnerObservation observation = observations.begin(configuredApiName)) {
    observation.captureRequest(plaintextDomainObject); // sanitizes immediately
    byte[] encryptedReply = restTemplate.postForObject(
            uri, existingEncryption(plaintextDomainObject), byte[].class);
    PartnerReply reply = existingDecryption(encryptedReply);
    observation.captureResponse(reply);
    return reply;
}
```

`PartnerPlaintextSchema` beans define typed reflection-free extractors per configured API leg and can only narrow `safe-fields`; remove-only paths do not widen that disclosure allowlist. Schema source types reject binary arrays/buffers, streams/readers/files, throwables, keys, and cryptographic parameter objects, and discovery is bounded to three legs for each of the 64 configured APIs. The feature is disabled by default. Capture derives a bounded safe tree synchronously and never retains the DTO. Binary, documents, Base64, unsupported types, and oversize values are excluded under the payload policy. Automatic client interception reuses the scope for status/duration and ignores both ciphertext bodies; unsupported transports call only `succeed(status)` or `failed()`. Keys, IVs/nonces, credentials, ciphertext, exception text, and crypto details are never emitted. Hook failure is contained and safe projections use the shared bounded asynchronous dispatcher. See `encrypted-service-migration.md`.

Callback integrations use a parallel scoped API because automatic HTTP interception cannot determine semantic processing state:

```java
try (CallbackObservation callback = callbacks.beginAuthenticated(callbackApiId, trustedSubject)) {
    callback.identifiers(knownIdentifiers);
    callback.captureRequest(decryptedDto);       // safe projection before business processing
    callback.authenticated();
    callback.validated();                        // when this API has a validation phase
    try (CallbackProcessing processing = callback.processingStarted(BACKGROUND)) {
        existingBusinessProcessing();
        processing.processed();
    }
    callback.captureResponse(responseDto);       // after processing, before encryption/serialization
    callback.response(statusCode);
}
```

The actual API uses registered definitions/typed extractors rather than free-form stage names or attributes. `beginAuthenticated` accepts an opaque trusted subject resolved by the configured adapter, not a partner key. Retry/duplicate classification comes only from the host's authenticated idempotency result. Each semantic method is idempotent per observation and rejects invalid transitions to a bounded internal counter; it never throws into business logic. Automatic transport interception reuses the same IDs and suppresses duplicate request/response payloads.

## Metrics path

Micrometer records bounded counters, gauges, and timers in process. The starter includes the Spring Boot 2.7 Actuator and Prometheus Micrometer registry so the single starter dependency can expose the management-network endpoint when configured. The registry pre-registers manifest-defined legal series, rejects a calculated budget above 10,000 active Prometheus series, and distinguishes synchronous completions, async acknowledgement outcomes, outbound retries, timeout versus connection failure, callback receipt/retry/duplicate, callback processing terminal outcomes, and callback response writes without using transaction identifiers. Alloy discovers private `/actuator/prometheus` targets through configured AWS Cloud Map DNS names, scrapes at 30-second intervals, drops non-contract metrics/labels, stamps trusted market/environment/service labels, validates each `partner_slot` against the source service's configured finite set, and remote-writes to Prometheus with its receiver explicitly enabled. Metrics do not use the event queues; recording performs only bounded in-process meter updates and never waits for scrape or remote write.

The only approved partner dimension is `partner_slot`, an opaque onboarding value from `p001` to `p064`. It is derived from trusted context and enforced by Alloy relabeling. Raw partner IDs and every transaction identifier are prohibited metric labels. Details and SLI formulas are in `metrics-sli.md`.

LOCAL_SYNTHETIC Compose uses the same Alloy scrape/relabel/remote-write boundary with synthetic A/B/C slots, a five-second verification interval, private backend networking, Prometheus 16-day retention, and a 1 GB local size cap. Production replaces the fixed fixture target with Cloud Map discovery, restores the 30-second/10-second scrape contract, uses EFS-backed state and an environment-sized storage cap, and never exposes Prometheus directly. The optional async request-to-callback completion histogram remains unregistered because no reliable durable original-send timestamp adapter exists; the SDK does not create an in-memory transaction map.

## Tenant routing and query isolation

- Loki runs with multi-tenancy enabled and exactly one opaque tenant ID per `(market, environment, partner)`.
- The SDK sends a canonical partner routing key, not `X-Scope-OrgID`, to the private Alloy ingress.
- The Alloy ingress proxy authenticates a service credential and maps `(servicePrincipal, partnerKey)` to a fixed internal receiver/pipeline. Unknown or conflicting pairs are rejected; client tenant headers are stripped.
- Each generated Alloy partner pipeline overwrites routing attributes and uses a fixed Loki tenant value. It never derives tenant from event payload alone.
- Loki has no public endpoint. An authenticated reverse proxy strips incoming tenant headers and injects the mapped tenant on writes/queries.
- Multi-tenant Loki queries are disabled. Operator cross-tenant work uses separate audited internal credentials and one tenant at a time.

Grafana OSS uses one organization per partner. Each organization contains only provisioned, server-proxy datasources and dashboards for that partner. Local users are individual Viewer accounts in exactly one partner organization; partner users are never organization or server admins and Explore is disabled for the partner role where supported.

Each partner Loki datasource authenticates to the query gateway, which maps its credential to one fixed tenant. Each Prometheus datasource authenticates to an Nginx auth layer that injects a fixed `X-Partner-Slot`; a single `prom-label-proxy` parses every supported Prometheus query and enforces `partner_slot=<fixed>`. Grafana-supplied tenant/slot headers are stripped first. Direct backend network access is denied by security groups.

The query-gateway task also contains a stateless journey resolver. Datasource identity fixes the Loki tenant before the resolver validates a typed seed. A configured correlation profile groups compatible APIs and defines stable/weak/singleton identifier types; profiles are never merged. The resolver performs exact structured-metadata queries and bounded graph expansion: at most eight candidate profiles, three rounds per selected candidate, 32 typed identifiers, 500 records per round, the 16-day retained time range, a 2 MiB response, and a 10-second deadline. Weak-only edges cannot merge incompatible stable branches. It never accepts a tenant/slot from a browser request, never queries multiple tenants, never stores partner data, and returns explicit complete/partial/unresolved/weak/conflict status. Direct LogQL remains available only through the same tenant-fixed gateway and documented bounded endpoints.

Grafana organizations isolate OSS datasources from other organizations, but they do not replace backend authorization. The gateway controls are mandatory. Datasource permission features that require Grafana Enterprise are not assumed.

## Loki data model and partner experience

Indexed labels are fixed to `service_name`, `deployment_environment`, `market`, `event_domain`, `event_type`, `direction`, `outcome`, and `severity`, with a maximum of eight labels per stream. Tenant identity is not duplicated as a label. API name, status code, error code, product, and journey stage remain line fields or structured metadata to avoid multiplying streams.

`event_id`, `interaction_id`, `callback_attempt_id`, `application_id`, `loan_id`, `original_correlation_id`, `partner_reference_id`, `external_transaction_id`, `callback_reference_id`, and `request_id` are structured metadata, never labels. `timeline_stage` and `api_id` are also structured metadata/line fields. Loki uses TSDB index with schema v13 and structured metadata enabled. The safe JSON line contains bounded display fields and the sanitized payload projection.

Partner dashboards provide:

- Typed application/loan/reference search: a required time range and one of the seven identifier types calls the tenant-fixed journey resolver. Input is syntax/length validated but is never authorization.
- Journey timeline: outbound request, async acknowledgement, callback receipt/retry, authentication/validation, processing, callback response, and business events are grouped by HTTP interaction/callback attempt and sorted by real timestamps. Correlation status and missing stages are visible; concurrent/late events are not given a false total order.
- Detail view: selecting `eventId` shows record-specific request/acknowledgement/callback/processing metadata and sanitized JSON; omitted payloads show effective mode/status and safe omission metadata. Internal-only failures are never linked into a partner detail page.
- SLA/SLI dashboard: synchronous availability/latency, async acknowledgement acceptance/latency, callback volume/retries, processing success/latency, response-write outcome, error codes, freshness, and telemetry coverage from the partner-scoped Prometheus datasource. “No data” remains distinct from zero.

## Market deployment topology

There is one independent platform stack for each `(AWS account, market, environment, ECS cluster)` tuple:

- Production account: `<market>-PROD` cluster and PROD stack.
- Staging account: `<market>-STAGE` and `<market>-DEV` clusters and independent STAGE/DEV stacks.
- DEV integration services call mock partner services only.

Every stack is deployed into the same ECS cluster/VPC as its partner integration services but uses dedicated ECS services, task roles, security groups, Cloud Map names, storage prefixes, and secrets. No telemetry crosses market/environment boundaries.

Partner integration services remain in private subnets with no public IP. Their external callback ALBs are service-owned and expose only 443/HTTPS with ACM; target security groups accept only the ALB security group. Outbound partner calls leave through controlled NAT or an approved egress proxy/firewall and retain end-server certificate and hostname verification. The observability stack does not create or mutate partner-service ingress/egress TLS.

The initial cost-conscious topology is:

| ECS service | PROD desired count | DEV/STAGE | State |
| --- | ---: | ---: | --- |
| Alloy ingress proxy + Alloy | 2 | 1 | Stateless; generated config |
| Loki single-binary | 1 | 1 | S3 TSDB v13; encrypted EFS for WAL/cache/compactor work |
| Prometheus | 1 | 1 | Encrypted EFS TSDB, 16-day and size retention caps |
| Grafana | 1 | 1 | Encrypted EFS SQLite initially; local accounts |
| Query gateway + prom-label-proxy + journey resolver | 2 | 1 | Stateless; generated auth/query mapping, no partner-data store |

Single stateful tasks mean the initial design does not claim backend high availability. Task restart or upgrade can temporarily remove dashboards/ingestion without affecting business traffic. The migration trigger to Loki simple-scalable mode and an external Grafana database is sustained resource use above 70%, inability to meet the approved query SLO, or an approved backend HA requirement.

Loki objects use an encrypted S3 bucket per stack. Compactor retention is exactly `384h` (16 days), with a two-hour delete delay and an 18-day S3 lifecycle safety backstop. S3 versioning is disabled for telemetry objects so deleted payloads are not retained as noncurrent versions. Prometheus uses 16-day time retention plus a configurable size cap. Internal audit/config backups have a separate policy and bucket/prefix and are not partner telemetry.

## Configuration-driven onboarding

A versioned market manifest is the single non-secret source of truth. It contains market/environment, service principals and Cloud Map scrape names, partners, opaque tenant ID, `partner_slot`, Grafana organization, outbound and callback API IDs, interaction kind, approved HTTPS endpoint/host/port references, server-owned route templates, callback ALB/DNS ownership reference, callback trust-adapter ID, supported Spring stack, per-leg capture modes, field/path/type schemas, correlation profiles linking compatible APIs with the seven typed identifier validators/extractors and stable/weak/singleton rules, bounded outcome/stage mappings, rate/sample limits, local-user references, and secret/certificate ARNs. It never contains URI credentials, passwords, signature keys, private/client keys, trust-store passwords or bytes, tokens, or encryption material.

Validation enforces uniqueness, naming regexes, one tenant/slot/org per partner, no more than 64 partners, no more than 64 APIs per service, safe defaults, known record types/callback stages/capture modes/data classes, fixed retention, source-to-partner authorization, HTTPS for every deployed partner endpoint, no port-80 listener or downgrade redirect, a trust adapter for every enabled callback, and no full callback capture without a reviewed schema/order test. A reviewed manifest change generates Alloy/gateway/journey-resolver/Grafana artifacts and the application deployment inputs consumed after central infrastructure exists. Onboarding order is local synthetic HTTP only when explicitly isolated, then ECS DEV mocks over HTTPS, STAGE, and PROD; removal disables capture/query first, waits 16 days, then removes tenant configuration and credentials.

## Upgrade and compatibility strategy

- Pin container images by version and digest; scan before promotion.
- Promote the same artifact DEV -> STAGE -> PROD with recorded soak and rollback criteria.
- SDK follows semantic versioning and supports event schema N and N-1 in Alloy during rolling upgrades.
- Additive safe fields require policy/cardinality review. Renames/removals use dual-read/dual-write only for safe fields and a documented expiry.
- Loki schema changes are appended with a future UTC `from` date and are never edited in place or rolled back destructively.
- Stateless services use ECS rolling/blue-green updates with health checks. Stateful Loki, Prometheus, and Grafana updates use backups where applicable, one-step version upgrades, compatibility checks, and an explicit maintenance plan.
- Kill switches can revert capture to metadata-only/off independently of an SDK rollback.

## Auditability

Git history and ADRs record schema, policy, dashboard, manifest, and infrastructure-contract changes. Central Terraform review/apply evidence records infrastructure changes; application CI artifacts retain validation results and image/config digests. AWS CloudTrail covers infrastructure and secret access; ALB access logs and ECS/CloudWatch internal logs cover ingress and admin actions. Grafana provisioning is immutable to partner Viewers; server-admin and local-account actions use named operator identities and a ticket/approver reference. Partner telemetry is operational evidence, not an audit ledger.

Grafana OSS does not provide a complete tamper-proof audit/MFA solution. Production identity assurance and formal audit-retention requirements remain explicit questions in `decisions-needed.md`; they cannot be inferred from local accounts.

Callback authentication decisions, idempotency decisions, and business completion remain business/audit concerns. Partner telemetry records only bounded derived outcomes. Configuration changes to callback trust adapters, route maps, correlation validators, payload schemas, gateway credentials, tenant routes, or retention generate internal-only named-operator evidence and reviewed artifact digests.

## Failure-mode behavior

| Failure or edge condition | Application behavior | Partner telemetry / operator signal |
| --- | --- | --- |
| Alloy/Loki/Prometheus/Grafana/DNS unavailable | Business call/callback continues | Bounded queue then drops; internal fixed-dimension health only |
| Sanitizer/extractor/model exception | Original business value/exception is preserved | Payload/event omitted; bounded reason without raw input |
| Outbound TLS certificate/hostname/handshake failure | Original client failure is preserved; no HTTP fallback or SDK retry | Safe bounded TLS failure class when structured signal exists; no certificate/message/key material |
| Inbound callback TLS handshake failure at ALB | Request never reaches the callback application | Internal-only ALB aggregate/evidence; no partner tenant assignment |
| ACM renewal/listener certificate failure | Existing valid certificate remains or ingress becomes unavailable according to ALB behavior | Internal alarm and rollback; no private key reaches ECS or telemetry |
| Queue count/byte/rate saturation | Producer returns immediately | Drop-newest; exact bounded counter |
| Dispatcher death | No business-thread takeover | Alive gauge/alert; capped daemon restart or continued loss |
| Async original times out, callback later arrives | Callback business handling is unchanged | Timeout acknowledgement fact and later callback joined by bridge identifiers when available |
| Duplicate/retry callback | SDK never deduplicates business work | Separate attempt and retry stage only when trusted idempotency adapter says so |
| Callback arrives out of order | No buffering/waiting for an earlier event | Actual timestamps; resolver may show partial/unresolved until bridge appears |
| Unknown reference or missing application ID | Callback is not rejected by observability | Record other safe IDs; resolver returns unresolved/weak status |
| Wrong partner or authentication/signature failure | Host security policy decides business response | No partner record/fallback tenant; internal denial counter/evidence only |
| Callback parsing failure after authentication | Host error handling is unchanged | Metadata-only receipt plus bounded parsing failure stage |
| Callback processing failure | Business exception/status is unchanged | Separate started/failed facts and response observation if available |
| `202` before downstream completion | HTTP scope closes normally | Response precedes later background processing facts using explicit snapshot |
| Processing succeeds but response write fails | Success is not rewritten as processing failure | Successful processed fact plus response `WRITE_FAILED` |
| Correlation limit/collision | No business effect | Resolver returns partial/conflict; never broadens tenant/time/query bounds |
| Retention expires before callback/search | No retention extension | Older bridge is unavailable; unresolved result is explicit |
| Invalid startup manifest | Service application starts in safe disabled mode unless strict non-production validation requested | No capture/export; internal config health |

## Failure and cost posture

Backend outage, DNS/TLS/auth failure, malformed response, dispatcher death, queue saturation, invalid context/config, sanitizer error, and shutdown timeout all result in bounded telemetry loss and safe health signals. A supervisor may restart the dispatcher with capped backoff; it never runs work on business threads. Invalid startup policy prevents the observability beans from enabling capture but does not fail the Spring application context unless an operator explicitly selects strict non-production validation.

Cost is controlled through 16-day retention, S3 single-store Loki, single stateful tasks initially, bounded capture/rate/sample limits, eight labels, partner/API cardinality caps, compressed asynchronous batches, no traces, no raw logs, no binaries, and scale triggers based on measured saturation rather than speculative HA. Journey resolution is stateless and query-time bounded, avoiding a new correlation database, stream processor, or application write. Security boundaries are not relaxed to save cost.

## Verification and rollout

Testing is layered across unit/property/fuzz, framework contract, concurrency, Docker Compose, tenant security, dashboard/query, enterprise-infrastructure contract validation, failure injection, and performance suites. A supplemental target-service layer can validate exactly one selected real service against the same local platform without replacing controlled generic fixtures. The centralized Terraform repository owns plan/policy evidence for AWS resources. Async/callback suites include late/out-of-order callbacks, duplicate/retry attempts, acknowledgement bridges, missing/unknown/conflicting IDs, wrong partners, auth/signature/parsing/processing/write failures, accepted-before-complete, MVC async dispatch, WebFlux cancellation/backpressure, and bounded correlation queries. Transport suites use synthetic certificates to cover valid/untrusted/expired/hostname-mismatch chains, HTTPS-to-HTTP downgrade denial, unchanged RestTemplate/WebClient/OkHttp TLS settings, ALB 443-only/ACM/private-target policy, spoofed forwarding headers, secret absence, and local-HTTP isolation. Exact gates are in `acceptance-criteria.md`.

Existing partner services roll out in phases: inventory outbound and callback routes/authentication/encryption/idempotency/completion semantics; deploy empty backends; add starter disabled; enable health metrics; enable metadata-only for one DEV mock synchronous API; enable one mock async acknowledgement/callback journey; validate tenant/correlation/timeline/dashboards; enable explicit plaintext/processing hooks where needed; approve full-sanitized fields per leg; expand partner-by-partner; retain kill-switch and rollback evidence. Callback capture remains disabled until its trusted resolver and filter/decryption ordering are tested. No phase enables production payload capture without security and service-owner approval.

## Requirement-to-design map

This map is a completeness index; the linked contracts are normative and contain the implementation detail.

| # | Required design | Normative location |
| ---: | --- | --- |
| 1 | Core telemetry object model | `telemetry-contract.md` core/envelope model |
| 2 | Partner context model | `telemetry-contract.md`; `partner-isolation.md` trust chain |
| 3 | Outbound API request | `telemetry-contract.md` outbound request |
| 4 | Outbound API response | `telemetry-contract.md` outbound response |
| 5 | Async acknowledgement | `telemetry-contract.md` acknowledgement |
| 6 | Callback/webhook request | `telemetry-contract.md`; MVC/WebFlux sections above |
| 7 | Callback/webhook response | `telemetry-contract.md`; MVC/WebFlux sections above |
| 8 | Callback processing result/event | `telemetry-contract.md` processing event and edge cases |
| 9 | Partner business event | `telemetry-contract.md` business event |
| 10 | SLI metric model | `metrics-sli.md` |
| 11 | Async queue/dispatcher | bounded asynchronous mechanism above; ADR 0002 |
| 12 | Backpressure/drop policy | bounded mechanism and failure-mode table |
| 13 | Kill switches | kill-switch section above |
| 14 | RestTemplate | outbound interception above |
| 15 | WebClient | outbound interception above |
| 16 | OkHttp | outbound interception above |
| 17 | Spring MVC callback interception | inbound callback section; ADR 0010 |
| 18 | Spring WebFlux callback interception | inbound callback section; ADR 0010 |
| 19 | MDC/context propagation | context/MDC section above |
| 20 | Existing SLF4J capture | safe-log section above |
| 21 | Async/reactive context | context/MDC and WebFlux sections |
| 22 | Full sanitized capture | `payload-policy.md` and capture lifecycle |
| 23 | Metadata-only capture | `telemetry-contract.md` capture modes |
| 24 | No-payload capture | `telemetry-contract.md` capture modes |
| 25 | Pre-encryption capture | explicit API section; `payload-policy.md` |
| 26 | Post-decryption capture | explicit API section; `payload-policy.md` |
| 27 | Callback capture before processing | inbound interception and ADR 0010 |
| 28 | Callback response after processing | inbound interception and ADR 0010 |
| 29 | Explicit API for invisible plaintext/semantics | explicit APIs section above |
| 30 | Pre-queue binary/Base64 exclusion | `payload-policy.md`; `security-invariants.md` |
| 31 | Payload limits | `payload-policy.md` hard-limit table |
| 32 | First-stage sanitization | `payload-policy.md` application stage |
| 33 | Second-stage Alloy sanitization | `payload-policy.md` Alloy stage |
| 34 | One Loki tenant per partner | `partner-isolation.md` |
| 35 | Tenant routing/trust boundary | `partner-isolation.md`; ADR 0004 |
| 36 | Loki labels | Loki model above; ADR 0005 |
| 37 | Loki structured metadata | `telemetry-contract.md` wire/Loki contract |
| 38 | Prometheus/Micrometer | `metrics-sli.md` |
| 39 | Local Grafana accounts | `partner-isolation.md` |
| 40 | Grafana authorization | `partner-isolation.md` |
| 41 | Partner datasource isolation | `partner-isolation.md`; tenant/query section above |
| 42 | Application/loan search | Loki experience and query-resolver sections |
| 43 | Outbound-to-callback correlation | `telemetry-contract.md` deterministic correlation; ADR 0009 |
| 44 | Journey/event timeline | Loki experience; ADR 0009 |
| 45 | Request/response/callback detail | Loki experience above |
| 46 | SLA/SLI dashboard | `metrics-sli.md`; Loki experience above |
| 47 | S3/Loki retention | deployment topology; `deployment-model.md` |
| 48 | ECS topology | market deployment topology; `deployment-model.md` |
| 49 | Enterprise Terraform ownership and requirements boundary | `deployment-model.md`; `enterprise-infrastructure/` |
| 50 | Upgrade strategy | upgrade/compatibility section above |
| 51 | Configuration-driven onboarding | onboarding section above; ADR 0008 |
| 52 | Auditability | audit section above; `threat-model.md` |
| 53 | SDK self-monitoring | `metrics-sli.md` SDK health metrics |
| 54 | Failure modes | failure-mode table above |
| 55 | Threat model | `threat-model.md` |
| 56 | Cost-conscious design | failure/cost posture; `deployment-model.md` |
| 57 | Testing strategy | `acceptance-criteria.md` |
| 58 | Performance strategy | `acceptance-criteria.md` performance gates |
| 59 | Existing-service rollout/migration | verification/rollout above; ADR 0008 |

### Transport-security requirement map

| # | Required transport design | Normative location |
| ---: | --- | --- |
| 1 | RestTemplate outbound HTTPS | outbound interception above; `transport-security.md` |
| 2 | WebClient outbound HTTPS | outbound interception above; `transport-security.md` |
| 3 | OkHttp outbound HTTPS | outbound interception above; `transport-security.md` |
| 4 | Callback/webhook inbound HTTPS | inbound callback section; `transport-security.md` |
| 5 | ALB HTTPS listener | external HTTPS section; `deployment-model.md` |
| 6 | ACM attachment and rotation | `transport-security.md`; ADR 0011 |
| 7 | TLS termination trust boundary | external HTTPS section; ADR 0011 |
| 8 | Private ECS/security groups | `deployment-model.md`; `transport-security.md` |
| 9 | Server certificate validation | `transport-security.md` outbound requirements |
| 10 | Hostname verification | `transport-security.md` outbound requirements |
| 11 | Custom CA trust stores | `transport-security.md` custom trust section |
| 12 | Certificate-validation failure handling | `transport-security.md` failure classification |
| 13 | Downgrade/fallback prevention | `transport-security.md` outbound requirements |
| 14 | Port-80 decision | ADR 0011: absent, not redirect-only |
| 15 | TLS ownership | `transport-security.md` ownership table |
| 16 | No SDK TLS mutation | ADR 0011; client-specific sections above |
| 17 | Certificate/key/trust-store secret protection | `payload-policy.md`; `transport-security.md` |
| 18 | Future mTLS extensibility | `transport-security.md`; ADR 0011 |
| 19 | Safe TLS failure metadata | `telemetry-contract.md`; `transport-security.md` |

## Scoped architecture review verdict

| Gate | Status | Evidence |
| --- | --- | --- |
| Data-class and pre-queue safety boundary | PASS | Three data classes, capture lifecycle, `payload-policy.md`, ADR 0001 |
| Business-thread availability and boundedness | PASS | Queue/dispatcher/kill-switch/failure sections, ADR 0002 |
| Module/dependency direction and one-starter integration | PASS | Repository responsibilities and Spring integration contracts |
| HTTPS-only partner transport, validation ownership, ALB/ACM/private-task boundary, and no SDK TLS mutation | PASS | External HTTPS section, `transport-security.md`, ADR 0011 |
| Sync plus first-class async/callback semantics | PASS | Schema-2 contract, inbound/outbound interception, ADRs 0009-0010 |
| Trusted identity, one-tenant routing, datasource and correlation isolation | PASS | `partner-isolation.md`, ADRs 0004-0005/0009 |
| Low-cardinality logs/metrics and partner dashboards | PASS | Loki model, `metrics-sli.md`, query/dashboard contracts |
| ECS/central-Terraform contract/retention/no-Helm deployment design | PASS | Market topology, `deployment-model.md`, ADRs 0007/0013, `enterprise-infrastructure/` |
| Threat, failure, test, performance, rollout, and unresolved-input treatment | PASS | `threat-model.md`, `acceptance-criteria.md`, `decisions-needed.md`, ADR 0008 |
| Schema-2 core and scoped Spring runtime | IMPLEMENTED / separately verified | Seven schema-2 record types, immutable correlation, bounded dispatcher, three outbound adapters, configured callback transport/semantic API, synthetic integration tests |
| Runtime implementation outside the M1 verdict | NOT APPLICABLE to this architecture verdict | Local Alloy/Loki M5 and Prometheus/Micrometer M6 are separately implemented and verified; Grafana query authorization, deployment, and full-performance work remain pending M7-M9 |

Overall verdict: **PASS for the M1 architecture/specification scope**. This is not a runtime, security-integration, release, or production-readiness verdict.
