# ADR 0005: Loki schema, search, and retention

- Status: Accepted for M5 implementation
- Date: 2026-08-16
- Decision owners: Logging platform architecture

## Context

Partners need transaction search and detail/timelines, while identifiers are high cardinality and cannot be labels. Loki must persist in S3 for exactly 16 days and support independent partner tenants.

## Decision

Use Loki multi-tenant mode, TSDB index, schema v13, S3 object storage, and structured metadata. Each event stream has at most eight fixed labels: service name, deployment environment, market, event domain, event type, direction, outcome, and severity. Tenant is not a label.

Store event/interaction/application/loan/correlation/request/partner-reference IDs as validated structured metadata, along with bounded API/status/error/product metadata. Store the display envelope and sanitized payload in a maximum-64-KiB JSON line. Identifier searches require tenant-fixed datasource, time range, and low-cardinality stream selector.

Enable compactor retention at 384 hours with two-hour delete delay. Use an 18-day S3 lifecycle backstop, disable telemetry-object versioning, and alert on compactor failure. Initial ECS deployment is single-binary with S3 plus encrypted EFS WAL/cache/work; migration to simple-scalable mode is threshold/availability driven.

## Security and availability consequences

- Identifier search may scan more data and be slower than indexed labels, intentionally protecting index cardinality.
- Per-tenant header routing and S3 partitions isolate data logically; proxy/network/IAM remain required authentication controls.
- Backend restart can cause temporary ingest/query downtime; S3 retains committed data and business traffic is unaffected.
- S3 backstop provides a deletion ceiling but compactor health is necessary for precise logical retention.

## Alternatives considered

- Identifier labels: prohibited due unbounded streams/index cost.
- Put IDs only in line text: searchable but less structured; structured metadata is designed for high-cardinality fields.
- Separate Loki deployment/bucket per partner: stronger physical isolation but high cost/operational burden for the initial cap.
- S3 versioning: rejected because noncurrent telemetry would outlive deletion intent.
- Longer/default retention: rejected; requirement is 16 days.

## Implementation and migration

Alloy schema pipelines set labels/metadata. Loki config pins v13/TSDB and retention. Schema changes append future UTC entries; never edit historical entries. Dashboard searches constrain time/stream before metadata filter. Tenant IDs are tombstoned, never reassigned.

## Verification evidence required

Label/cardinality contract tests, structured-metadata search by each identifier, cross-tenant denial, timeline/detail correctness, 64-KiB rejection, compactor metrics, and aged synthetic data deletion through S3.

## References and supersession

- [Loki structured metadata](https://grafana.com/docs/loki/latest/get-started/labels/structured-metadata/)
- [Recommended TSDB v13 S3 schema](https://grafana.com/docs/loki/latest/operations/storage/schema/)
- [Loki configuration and compactor retention](https://grafana.com/docs/loki/latest/configure/)

Normative details: `../telemetry-contract.md`, `../deployment-model.md`. No ADR is superseded.
