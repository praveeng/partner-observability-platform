# Integration tests

`run-local-data-plane.sh` validates the Compose, Alloy, Loki, and gateway configurations, then tests real containers with synthetic A/B/C tenants. It proves outbound and callback isolation, colliding identifiers, missing/conflicting routes, callback-body and tenant-header spoofing, record-type enforcement, second-stage credential/Base64 drops and PII masking, exact indexed labels, schema N/N-1 names, structured-metadata searches, correlated outbound/callback journeys, and Alloy self-metrics.

`run-local-metrics-plane.sh` validates Prometheus and recording rules, starts the real Alloy scrape/relabel/remote-write path, and proves trusted deployment-label overwrite, bounded A/B/C `partner_slot` acceptance, arbitrary label/metric and unknown-slot removal, outbound/callback series queries, 16-day/size retention flags, disabled admin/lifecycle APIs, and Alloy scrape/write self-metrics.

Prerequisites are Docker Compose, `curl`, `jq`, and `rg`:

```bash
./test/integration/run-local-data-plane.sh
```

The test uses randomized loopback ports, a unique Compose project, synthetic values only, and disposable volumes. Failure output includes bounded component logs; request bodies are not logged by the gateway.
