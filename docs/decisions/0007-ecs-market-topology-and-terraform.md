# ADR 0007: ECS market topology and Terraform boundaries

- Status: Accepted for M8 implementation
- Date: 2026-08-16
- Decision owners: Cloud platform architecture

## Context

The platform must run in the same AWS ECS cluster as partner integration services, one deployment per market cluster. Production has PROD; staging has STAGE and DEV, with DEV using mocks. Loki requires S3 persistence. Cost matters, but production backend availability objectives were not supplied.

## Decision

Deploy one independent stack for every account/market/environment/cluster. Use dedicated ECS services for Alloy ingress (proxy+Alloy), Loki single-binary, Prometheus, Grafana, and query gateway (auth proxy+prom-label-proxy+stateless bounded journey resolver). PROD starts with two stateless ingress/query tasks; stateful components use one task. DEV/STAGE use one each. The resolver stores no partner data and has read-only fixed-tenant access. No backend is a partner-service health dependency.

Use encrypted S3 for Loki TSDB objects and encrypted EFS access points for Loki WAL/cache/work, Prometheus TSDB, and initial single-instance Grafana SQLite. Use Secrets Manager, least-privilege task roles, private service discovery/endpoints, TLS load balancers, security-group boundaries, CloudWatch internal telemetry, and AWS Backup for Grafana state.

ADR 0011 refines external transport: the observability network module creates only the 443-only ACM-backed Grafana ALB, while host-service infrastructure owns callback ALBs. All targets are private/no-public-IP and accept only their ALB security group. Port 80 is absent. Outbound partner HTTPS and client trust remain host-service concerns, not observability Terraform or SDK behavior.

Terraform modules are the exact boundaries in `deployment-model.md`, composed by `market-observability-stack`. Inputs reference an existing VPC/ECS cluster and immutable image/config digests. No module creates production credentials or automatically applies.

## Security and availability consequences

- Stack/account/market/environment boundaries constrain blast radius and data movement.
- Single stateful tasks are cost-conscious but explicitly not HA; restart/maintenance can interrupt telemetry dashboards/ingest without business impact.
- EFS simplicity trades performance for initial scale; thresholds trigger reviewed migration.
- DEV mock-only routing is an enforceable environment rule.

## Alternatives considered

- Kubernetes/Helm: prohibited.
- One regional cross-market stack: rejected for isolation/blast radius requirement.
- Full HA/simple-scalable Loki and RDS from day one: viable but unjustified cost without availability/volume inputs.
- Ephemeral-only Grafana/Prometheus: rejected because accounts/dashboards/SLIs need bounded persistence.
- AWS-managed observability replacement: rejected because required runtime names Loki/Prometheus/Grafana/Alloy.

## Implementation and migration

M8 creates modules/non-production examples and plans only. Same digests promote DEV->STAGE->PROD. At 70% sustained resource/storage, partner cap approach, or approved HA SLO, create a migration ADR for simple-scalable Loki, external Grafana DB, and/or metrics architecture.

## Verification evidence required

Terraform format/validate/lint/security/policy and non-production plan tests; SG reachability; IAM least privilege; secret/state scan; restore/restart; no cross-stack paths; DEV mock-only assertions; no Helm/Kubernetes artifacts.

## References and supersession

Normative details: `../deployment-model.md`, `../transport-security.md`, and ADR 0011. No ADR is superseded.
