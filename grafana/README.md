# Grafana

This directory contains the real `LOCAL_SYNTHETIC` partner portal boundary:

- `grafana.ini` disables anonymous access, signup, organization creation, Viewer editing, Explore, alerting, analytics, and update/plugin network checks.
- `provisioning/datasources/partner-datasources.yaml` creates two non-editable, server-proxy datasources in each partner organization. Loki and Prometheus requests go only to the tenant gateway. The gateway credential is supplied through `secureJsonData` from an environment variable.
- `provisioning/dashboards/partner-operations.yaml` loads the same generic dashboard source into isolated PARTNER_A and PARTNER_B organizations.
- `dashboards/partner-operations.json` provides typed transaction search, overview, ordered timeline, selected-record detail, outbound SLI, and callback SLI panels.

Partner selection is deliberately absent. Each local user is a Viewer in exactly one Grafana organization. Both organizations use the same datasource UIDs, but Grafana resolves them inside the authenticated organization to different fixed gateway identities. Nginx injects a fixed Loki tenant or Prometheus `partner_slot`; partner requests cannot supply or replace either value. Loki, Prometheus, and the label proxy remain on the internal backend network.

Run the executable boundary with:

```bash
./scripts/test-grafana.sh
./scripts/test-grafana.sh --validate-only
```

The runner generates passwords and the Grafana secret key in a mode-0600 temporary directory, bootstraps PARTNER_A/PARTNER_B organizations and Viewer users through Grafana's API, restarts Grafana with file provisioning, loads synthetic A/B records and metrics, and removes the stack and credentials on exit. It never prints passwords. `KEEP_RUNNING=1 ./scripts/test-grafana.sh` retains the disposable stack and prints only the path of the temporary credentials file for local inspection.

HTTP is limited to loopback/Docker networking in this explicit `LOCAL_SYNTHETIC` profile. External partner-facing Grafana remains HTTPS-only under the deployment contract; these local accounts and generated credentials are not a production identity design.
