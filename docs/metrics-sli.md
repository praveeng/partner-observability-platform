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
| `interaction_kind` | `sync_outbound`, `async_initiation`, `callback` |
| `ack_outcome` | `accepted`, `rejected`, `no_ack_timeout`, `transport_failure`, `cancelled`, `unknown` |
| `delivery_class` | `initial`, `retry`, `duplicate`, `unknown` |
| `callback_stage` | Fixed callback lifecycle stage enum; never a configured free string |
| `processing_mode` / `processing_phase` | Fixed `inline|background` and bounded phase enums |
| `outcome` | `success`, `business_rejected`, `technical_failure`, `cancelled`, `unknown` |
| `status_class` | `1xx`, `2xx`, `3xx`, `4xx`, `5xx`, `io_error`, `cancelled`, `unknown` |
| `capture_mode` | `full_sanitized`, `metadata_only`, `no_payload` |
| `reason` | Contract enum; never raw text |
| `transport_failure_class` | `tls_handshake`, `tls_certificate_validation`, `tls_hostname_verification`, `tls_protocol_negotiation`, `tls_configuration`, `unknown_tls` |
| `queue` | `high`, `normal`, `retry` |
| `record_type` | `outbound_api_request`, `outbound_api_response`, `async_acknowledgement`, `callback_request`, `callback_response`, `callback_processing_event`, `partner_business_event` |
| `result` | HTTP: `http_1xx|http_2xx|http_3xx|http_4xx|http_5xx|timeout|connection_failure|cancelled|unknown`; callback response: `write_completed|write_failed|cancelled|unknown` |
| `action` / `data_class` | Sanitizer enums defined by payload policy |
| `version` | Exactly one active manifest-generated policy version per service |

The meter registry pre-registers only manifest-defined partner/API combinations. Unknown runtime values map to `unknown` only for an existing meter and do not create a new tag value. Alloy overwrites market/environment/service labels, validates `partner_slot` against the configured source-service set, and drops metrics/labels outside this contract. `honor_labels` is false.

HTTP method is intentionally not a label in the initial manifest because each configured `api` already has one startup-fixed method; duplicating it adds series without improving a query. A future API that legitimately permits several methods may add the bounded method enum only after the series calculator and recording rules are updated.

## Application SLI metrics

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `partner_observability_http_interactions_total` | Counter | market, environment, service, partner_slot, api, interaction_kind, direction, outcome, status_class, result | Completed sync responses and async acknowledgement terminals; `result` separates timeouts and connection failures without exception-derived labels |
| `partner_observability_http_duration_seconds` | Histogram | service, partner_slot, api, interaction_kind, direction, outcome | Monotonic duration of one HTTP observation, not an entire async journey |
| `partner_observability_http_in_flight` | Gauge | service, partner_slot, api, interaction_kind, direction | Current observed HTTP interactions; bounded registrations |
| `partner_observability_outbound_retries_total` | Counter | service, partner_slot, api, interaction_kind, direction | Attempts whose trusted host `OutboundAttemptResolver` reports as attempt 2-10; the SDK never performs a business retry |
| `partner_observability_async_acknowledgements_total` | Counter | service, partner_slot, api, ack_outcome, status_class | Async initiation terminal acknowledgement outcomes, including no-ack failures |
| `partner_observability_async_acknowledgement_duration_seconds` | Histogram | service, partner_slot, api, ack_outcome | Initiation-to-terminal acknowledgement observation |
| `partner_observability_callback_deliveries_total` | Counter | service, partner_slot, api, delivery_class | Authenticated callback deliveries; unauthenticated attempts stay internal-only |
| `partner_observability_callback_processing_total` | Counter | service, partner_slot, api, processing_mode, processing_phase, outcome | Explicit validated/started/terminal processing facts from fixed phases |
| `partner_observability_callback_processing_duration_seconds` | Histogram | service, partner_slot, api, processing_mode, outcome | Started-to-terminal business processing duration when observed |
| `partner_observability_callback_response_total` | Counter | service, partner_slot, api, outcome, status_class, result | Local response outcome; `result` is `write_completed`, `write_failed`, `cancelled`, or `unknown` |
| `partner_observability_events_total` | Counter | service, partner_slot, event_name, outcome | Explicit journey events; event_name from max-64 registry |

