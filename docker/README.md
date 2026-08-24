# Local Docker Compose environment

`compose.yml` starts the isolated `LOCAL_SYNTHETIC` Alloy/Loki/Prometheus data plane, fixed-identity Nginx query gateway, and an optional Grafana profile. Images are pinned by version and digest. Loki and the Prometheus label proxy have no host ports; ingress, query, Prometheus, Grafana, and Alloy diagnostics bind to loopback only.

```bash
docker compose -f docker/compose.yml up -d --wait
docker compose -f docker/compose.yml down -v
```

Default loopback ports are OTLP/HTTP ingest `14318`, fixed-tenant Loki/Prometheus query `13101`, Prometheus `19090`, optional Grafana `13000`, and Alloy diagnostics/self-metrics `12345`. Override them with `LOCAL_OTLP_PORT`, `LOCAL_QUERY_PORT`, `LOCAL_PROMETHEUS_PORT`, `LOCAL_GRAFANA_PORT`, and `LOCAL_ALLOY_METRICS_PORT`.

`nginx/local-synthetic.htpasswd` contains deliberately public, synthetic-only fixture credentials for the lower-level Partner A/B/C SDK and Loki query tests. They are not secrets, must never be reused outside `LOCAL_SYNTHETIC`, and are not a production authentication design. The Grafana runner replaces this file with generated temporary credentials. The gateway rejects unknown identity/route pairs, strips inbound authorization inputs, injects fixed Loki tenants, and forwards metrics only through `prom-label-proxy` with a fixed `partner_slot`. Ingest uses fixed route-specific Alloy receivers; neither callback JSON nor OTLP metadata can select a tenant.

Run the isolated real-component suite with:

```bash
./scripts/test-security.sh --data-plane
./scripts/test-security.sh --metrics-plane
./scripts/test-grafana.sh
```

The suite creates unique Compose resources and removes its synthetic volumes on exit.
