# Terraform modules

The modules in this directory compose one isolated Partner Observability stack into an existing market VPC and ECS cluster. They create no cluster, partner service, callback ALB, certificate, user credential, Terraform backend, or production deployment.

| Module | Responsibility |
| --- | --- |
| `market-observability-stack` | Validates the non-secret market/partner manifest and composes every child module. |
| `observability-network` | Private task security groups and discovery, internal Alloy NLB, and the 443-only Grafana ALB/ACM/WAF/DNS boundary. |
| `observability-identity` | ECS execution and per-service task roles with exact artifact, telemetry-bucket, repository, log-group, and secret ARN grants. |
| `loki-storage` | Encrypted S3/EFS Loki storage, 18-day object backstop, disabled versioning, and TLS/role bucket policy. |
| `ecs-alloy-ingest` | Stateless proxy/Alloy task, service, private NLB target, and bounded CPU autoscaling. |
| `ecs-loki` | Single stateful Loki task/service with S3 configuration and encrypted EFS work storage. |
| `ecs-prometheus` | Single stateful Prometheus task/service with encrypted EFS and 16-day plus size retention. |
| `ecs-grafana` | Single private Grafana task/service, encrypted EFS SQLite, seven-day backup plan, and ALB target. |
| `ecs-query-gateway` | Stateless trusted query proxy, prom-label-proxy, and journey-resolver service required by the architecture. |
| `observability-alerts` | Internal CloudWatch capacity/task alarms with an optional notification target. |

Every image input requires an immutable `@sha256:<64 hex>` reference. Every ECS service uses private subnets and `assign_public_ip = false`. Stateful services deliberately remain single-task and have no horizontal autoscaling.
