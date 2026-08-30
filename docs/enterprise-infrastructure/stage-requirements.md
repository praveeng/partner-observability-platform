# STAGE Requirements

STAGE is the first environment affected by this contract. It uses the real partner staging
environment and an independent market STAGE cluster/deployment. It must not share tenants,
credentials, buckets/prefixes, service discovery, Grafana organizations, or query paths with DEV or
PROD.

STAGE uses the common requirements with environment-specific inputs for:

- existing STAGE account/region/market cluster, VPC, private/public ALB subnets, endpoints, and
  enterprise egress controls;
- real staging partner identities, source services, opaque Loki tenants, metric slots, Grafana
  organizations, callback ingress evidence, and secret references;
- STAGE Grafana DNS, ACM certificate, WAF, partner IP allowlists, and approved TLS policy;
- immutable image/config digests promoted by the application pipeline;
- CPU/memory/storage, one initial task per component unless the central availability standard
  requires more, and bounded stateless scaling;
- 16-day Loki/Prometheus retention, Loki 18-day lifecycle backstop, encrypted storage, backups,
  internal logs, alarms, and notification routing;
- maintenance, rollback, synthetic isolation tests, and output/change reference for GHA.

STAGE must prove HTTPS ingress, private tasks, SG paths, IAM, storage, base health, application
artifact deployment, one-tenant-per-partner routing, query isolation, dashboards, and rollback
before the PROD infrastructure change is approved. DEV remains independent and unchanged.
