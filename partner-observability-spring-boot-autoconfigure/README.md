# Spring Boot auto-configuration

Java 17 / Spring Boot 2.7 auto-configuration for the framework-neutral core. It is activated only by `partner-observability.enabled=true`; the disabled path creates no dispatcher or HTTP instrumentation beans.

Implemented integrations:

- existing `RestTemplate` beans receive exactly one interceptor. JSON request bytes already materialized by Spring may be sanitized; response bytes are teed only while the application consumes them and are never eagerly read;
- existing `WebClient` beans receive a subscription-scoped filter with Reactor Context propagation. Automatic payload capture is metadata-only because the filter cannot safely serialize a body inserter or buffer response publishers;
- existing OkHttp clients are rebuilt with one application interceptor. It never invokes `RequestBody.writeTo` and records metadata only;
- configured Spring MVC callbacks use a post-authentication filter, trusted partner resolver, scoped MDC/context, distinct request and response records, and an explicit `CallbackObservation` for authentication, validation, retry/duplicate, processing, background completion, and response-write facts;
- configured WebFlux callbacks have a metadata-only, Reactor-context filter when a reactive trusted resolver is available. It does not decorate or buffer `DataBuffer` streams;
- Micrometer hooks pre-register configured API IDs, fixed outcome/status/result enums, and opaque partner slots only; the 10,000-series startup budget fails closed. They cover outbound success/4xx/5xx/timeout/connection/retry/latency, async acknowledgements, and callback delivery/processing/response/retry/duplicate health. The Actuator health contributor reports bounded dispatcher state and never backend readiness or secret/config values.

Secure defaults are disabled globally, metadata-only per API, payload capture off, exact configured routes only, and no trusted callback context fallback. A standard authenticated principal can be mapped with `authenticated-principal`; custom host authentication/signature systems provide `CallbackPartnerKeyResolver` or `ReactiveCallbackPartnerKeyResolver`. Those adapters must consume server-owned verified state, never a route/header/body partner claim.

`CorrelationIdentifiersExtractor` is the typed decoded-object extension point for application/loan/original-correlation/partner-reference/external-transaction/callback-reference/request IDs. Identifiers are persisted on each immutable record and do not create an SDK pending-transaction map.

Unsupported, streaming, one-shot, encrypted, binary, Base64, malformed, or oversized bodies reduce to metadata/omission status. Observability construction, queue saturation, publisher failure, callback denial, and shutdown failures are contained from business behavior.

Metrics are in-process Micrometer updates and are scraped from private `/actuator/prometheus`; the SDK never makes a synchronous metrics network call. Alloy overwrites deployment labels and removes non-contract labels before remote write. The metric schema never accepts application, loan, correlation, request, partner-reference, callback-reference, or other per-transaction identifiers.

Run:

```bash
./gradlew :partner-observability-spring-boot-autoconfigure:test
```
