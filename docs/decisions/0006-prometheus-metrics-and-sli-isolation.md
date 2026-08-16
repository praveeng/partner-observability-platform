# ADR 0006: Prometheus metrics and SLI isolation

- Status: Accepted for M6/M7 implementation
- Date: 2026-08-16
- Decision owners: Metrics and security architecture

## Context

Partner SLA/SLI views require a partner dimension, but raw partner IDs and transaction identifiers would create disclosure/cardinality risk. A shared Prometheus does not natively authorize tenants, and metric network I/O cannot occur on business threads.

## Decision

Record pre-registered Micrometer counters/timers/gauges in process. The only partner dimension is manifest-generated opaque `partner_slot` (`p001`-`p064`). APIs/event names/outcomes are bounded registries. The manifest cardinality calculator rejects >10,000 active SDK series per application or >100,000 initial market series.

Alloy discovers private Actuator endpoints through configured Cloud Map DNS, scrapes every 30 seconds, overwrites trusted deployment labels with `honor_labels=false`, validates slots against the source-service allowlist, drops non-contract data, and remote-writes to a private Prometheus receiver. Business metric updates never wait for scrape/write.

Partner Grafana Prometheus datasources authenticate to the query gateway. It maps the credential to one fixed slot and supplies that value to `prom-label-proxy`, which enforces the label on every supported parsed query and rejects conflict/unsupported endpoints.

Use contract metrics/formulas/buckets in `metrics-sli.md`. Contractual SLA percentages/exclusions remain onboarding business inputs, not architecture guesses.

## Security and availability consequences

- Partner visibility is available with a bounded approved mapping rather than raw identity.
- Scrape/backend gaps affect dashboards only; no application retry or request failure.
- Query proxy is a mandatory security component and must be version-pinned/tested.
- Shared Prometheus remains a larger internal blast radius than physical per-partner stores; server-side label enforcement mitigates read isolation.

## Alternatives considered

- No partner metric dimension: cannot produce partner SLI dashboards.
- Raw partner ID: rejected by disclosure/cardinality contract.
- One Prometheus per partner: rejected initially for cost and operational multiplication.
- Dashboard variable/matcher only: rejected because a Viewer can issue arbitrary queries.
- Grafana Enterprise LBAC/Mimir: viable future options but not assumed by required OSS/cost baseline.

## Implementation and migration

M2/M3 register fixed meters. M6 adds Alloy scrape/relabel/remote-write, rules, and cardinality validation. M7 provisions slot-fixed datasources. Moving to another metrics backend requires preserving Prometheus query/API and isolation tests or a new ADR.

## Verification evidence required

Meter-series calculation, malicious target label overwrite, remote-write outage, scrape discovery, PromQL/query/metadata proxy bypass, partner dashboard formula, and no-data/freshness tests.

## References and supersession

- [Alloy Prometheus scrape](https://grafana.com/docs/alloy/latest/reference/components/prometheus/prometheus.scrape/)
- [Prometheus remote-write receiver](https://prometheus.io/docs/prometheus/latest/command-line/prometheus/)
- [prom-label-proxy](https://github.com/prometheus-community/prom-label-proxy)

Normative details: `../metrics-sli.md`. No ADR is superseded.