Histogram buckets are `0.05`, `0.1`, `0.25`, `0.5`, `1`, `2`, `5`, `10`, and `30` seconds. Values above 30 seconds remain in `+Inf`. API-specific SLO thresholds are configuration data used by dashboards/alerts, not new labels or buckets.

Callback delivery age or total async journey duration is not derived from an unbounded in-memory correlation map. An optional `partner_observability_callback_delivery_age_seconds` histogram may be registered only for APIs whose business adapter supplies a trusted original-sent timestamp from existing business state; the SDK validates a 0-to-16-day duration and never stores a transaction identifier. Without that adapter, the dashboard shows Loki event-time distributions as diagnostic, explicitly not as a complete Prometheus SLA.

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
| `partner_observability_callback_ingress_denied_total` | Counter | service, reason | Internal-only missing/untrusted/conflicting callback-context reason; no partner slot, route/body ID, or credential detail |
| `partner_observability_transport_security_failures_total` | Counter | service, api, direction, interaction_kind, transport_failure_class | Internal-only structured outbound TLS terminal failures; no peer/certificate/exception/key detail |
| `partner_observability_policy_version_info` | Gauge fixed at 1 | service, version | Version is deployment-generated and bounded to one active value |
| `partner_observability_dispatcher_alive` | Gauge | service | 1 while dispatcher loop is alive |

SDK health metrics intentionally omit `partner_slot` to limit series and prevent operational details from appearing on partner dashboards. Transport-security failures are internal-only health; partner-visible HTTP SLIs continue to show the bounded technical outcome without certificate detail. The partner-visible “telemetry coverage” uses partner-scoped interaction/event counters, while internal operators see queue/export/context-denial/transport health.

## Platform metrics

Alloy, Loki, Prometheus, Grafana, gateways, ECS, ALB/NLB, S3, and EFS metrics remain internal-only. Internal dashboards include ingest rejection by reason, tenant pipeline health using opaque tenant slot, Loki compactor/retention health, S3 errors, Prometheus WAL/TSDB health, query latency, Grafana auth failures, gateway denials, ECS desired/running count, CPU/memory/storage, and certificate/secret expiry. No raw partner name appears in a metric label; the internal slot mapping is controlled configuration.

## SLI definitions

For a window `W` and server-enforced `partner_slot`, service, API, interaction kind, and direction:

- Synchronous availability = successful completed sync responses / eligible sync responses, where eligible excludes only explicitly configured partner-caused business rejection. Numerator, denominator, and exclusions are shown.
- Async acknowledgement acceptance = `accepted / eligible async acknowledgement terminals`; timeout, transport failure, and technical rejection remain in the denominator, while configured business rejection is shown separately.
- Async acknowledgement latency = p50/p95/p99 of the acknowledgement histogram by accepted and all terminal outcomes.
- Callback delivery volume and retry ratio = authenticated deliveries and `(retry + duplicate) / all authenticated deliveries`. This is not callback completeness because the platform does not know how many callbacks a partner should send without an approved business denominator.
- Callback processing success = terminal `CALLBACK_PROCESSED success / (processed success + processing failed)` for eligible processing attempts. Received/started but non-terminal callbacks are shown as in-flight/coverage gaps, not counted as success.
- Callback processing latency = p50/p95/p99 from explicit started-to-terminal observations, separated by inline/background and outcome.
- Callback response-write success = `write_completed / all terminal callback response writes`; it remains separate from business processing success.
- Technical error rate = `technical_failure / eligible`.
- TLS handshake/certificate/hostname failures contribute to the normal technical-failure outcome. They do not create a separate partner SLA denominator or expose certificate identity; internal operators may use the fixed transport-failure counter for diagnosis.
- Business rejection rate = `business_rejected / all completed`.
- HTTP latency = p50/p95/p99 from the applicable one-exchange histogram, displayed for successful eligible interactions and all interactions separately.
- Volume = completed interactions per second/minute and total over `W`.
- Telemetry coverage is shown separately as sync responses/requests, async acknowledgements/async requests, callback responses/callback deliveries, and processing terminals/processing starts. Ratios are observability-quality SLIs, may be distorted by independent drops/retries, and are never business SLAs.
- Drop ratio = SDK dropped records divided by capture attempts; internal-only because SDK health lacks partner dimension.
- Isolation correctness = denied cross-tenant test attempts / attempted cross-tenant tests; target all denied.
- Prohibited disclosure count = findings from controlled security verification; target exactly zero and not inferred from production scanning.

