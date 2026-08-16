# ADR 0002: Telemetry model and bounded dispatch

- Status: Accepted for M2 implementation
- Date: 2026-08-16
- Decision owners: SDK architecture

## Context

Instrumentation must model requests, responses, journey events, and SLI observations while preserving business availability under backend slowness, high volume, and large inputs. Standard blocking queues/executors and unbounded retry can couple resource use to telemetry.

## Decision

Use immutable Java 17 `TelemetryEnvelope` records containing one sealed `ApiRequestRecord`, `ApiResponseRecord`, or `PartnerEventRecord`. Safe values have no binary/arbitrary-object subtype. SLI observations update pre-registered Micrometer meters and are not queued records.

Use bounded non-blocking MPSC array queues with independent count and byte budgets: high 256/4 MiB and normal 1,024/16 MiB by default. Producers reserve bytes then call `offer` once. Saturation drops newest. A single daemon dispatcher drains one high then up to three normal batches, maximum 128 events/256 KiB, flushes at 200 ms, and performs all network work. Connect/request timeouts are 250 ms/1 second. One bounded batch may be retried once after jitter; shutdown drains for at most two seconds.

Use preallocated per-partner and global token buckets, deterministic sampling, a hard 64-partner market cap, and bounded reason enums. The dispatcher partitions each bounded mixed drain into single-partner sub-batches without increasing the batch byte cap. Transport is OTLP/HTTP logs over private TLS to Alloy and is best-effort/at-most-once with possible retry duplicate identified by `eventId`.

## Security and availability consequences

- Queue memory, retry, batch, traversal, and partner rate state have calculable maxima.
- Business threads do no backend I/O, sleep, retry, eviction, or blocking queue operation.
- Priority preserves more error/journey telemetry but never guarantees delivery.
- Backend outage deliberately loses telemetry and can produce duplicate retry events.

## Alternatives considered

- `ArrayBlockingQueue`: rejected because producer `offer` still contends on an internal lock.
- Unbounded executor/queue: prohibited.
- Disk spool/WAL in application: rejected for I/O coupling, sensitive residual data, shutdown complexity, and false delivery expectations.
- Infinite/exponential retry: rejected because it amplifies outage pressure.
- One queue: simpler, but sustained success traffic could starve bounded error/journey visibility.

## Implementation and migration

Core provides queue/byte accounting/dispatcher/transport SPI. Autoconfiguration owns lifecycle. Services can lower limits; hard maxima cannot be raised by configuration. SDK schema N/N-1 coexist at Alloy during rolling upgrades.

## Verification evidence required

Concurrency/linearizability-style accounting tests, queue/byte saturation, dispatcher blackhole, fairness, shutdown, retry duplicate, exact drop reason, memory plateau, and business response invariance under M9 performance profiles.

## References and supersession

Normative details: `../architecture.md`, `../telemetry-contract.md`, `../acceptance-criteria.md`. No ADR is superseded.
