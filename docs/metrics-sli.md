# Metrics and SLI Contract

## Scope

Micrometer is the application instrumentation API. Prometheus is the market-local metric store. Alloy scrapes private application Actuator endpoints and remote-writes a reviewed subset to Prometheus. Partner users query only through a server-side label-enforcing proxy.

Metrics never contain payloads, raw partner identifiers, user/customer identifiers, transaction IDs, application/loan/correlation/request IDs, partner references, raw URLs, exception classes/messages, arbitrary field names, or unbounded configuration values.

## Trusted dimensions

All label values must come from startup configuration or bounded enums:

| Label | Bound |
| --- | --- |
| `market` | One value per deployment, stamped by Alloy |
| `environment` | One of `DEV`, `STAGE`, `PROD`, stamped by Alloy |
| `service` | At most 32 configured services per market stack |
| `partner_slot` | `p001`-`p064`; opaque mapping, at most 64 |
| `api` | At most 64 configured API IDs per service |
| `event_name` | At most 64 configured event names per service |
| `direction` | `inbound`, `outbound` |
| `outcome` | `success`, `business_rejected`, `technical_failure`, `cancelled`, `unknown` |
| `status_class` | `1xx`, `2xx`, `3xx`, `4xx`, `5xx`, `io_error`, `cancelled`, `unknown` |
| `capture_mode` | `full_sanitized`, `metadata_only`, `none` |
| `reason` | Contract enum; never raw text |
| `queue` | `high`, `normal`, `retry` |
| `record_type` | `api_request`, `api_response`, `partner_event` |
| `result` | Metric-specific documented enum; never raw backend text |
| `action` / `data_class` | Sanitizer enums defined by payload policy |
| `version` | Exactly one active manifest-generated policy version per service |

The meter registry pre-registers only manifest-defined partner/API combinations. Unknown runtime values map to `unknown` only for an existing meter and do not create a new tag value. Alloy overwrites market/environment/service labels, validates `partner_slot` against the configured source-service set, and drops metrics/labels outside this contract. `honor_labels` is false.

## Application SLI metrics

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `partner_observability_api_requests_total` | Counter | market, environment, service, partner_slot, api, direction, outcome, status_class | Completed eligible interactions |
| `partner_observability_api_duration_seconds` | Histogram | market, environment, service, partner_slot, api, direction, outcome | Monotonic end-to-end client/server observation duration |
| `partner_observability_api_in_flight` | Gauge | service, partner_slot, api, direction | Current observed interactions; bounded registrations |
| `partner_observability_events_total` | Counter | service, partner_slot, event_name, outcome | Explicit journey events; event_name from max-64 registry |

Histogram buckets are `0.05`, `0.1`, `0.25`, `0.5`, `1`, `2`, `5`, `10`, and `30` seconds. Values above 30 seconds remain in `+Inf`. API-specific SLO thresholds are configuration data used by dashboards/alerts, not new labels or buckets.

## SDK health metrics

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `partner_observability_capture_attempts_total` | Counter | service, capture_mode, record_type | Capture attempts after trusted context/policy resolution |
| `partner_observability_records_enqueued_total` | Counter | service, queue, record_type | Accepted safe records |
| `partner_observability_records_dropped_total` | Counter | service, queue, reason, record_type | Every pre-admission/admission/export loss |
| `partner_observability_queue_events` | Gauge | service, queue | Current event count |
| `partner_observability_queue_bytes` | Gauge | service, queue | Current reserved bytes |
| `partner_observability_dispatch_batches_total` | Counter | service, result | `sent`, `rejected`, `timeout`, `io_error`, `auth_error` |
| `partner_observability_dispatch_events_total` | Counter | service, result | Records by bounded dispatch result |
| `partner_observability_dispatch_duration_seconds` | Histogram | service, result | Dispatcher-only network duration |
| `partner_observability_sanitization_total` | Counter | service, action, data_class | `allowed`, `masked`, `removed`, `omitted`; class enum only |
| `partner_observability_policy_version_info` | Gauge fixed at 1 | service, version | Version is deployment-generated and bounded to one active value |
| `partner_observability_dispatcher_alive` | Gauge | service | 1 while dispatcher loop is alive |

SDK health metrics intentionally omit `partner_slot` to limit series and prevent operational details from appearing on partner dashboards. The partner-visible “telemetry coverage” uses partner-scoped API event counters, while internal operators see queue/export health.

## Platform metrics

