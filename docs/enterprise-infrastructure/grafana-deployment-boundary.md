# Grafana Deployment Boundary

## Base infrastructure versus application assets

Central Terraform owns base Grafana bring-up: ECS, private networking, ALB/target group, TLS/ACM,
DNS, WAF/allowlists, IAM, storage/backup, base secrets infrastructure, service discovery, health,
and infrastructure logging.

Sure Partner Observability owns and retains:

- `grafana/dashboards/` dashboard JSON;
- `grafana/alerts/` application alert definitions when present;
- partner organization/folder/datasource provisioning artifacts;
- fixed query-gateway datasource definitions;
- transaction search, journey timeline/detail, and SLI queries;
- dashboard/alert validation and partner-isolation tests.

These application assets are not moved into central Terraform modules. They are packaged,
validated, and deployed by the enterprise GHA release after base Grafana is healthy.

## Deployment contract

GHA needs a non-secret provisioning endpoint or artifact rollout mechanism, Grafana service/task
identifier, HTTPS health URL, expected artifact digest location, and secret ARN references. It must
not receive datasource passwords or Grafana secret values through Terraform output. Rollout must
be repeatable, use deterministic UIDs, preserve immutable Viewer-facing resources, and support a
prior-artifact rollback.

Post-deployment validation proves health, one partner organization per configured partner,
Viewer-only membership, fixed read-only datasources, dashboard availability, tenant/slot isolation,
and denial of direct backend or query manipulation. Infrastructure health alone is not evidence
that application provisioning succeeded.

The current repository has a local provisioning implementation but no tracked enterprise GitHub
Actions workflow. The central deployment mechanism and authentication interface remain required
enterprise integration inputs; this task does not invent or implement them.
