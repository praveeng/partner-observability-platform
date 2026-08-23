# Prometheus local metrics plane

Local Compose runs Prometheus as a private remote-write receiver. Grafana Alloy scrapes the synthetic SDK-compatible endpoint, rejects unknown slots/metrics/tag values, removes non-contract labels, overwrites `market`, `environment`, and `service`, and writes the bounded result to Prometheus. Applications only update in-process Micrometer meters; they never call Prometheus or Alloy on a business or callback thread.

Run the focused real-container check with:

```bash
./test/integration/run-local-metrics-plane.sh
```

`partner-recording-rules.yml` supplies five-minute outbound and callback throughput, rates, availability, and p50/p95/p99 histogram quantiles. It intentionally contains no contractual SLA alert. `thresholds.example.yml` shows how environment-owned approved thresholds can be added without inventing defaults or adding labels.

Local Prometheus uses a Docker volume, `16d` time retention, and a `1GB` size cap. Production remains one private market/environment Prometheus task with 16-day retention plus an environment-sized disk cap and durable EFS-backed state/backup policy; the production size value is capacity input, not the local approximation. Prometheus remote-write, admin, lifecycle, and query endpoints remain private in production. Partner query enforcement through the slot-fixed query gateway and `prom-label-proxy` belongs to M7.

The optional request-to-callback completion histogram is not registered: the SDK has no reliable durable original-send timestamp and deliberately keeps no transaction map. An approved host adapter may add it later exactly as described in `docs/metrics-sli.md`.