Alloy, Loki, Prometheus, Grafana, gateways, ECS, ALB/NLB, S3, and EFS metrics remain internal-only. Internal dashboards include ingest rejection by reason, tenant pipeline health using opaque tenant slot, Loki compactor/retention health, S3 errors, Prometheus WAL/TSDB health, query latency, Grafana auth failures, gateway denials, ECS desired/running count, CPU/memory/storage, and certificate/secret expiry. No raw partner name appears in a metric label; the internal slot mapping is controlled configuration.

## SLI definitions

For a window `W` and server-enforced `partner_slot`, service, API, and direction:

- Availability = `success / eligible`, where `eligible` is all outcomes except explicitly configured partner-caused `business_rejected`. Both numerator and exclusions are shown.
- Technical error rate = `technical_failure / eligible`.
- Business rejection rate = `business_rejected / all completed`.
- Latency = p50/p95/p99 from histogram, displayed for successful eligible requests and for all requests separately.
- Volume = completed interactions per second/minute and total over `W`.
- Telemetry coverage = response records divided by request records for an interaction class; it is an observability-quality SLI, not a business SLA.
- Drop ratio = SDK dropped records divided by capture attempts; internal-only because SDK health lacks partner dimension.
- Isolation correctness = denied cross-tenant test attempts / attempted cross-tenant tests; target all denied.
- Prohibited disclosure count = findings from controlled security verification; target exactly zero and not inferred from production scanning.

Default evaluation windows are 5 minutes for operational panels, 1 hour and 24 hours for trend, and rolling 16 days for partner reports. Calendar SLA semantics, exclusions, and targets are configured per partner/API; the architecture does not invent contractual SLA percentages.

## Cardinality budget

Per application instance, the SDK must expose no more than 10,000 `partner_observability_*` active series. The generated manifest validator calculates the exact upper bound before deployment and rejects a configuration above that number. The market Prometheus target is at most 100,000 active partner-observability series initially; exceeding 70% for 15 minutes blocks onboarding and triggers a capacity review.

The calculation includes histogram buckets plus `_sum`/`_count`, all configured partner/API/outcome combinations, and health metrics. Optional event metrics are disabled unless their precomputed series fit. Meters expire only on process restart because the registry is configuration-fixed.

## Collection and storage

- Applications expose a management-network-only Prometheus endpoint; it is not partner/public accessible.
- Terraform/onboarding supplies Cloud Map DNS target groups to Alloy `discovery.dns`/scrape configuration.
- Alloy scrapes every 30 seconds with a 10-second timeout, applies allowlist/relabel/drop rules, and remote-writes to Prometheus.
- Prometheus enables only the remote-write receiver needed for Alloy, binds it privately, disables admin/lifecycle APIs, and applies 16-day time retention plus a storage-size cap.
- Recording rules precompute dashboard rates and quantiles without adding transaction identifiers.

Missing Alloy/Prometheus never affects business recording; Micrometer updates stay in process. A scrape or write outage causes metric gaps rather than application retries.

## Partner query isolation

Prometheus is not treated as a native tenant boundary. The partner Grafana organization receives a datasource credential mapped by the query gateway to exactly one `partner_slot`. The gateway strips user headers, injects `X-Partner-Slot`, and forwards to `prom-label-proxy`, which parses supported Prometheus API queries and enforces `partner_slot=<fixed>` on every selector with conflict errors enabled. Unsupported API paths and label APIs are denied unless isolation tests cover them.

Alloy accepts `partner_slot` only from the starter's fixed meter registry and validates it against that source service's manifest allowlist, dropping all other values. A fully compromised service can still fabricate metrics for its authorized partner set, which is a documented residual risk. Direct Prometheus access is security-group denied.

## Dashboard contract

The SLA/SLI dashboard provides volume, availability, business rejection, technical error, latency p50/p95/p99, error/status breakdown, and 16-day trend. Dashboard variables may narrow service/API/direction/time, but the partner slot is not a variable. “No data” is visually distinct from zero success or zero errors. Panels disclose the formula, exclusions, source freshness, and last sample time.

## Alerting baseline

Internal alerts, subject to environment-specific routing, include dispatcher dead for 2 minutes, normal/high queue above 80% for 5 minutes, any sustained drops above 1% for 5 minutes, export failures for 5 minutes, Alloy rejection >0, Prometheus remote-write failure, Loki compactor failure, storage above 70%, query gateway denials spike, and datasource health failure. Partner SLA alert thresholds require an approved partner contract and are not enabled by default.
