# Integration tests

`run-local-data-plane.sh` validates the Compose, Alloy, Loki, and gateway configurations, then tests real containers with synthetic A/B/C tenants. It proves outbound and callback isolation, colliding identifiers, missing/conflicting routes, callback-body and tenant-header spoofing, record-type enforcement, second-stage credential/Base64 drops and PII masking, exact indexed labels, schema N/N-1 names, structured-metadata searches, correlated outbound/callback journeys, and Alloy self-metrics.

`run-local-metrics-plane.sh` validates Prometheus and recording rules, starts the real Alloy scrape/relabel/remote-write path, and proves trusted deployment-label overwrite, bounded A/B/C `partner_slot` acceptance, arbitrary label/metric and unknown-slot removal, outbound/callback series queries, 16-day/size retention flags, disabled admin/lifecycle APIs, and Alloy scrape/write self-metrics.

`run-local-grafana.sh` starts the complete local observability stack and proves two isolated Grafana organizations, local Viewer authentication, fixed Loki tenants and Prometheus slots, generic dashboard provisioning, typed search (including a colliding application ID), ordered callback timeline, safe detail, all dashboard SLI query families, prohibited-content absence, API/Explore/org/header/UID/PromQL bypass denial, and safe gateway audit records. `--validate-only` still starts real Grafana and verifies provisioned objects through its API; it skips only telemetry/SLI seeding and result assertions.

`run-local-end-to-end.sh` builds `sure-partner-observability-test-app` and the SDK from source, starts the complete disposable platform, and drives real RestTemplate and HTTP callback fixtures. Final visibility assertions use PARTNER_A/PARTNER_B Viewer sessions and their fixed Grafana datasource/query-gateway paths. The runner does not inject OTLP records or query Loki directly. Generated credentials stay in a mode-0600 temporary file and are removed on normal teardown.

## Requirements 36–46 traceability

| Requirement | Application-originated scenario | Executable assertion | Evidence source |
| ---: | --- | --- | --- |
| 36 | PARTNER_A and PARTNER_B RestTemplate success journeys | `SYNC` requires paired `PARTNER_API_REQUEST`/`PARTNER_API_RESPONSE`, identifiers, API, direction, status, latency, and safe request detail | Viewer-authenticated Grafana Loki datasource proxy plus real app response |
| 37 | HTTP 202 async request followed by a delayed real callback | `ASYNC-CALLBACK` requires the ordered request, acknowledgement, callback receipt, processing, and response subset | Test-app lifecycle endpoint and authorized Grafana Loki datasource proxy |
| 38 | Sync and async transactions with application, loan, correlation, and partner references | `SYNC` and `ASYNC-CALLBACK` search each typed structured-metadata field and require the expected record counts | Partner-fixed Grafana Loki datasource proxy |
| 39 | Callback-success journey with a generated callback reference | `ASYNC-CALLBACK` searches `callback_reference_id` and requires the correlated callback records | Partner-fixed Grafana Loki datasource proxy |
| 40 | Exact selected `CALLBACK_JOURNEY_UPDATED` application statement | `EVENT` requires a `PARTNER_EVENT` with the configured name, journey stage, safe scalar attribute, and transaction correlation | SDK dispatcher, Alloy receiver count, tenant Loki, and authorized Grafana query |
| 41 | Success, timeout, and retry RestTemplate journeys | `METRICS-SLI` requires request/success/timeout/retry counters, latency count, rate, and p50/p95/p99 values greater than zero | Partner-fixed Grafana Prometheus datasource proxy |
| 42 | Callback success, retry, and processing-failure journeys | `METRICS-SLI` requires delivery, processing success/failure, callback latency, and callback throughput values | Partner-fixed Grafana Prometheus datasource proxy |
| 43 | Equivalent A/B traffic plus forged tenant/slot headers | `ISOLATION`, `METRICS-SLI`, and `GRAFANA-QUERY` require mutual log/event/metric denial and ignore caller routing headers | Separate Grafana organizations and server-fixed Loki tenant/Prometheus slot routes |
| 44 | Independent A/B real callbacks | `ISOLATION` requires mutual callback-reference and loan denial through each Viewer path | Partner-fixed Grafana Loki datasource proxy |
| 45 | A/B traffic sharing the fixture application ID | `ISOLATION` requires each Viewer search to contain only its own API/callback names | Partner-fixed Grafana Loki datasource proxy |
| 46 | A/B callbacks sharing `SYNTHETIC-CALLBACK-REFERENCE-COLLISION-0001` | `ISOLATION` requires each callback-reference search to contain only its own callback records | Partner-fixed Grafana Loki datasource proxy |

Prerequisites are Docker Compose, `curl`, `jq`, and `rg`:

```bash
./test/integration/run-local-data-plane.sh
./test/integration/run-local-metrics-plane.sh
./test/integration/run-local-grafana.sh
./test/integration/run-local-end-to-end.sh
```

The tests use randomized loopback ports, unique Compose projects, synthetic values only, and disposable volumes. Failure output includes bounded component logs; request bodies and query arguments are not logged by the gateway. Set `KEEP_RUNNING=1` only for local debugging; the Grafana runner retains generated credentials in a mode-0600 temporary file and prints its path.
