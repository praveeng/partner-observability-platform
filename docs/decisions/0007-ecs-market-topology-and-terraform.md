# ADR 0007: ECS market topology and Terraform boundaries

- Status: Accepted topology; repository implementation ownership superseded by ADR 0013
- Date: 2026-08-16
- Decision owners: Cloud platform architecture

## Context

The platform must run in the same AWS ECS cluster as partner integration services, one deployment per market cluster. Production has PROD; staging has STAGE and DEV, with DEV using mocks. Loki requires S3 persistence. Cost matters, but production backend availability objectives were not supplied.

## Decision

Deploy one independent stack for every account/market/environment/cluster. Use dedicated ECS services for Alloy ingress (proxy+Alloy), Loki single-binary, Prometheus, Grafana, and query gateway (auth proxy+prom-label-proxy+stateless bounded journey resolver). PROD starts with two stateless ingress/query tasks; stateful components use one task. DEV/STAGE use one each. The resolver stores no partner data and has read-only fixed-tenant access. No backend is a partner-service health dependency.

Use encrypted S3 for Loki TSDB objects and encrypted EFS access points for Loki WAL/cache/work, Prometheus TSDB, and initial single-instance Grafana SQLite. Use Secrets Manager, least-privilege task roles, private service discovery/endpoints, TLS load balancers, security-group boundaries, CloudWatch internal telemetry, and AWS Backup for Grafana state.

ADR 0011 refines external transport: the observability network module creates only the 443-only ACM-backed Grafana ALB, while host-service infrastructure owns callback ALBs. All targets are private/no-public-IP and accept only their ALB security group. Port 80 is absent. Outbound partner HTTPS and client trust remain host-service concerns, not observability Terraform or SDK behavior.

The infrastructure capabilities and inputs are defined in `deployment-model.md` and the enterprise
infrastructure requirements contract. The centralized enterprise Terraform repository implements
them by reusing its established patterns. This repository owns no enterprise Terraform modules,
state, plans, or execution.

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

The former M8 modules/examples were retired under ADR 0013 after their requirements were migrated.
Application digests promote through the enterprise release process after base infrastructure
exists. At 70% sustained resource/storage, partner cap approach, or approved HA SLO, create a
migration ADR for simple-scalable Loki, external Grafana DB, and/or metrics architecture.

## Verification evidence required

The centralized Terraform change supplies format/validate/lint/security/policy, reviewed plan,
SG-reachability, least-privilege, secret/state, cost/replacement, restore/restart, and cross-stack
isolation evidence. This repository validates the requirements contract and application-owned
runtime/local behavior only.

## References and supersession

Normative details: `../deployment-model.md`, `../transport-security.md`, ADR 0011, ADR 0013, and
`../enterprise-infrastructure/README.md`. ADR 0013 supersedes repository implementation ownership.
