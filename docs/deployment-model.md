# Deployment Model

## Environments

- Local integration uses Docker Compose and synthetic data.
- Cloud deployment targets AWS ECS and is provisioned with Terraform.
- Production deployment, production credentials, and autonomous production changes are prohibited.
- Kubernetes and Helm are not supported.

## Baseline component separation

Application tasks emit asynchronously to an external Alloy endpoint or approved sidecar/daemon topology. Alloy routes partner-isolated logs to Loki and exposes/sends health metrics for Prometheus. Grafana queries Loki/Prometheus through server-authorized data sources. Exact ECS service/task placement is an M1 decision.

Network failure or component replacement must not affect business request success. Backends are not health-check dependencies for application readiness unless the check is informational and cannot remove business capacity.

## Terraform boundary

Reusable modules belong in `terraform/modules`; non-production examples belong in `terraform/examples`. Terraform must define encryption, network boundaries, IAM/roles, log/metric storage, service discovery, resource limits, and secret references once the topology is approved. State and secrets are never committed.

Examples must default to non-production-safe names/settings and must not apply automatically. CI may format, validate, lint, and generate reviewed plans but must not deploy production.

## Open deployment decisions

The AWS account/VPC integration contract, ECS topology, persistence/retention, high availability, tenancy credentials, ingress/authentication, secret manager, image registry, and disaster recovery objectives require M1 decisions listed in `decisions-needed.md`.
