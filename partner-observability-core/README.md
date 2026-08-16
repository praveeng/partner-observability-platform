# Partner Observability Core

Framework-neutral Java 17 primitives for producing already-safe telemetry and handing it to a bounded asynchronous publisher. This module has no Spring, HTTP-client, Loki, Grafana, Prometheus, AWS, ECS, or Terraform dependency.

## Packages

- `context`: immutable, authenticated server-derived `PartnerContext` and resolver base class.
- `model`: immutable request, response, partner-event, envelope, and high-cardinality transaction identifier models.
- `payload`: reviewed path schemas, fail-closed sanitizer, safe closed-tree representation, capture results, and content-free binary omission metadata.
- `policy`: payload capture modes and monotonic runtime kill switches for all capture, payloads, logs, events, metrics, and export.
- `dispatch`: fixed event/byte-capacity MPSC queues, non-blocking drop-newest admission, priority dispatch, one bounded retry slot, and bounded shutdown.
- `publish`: single-partner batch and publisher SPI; backend adapters belong in later modules.
- `health`: fixed-dimension counters, queue gauges, and lifecycle state.
- `time`: wall-clock and monotonic-time abstraction for deterministic tests.

## Safety boundary

Only a `TelemetrySubmission` containing an immutable `TelemetryEnvelope` can enter the dispatcher. Payload candidates must first pass `FailClosedPayloadSanitizer`; rejected sanitizer results cannot be used to construct request, response, or event records. The safe-tree type has no binary, arbitrary-object, throwable, or raw-text fallback subtype.

Producer calls use non-blocking queue `offer` operations only. Full event or byte budgets drop the newest telemetry and increment a bounded-dimension counter. Publishing and its single retry happen only on the daemon dispatcher thread. Publisher failures, sanitizer/record construction failures, disabled capture, saturation, and shutdown timeout do not propagate to business callers.

## Verification

Run the module suite from the repository root:

```bash
./gradlew :partner-observability-core:test
```

The suite uses synthetic values and covers the mandatory credential/PII/binary corpus, structural limits, unknown/malformed/encrypted input, independent kill switches, server-owned context, colliding identifiers across partners, queue event and byte exhaustion, publisher recovery/failure, multi-producer concurrency, partner-pure batches, and bounded shutdown.

Spring Boot auto-configuration, client interceptors, wire encoding, Alloy/Loki defense in depth, Grafana authorization, and full-duration performance verification are intentionally later milestones.