Default evaluation windows are 5 minutes for operational panels, 1 hour and 24 hours for trend, and rolling 16 days for partner reports. “No data” and “no terminal event yet” are distinct from zero failures or success. Calendar SLA semantics, callback-completeness denominator, maximum processing window, exclusions, and targets are configured per partner/API; the architecture does not invent contractual SLA percentages.

## Cardinality budget

Per application instance, the SDK must expose no more than 10,000 `partner_observability_*` active series. The generated manifest validator calculates the exact upper bound before deployment and rejects a configuration above that number. The market Prometheus target is at most 100,000 active partner-observability series initially; exceeding 70% for 15 minutes blocks onboarding and triggers a capacity review.

The calculation includes the nine finite histogram buckets, `+Inf`, `_sum`, `_count`, and Micrometer's `_max`, every valid configured partner/API/interaction/outcome/stage combination, and health metrics. It counts only legal combinations from the manifest state machine rather than registering a full Cartesian product, but it still rejects the configuration if the exact total exceeds the cap. Optional event and callback-delivery-age metrics are disabled unless their precomputed series fit. Meters expire only on process restart because the registry is configuration-fixed.

## Collection and storage

- Applications expose a management-network-only Prometheus endpoint; it is not partner/public accessible.
- Terraform/onboarding supplies Cloud Map DNS target groups to Alloy `discovery.dns`/scrape configuration.
- Alloy scrapes every 30 seconds with a 10-second timeout, applies allowlist/relabel/drop rules, and remote-writes to Prometheus.
- Prometheus enables only the remote-write receiver needed for Alloy, binds it privately, disables admin/lifecycle APIs, and applies 16-day time retention plus a storage-size cap.
- Recording rules precompute dashboard rates and quantiles without adding transaction identifiers.

The LOCAL_SYNTHETIC Compose profile uses the same scrape/relabel/remote-write shape with a fixed synthetic endpoint, a five-second test scrape interval, `16d` retention, and a `1GB` volume cap. Production uses Cloud Map discovery, the contractual 30-second/10-second scrape settings, private network paths, EFS-backed state, and an environment-sized cap. The local fixture is evidence for pipeline shape, not a production durability or capacity claim.

Missing Alloy/Prometheus never affects business recording; Micrometer updates stay in process. A scrape or write outage causes metric gaps rather than application retries.

## Partner query isolation

Prometheus is not treated as a native tenant boundary. The partner Grafana organization receives a datasource credential mapped by the query gateway to exactly one `partner_slot`. The gateway strips user headers, injects `X-Partner-Slot`, and forwards to `prom-label-proxy`, which parses supported Prometheus API queries and enforces `partner_slot=<fixed>` on every selector with conflict errors enabled. Unsupported API paths and label APIs are denied unless isolation tests cover them.

Alloy accepts `partner_slot` only from the starter's fixed meter registry and validates it against that source service's manifest allowlist, dropping all other values. A fully compromised service can still fabricate metrics for its authorized partner set, which is a documented residual risk. Direct Prometheus access is security-group denied.

## Dashboard contract

The SLA/SLI dashboard provides sync volume/availability/latency, async acknowledgement acceptance/latency, callback delivery/retry, processing success/latency, response-write outcome, business rejection, technical error, error/status breakdown, coverage, and 16-day trend. Dashboard variables may narrow service/API/interaction kind/direction/time, but the partner slot is not a variable. “No data,” “not terminal yet,” and zero success/errors are visually distinct. Panels disclose the formula, exclusions, source freshness, last sample time, and whether a measure is Prometheus SLI or best-effort Loki diagnostic.

## Alerting baseline

Internal alerts, subject to environment-specific routing, include dispatcher dead for 2 minutes, normal/high queue above 80% for 5 minutes, any sustained drops above 1% for 5 minutes, export failures for 5 minutes, Alloy rejection >0, callback trusted-context denials spike, outbound TLS failure spike, ACM/certificate renewal or expiry risk, correlation partial/conflict/query-limit spike, Prometheus remote-write failure, Loki compactor failure, storage above 70%, query gateway denials spike, and datasource health failure. Partner SLA/callback-staleness alert thresholds require an approved partner contract and are not enabled by default.
