# PROD Requirements

PROD uses the same architecture and application artifacts as STAGE in the dedicated production
account and market PROD cluster. It requires explicit human production approval/change reference
and successful STAGE evidence. No autonomous agent, this repository, or its GHA executes production
Terraform.

In addition to the common contract, PROD requires explicit values and approval for:

- production account/region/cluster/VPC/subnets and enterprise state/ownership controls;
- real partner tenant/slot/organization mappings, partner ingress allowlists, source services,
  secret ownership, callback ingress evidence, and offboarding/tombstone controls;
- production DNS, ACM certificate/renewal evidence, WAF/rate policy, TLS policy, access logging, and
  alarm escalation;
- task sizing, storage cap, backup/recovery, maintenance window, scaling bounds, and measured cost;
- minimum two Alloy ingress and two query-gateway tasks across supported failure domains; initial
  Loki, Prometheus, and SQLite Grafana remain single-task/non-HA unless separately approved;
- deletion protection and replacement/data-loss review for stateful resources;
- named deployment/operator/break-glass roles, least privilege, audit retention, and production
  secret/KMS references;
- application rollout, canary/soak, post-deployment partner isolation, health, dashboard, retention,
  certificate, and rollback evidence.

Differences from STAGE are configuration—capacity, counts, URLs, certificates, partners,
allowlists, secrets, thresholds, backups, maintenance, and approvals—not a duplicate codebase or
architecture. Production infrastructure availability does not become a dependency of partner
business traffic.
