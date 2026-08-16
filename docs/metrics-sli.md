# Metrics and SLI Contract

## Principles

Metrics describe observability health without exposing payloads or creating unbounded series. Labels must come from reviewed bounded enumerations. Partner IDs, transaction IDs, request IDs, application IDs, loan IDs, exception messages, URLs with identifiers, and arbitrary configuration values are prohibited metric labels.

## Candidate metric families

Names remain provisional until M1/M6 review:

| Signal | Intent | Allowed dimensions |
| --- | --- | --- |
| telemetry events attempted | SDK demand | event type, bounded outcome |
| telemetry events enqueued | Queue acceptance | event type |
| telemetry events dropped | Saturation/policy loss | bounded reason |
| queue utilization | Capacity pressure | queue role |
| sanitization decisions | Safety visibility | bounded action/data class; never field value |
| export batches/events | Export health | bounded result/backend |
| export latency | Async exporter health | backend/result |
| backend scrape/ingest health | Platform health | component/instance under deployment policy |

Metric names, bucket boundaries, and label budgets require an ADR or approved contract update before implementation.

## Candidate SLIs

- Business-path isolation: no business failure or synchronous backend wait attributable to observability.
- Queue acceptance ratio: accepted safe events divided by attempted safe events.
- Telemetry drop ratio by bounded reason.
- Asynchronous export success and latency.
- Partner routing correctness and cross-tenant denial rate in synthetic tests.
- Prohibited disclosure count: target exactly zero.

M1 must define measurement windows and objectives; M9 must define reproducible load profiles and thresholds. Telemetry loss is acceptable under saturation, but it must be visible through bounded local/platform metrics without risking business availability.
