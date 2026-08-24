# Integration tests

`run-local-data-plane.sh` validates the Compose, Alloy, Loki, and gateway configurations, then tests real containers with synthetic A/B/C tenants. It proves outbound and callback isolation, colliding identifiers, missing/conflicting routes, callback-body and tenant-header spoofing, record-type enforcement, second-stage credential/Base64 drops and PII masking, exact indexed labels, schema N/N-1 names, structured-metadata searches, correlated outbound/callback journeys, and Alloy self-metrics.

`run-local-metrics-plane.sh` validates Prometheus and recording rules, starts the real Alloy scrape/relabel/remote-write path, and proves trusted deployment-label overwrite, bounded A/B/C `partner_slot` acceptance, arbitrary label/metric and unknown-slot removal, outbound/callback series queries, 16-day/size retention flags, disabled admin/lifecycle APIs, and Alloy scrape/write self-metrics.

`run-local-grafana.sh` starts the complete local observability stack and proves two isolated Grafana organizations, local Viewer authentication, fixed Loki tenants and Prometheus slots, generic dashboard provisioning, typed search (including a colliding application ID), ordered callback timeline, safe detail, all dashboard SLI query families, prohibited-content absence, API/Explore/org/header/UID/PromQL bypass denial, and safe gateway audit records. `--validate-only` still starts real Grafana and verifies provisioned objects through its API; it skips only telemetry/SLI seeding and result assertions.

Prerequisites are Docker Compose, `curl`, `jq`, and `rg`:

```bash
./test/integration/run-local-data-plane.sh
./test/integration/run-local-metrics-plane.sh
./test/integration/run-local-grafana.sh
```

The tests use randomized loopback ports, unique Compose projects, synthetic values only, and disposable volumes. Failure output includes bounded component logs; request bodies and query arguments are not logged by the gateway. Set `KEEP_RUNNING=1` only for local debugging; the Grafana runner retains generated credentials in a mode-0600 temporary file and prints its path.
