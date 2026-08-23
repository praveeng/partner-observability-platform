# Local Docker Compose environment

`compose.yml` starts the isolated `LOCAL_SYNTHETIC` Alloy/Loki data plane and its fixed-tenant Nginx gateway. Images are pinned by version and digest. Loki has no host port; ingress, query, and Alloy diagnostics bind to loopback only.

```bash
docker compose -f docker/compose.yml up -d --wait
docker compose -f docker/compose.yml down -v
```

Default loopback ports are OTLP/HTTP ingest `14318`, fixed-tenant Loki query `13101`, and Alloy diagnostics/self-metrics `12345`. Override them with `LOCAL_OTLP_PORT`, `LOCAL_QUERY_PORT`, and `LOCAL_ALLOY_METRICS_PORT`.

`nginx/local-synthetic.htpasswd` contains deliberately public, synthetic-only fixture credentials for Partner A/B/C SDK and query identities. They are not secrets, must never be reused outside `LOCAL_SYNTHETIC`, and are not a production authentication design. The gateway rejects unknown identity/route pairs, strips inbound tenant headers, and injects fixed tenants on queries. Ingest uses fixed route-specific Alloy receivers; neither callback JSON nor OTLP metadata can select a tenant.

Run the isolated real-component suite with:

```bash
./scripts/test-security.sh --data-plane
```

The suite creates unique Compose resources and removes its synthetic volumes on exit.
