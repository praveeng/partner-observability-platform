# Spring Boot auto-configuration

Java 17 / Spring Boot 2.7 auto-configuration for the framework-neutral core. It is activated only by `partner-observability.enabled=true`; the disabled path creates no dispatcher or HTTP instrumentation beans.

Implemented integrations:

- existing `RestTemplate` beans receive exactly one interceptor. JSON request bytes already materialized by Spring may be sanitized; response bytes are teed only while the application consumes them and are never eagerly read;
- existing `WebClient` beans receive a subscription-scoped filter with Reactor Context propagation. Automatic payload capture is metadata-only because the filter cannot safely serialize a body inserter or buffer response publishers;
- existing OkHttp clients are rebuilt with one application interceptor. It never invokes `RequestBody.writeTo` and records metadata only;
- configured Spring MVC callbacks use a post-authentication filter, trusted partner resolver, scoped MDC/context, distinct request and response records, and an explicit `CallbackObservation` for authentication, validation, retry/duplicate, processing, background completion, and response-write facts;
- configured WebFlux callbacks have a metadata-only, Reactor-context filter when a reactive trusted resolver is available. It does not decorate or buffer `DataBuffer` streams;
- Micrometer hooks pre-register configured API IDs, fixed outcome/status/result enums, and opaque partner slots only; the 10,000-series startup budget fails closed. They cover outbound success/4xx/5xx/timeout/connection/retry/latency, async acknowledgements, and callback delivery/processing/response/retry/duplicate health. The Actuator health contributor reports bounded dispatcher state and never backend readiness or secret/config values.
- optional selected-log compatibility observes exact startup-approved SLF4J templates from approved logger/package patterns, optionally requires a marker, projects only configured scalar arguments, and publishes a category event through the shared bounded dispatcher.

Secure defaults are disabled globally, metadata-only per API, payload capture off, exact configured routes only, and no trusted callback context fallback. A standard authenticated principal can be mapped with `authenticated-principal`; custom host authentication/signature systems provide `CallbackPartnerKeyResolver` or `ReactiveCallbackPartnerKeyResolver`. Those adapters must consume server-owned verified state, never a route/header/body partner claim.

`CorrelationIdentifiersExtractor` is the typed decoded-object extension point for application/loan/original-correlation/partner-reference/external-transaction/callback-reference/request IDs. Identifiers are persisted on each immutable record and do not create an SDK pending-transaction map.

Unsupported, streaming, one-shot, encrypted, binary, Base64, malformed, or oversized bodies reduce to metadata/omission status. Observability construction, queue saturation, publisher failure, callback denial, and shutdown failures are contained from business behavior.

Metrics are in-process Micrometer updates and are scraped from private `/actuator/prometheus`; the SDK never makes a synchronous metrics network call. Alloy overwrites deployment labels and removes non-contract labels before remote write. The metric schema never accepts application, loan, correlation, request, partner-reference, callback-reference, or other per-transaction identifiers.

## Selected existing logs

Log compatibility is off unless both global observability and `logs-enabled` are true. Enabling it without a selection fails startup. Every selection requires an exact unformatted message template and an exact logger name or trailing `.*`/`.**` package pattern; an optional marker narrows it further. A package pattern alone never copies logs.

```yaml
partner-observability:
  enabled: true
  payloads-enabled: true
  logs-enabled: true
  service-name: credit-service
  service-version: 1.0.0
  market: uk
  environment: DEV
  partners:
    - key: PARTNER_A
      tenant-route-id: opaque-tenant-a
      slot: p001
  log-selections:
    - category: PARTNER_SUBMISSION_ACCEPTED
      logger-pattern: com.example.partner.service.**
      message-template: 'Submission accepted for operation {}'
      minimum-level: INFO
      marker: PARTNER_SAFE # optional; omit only after explicit review
      journey-stage: SUBMISSION_ACCEPTED
      outcome: SUCCESS
      arguments:
        - index: 0
          name: operation
          type: STRING
          policy: ALLOW
```

The log statement still follows its original SLF4J/Logback appenders and formatting. The observer does not mutate logger levels, filters, additivity, appenders, or the event, so ECS/CloudWatch remains unchanged if compatibility capture is disabled, drops, saturates, or export fails.

The partner-safe copy is not the rendered log. It contains only the configured category/journey/outcome and sanitizer-approved configured arguments. The bridge never copies the raw template, calls `getFormattedMessage()`, reads MDC as partner authority, invokes arbitrary `toString()`, or reads exception messages, throwable proxies, or stack traces. Authorization/credentials/card/OTP are removed; binary/Base64 and oversized values are omitted; unsupported values fail closed.

A selection emits only while a trusted `PartnerObservationContext` is active and exactly matches one startup-configured partner. Missing or foreign context drops the entire copy. Callback instrumentation establishes that context automatically after trusted resolution. Other application flows must already use the library's trusted scoped observation boundary; a client field, MDC value, marker, logger name, or argument cannot establish tenant identity.

Compatibility events use normal priority and `TelemetryChannel.LOG` on the same bounded asynchronous dispatcher as other telemetry. Log/application threads perform no remote I/O. Non-additive loggers are not reconfigured by the bridge; keep the selected event on the normal root-appender path or use the explicit structured event API.

Run:

```bash
./gradlew :partner-observability-spring-boot-autoconfigure:test
```
