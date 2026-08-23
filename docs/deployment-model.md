# Deployment Model

## Deployment unit and environments

One complete observability stack is deployed per `(AWS account, market, environment, ECS cluster)` and shares that ECS cluster/VPC with the market's partner integration services.

| AWS account | ECS clusters | Partner target |
| --- | --- | --- |
| Production | One `<market>-PROD` cluster per market | Real partners; deployment requires human production workflow outside autonomous-agent scope |
| Staging | `<market>-STAGE` and `<market>-DEV` | STAGE test partners; DEV mock partner services over HTTPS only |

No log, metric, query, credential, bucket prefix, service discovery namespace, or Grafana organization crosses a stack boundary. Local Docker Compose mirrors the logical components using only synthetic data. Its explicitly isolated `LOCAL_SYNTHETIC` fixtures are the only place HTTP may be used; no ECS DEV/STAGE/PROD configuration can enable that exception. There is no Kubernetes or Helm path.

## ECS services

### Alloy ingress service

An internal TLS NLB fronts ECS tasks containing an authenticating Nginx/Envoy-class ingress proxy and Grafana Alloy. PROD starts with two stateless tasks across availability zones; DEV/STAGE use one. The proxy maps source credentials plus partner key to a fixed local Alloy receiver/pipeline. Alloy accepts OTLP logs, validates/sanitizes/routes them, scrapes private Micrometer endpoints discovered through Cloud Map DNS, and remote-writes approved metrics.

The service has no public IP. Its task role reads only its generated configuration and named secret ARNs, publishes its own internal metrics/logs, and can reach Loki/Prometheus write endpoints. It cannot read telemetry S3 objects directly.

### Loki service

The initial topology is one Loki single-binary ECS task per stack, multi-tenancy enabled, TSDB index/schema v13, S3 object storage, and encrypted EFS for WAL/index cache/compactor working data. An internal service endpoint is reachable only from Alloy and the query gateway. The task role has least-privilege access to its stack bucket/prefix and KMS key when used.

The compactor is enabled with `retention_period: 384h`, a two-hour deletion delay, and a working directory on EFS. This is the authoritative 16-day retention. The S3 bucket has an 18-day expiration backstop and no object versioning/noncurrent retention for telemetry. Bucket policy requires TLS and denies access outside the Loki task role and approved break-glass recovery role.

The single task is deliberately cost-conscious and does not claim HA. Scale-up is vertical first. Migration to Loki simple-scalable read/write/backend ECS services requires a tested ADR when CPU/memory/storage/query thresholds exceed 70%, partner count approaches 64, or an approved availability target cannot tolerate restart downtime.

### Prometheus service

One private Prometheus ECS task stores TSDB data on encrypted EFS. The remote-write receiver is enabled only for Alloy; admin and lifecycle APIs are disabled. Retention is 16 days plus an environment-specific size cap (DEV 10 GiB, STAGE 20 GiB, PROD initial 50 GiB). A private query endpoint is reachable only through the query gateway and internal operator network.

Metrics are rebuildable operational data; the initial topology does not provide HA or long-term object storage. Capacity or availability requirements beyond these bounds require an ADR rather than silently adding a new backend.

### Grafana service

One Grafana OSS ECS task is exposed through an ALB HTTPS listener on port 443 with an ACM certificate, an approved TLS-1.2-or-newer security policy, WAF/rate limiting, and approved network access. Port 80 has no listener or security-group rule. Initial local-account/config state uses SQLite on encrypted EFS and AWS Backup daily snapshots retained seven days. One task is mandatory while using SQLite. Anonymous signup/access is disabled; datasource/browser direct access is disabled.

Scaling Grafana beyond one task requires migration to an external supported database and a reviewed session/secret strategy. Partner organizations, datasources, and dashboards are provisioned; local users are managed through an audited operator workflow using Secrets Manager references rather than committed passwords.

### Query gateway and journey-resolver service

PROD uses two stateless tasks; DEV/STAGE one. Nginx authenticates datasource credentials, strips tenant/slot headers, maps identity to fixed tenant/slot, and proxies approved Loki requests. For Prometheus it supplies the trusted slot to a colocated pinned `prom-label-proxy`, which parses/enforces the label on supported query endpoints. A colocated stateless journey resolver accepts a typed identifier only after datasource authentication fixes the tenant, selects at most eight separate configured correlation-profile candidates, performs exact bounded structured-metadata graph queries (three rounds, 32 identifiers, 500 records/round, at most 16 days), and returns at most 2 MiB of timeline/detail data with correlation status. It enforces a 10-second request deadline, at most 20 in-flight resolutions per task, and a default per-datasource rate of 2 requests/second with burst 5. Limit exhaustion returns an explicit partial/429/timeout result without a broader query. It has no database, cache containing partner values, cross-tenant mode, or write permission. Unsupported endpoints are denied. Only Grafana and approved internal operators can connect.

## External partner transport and ALB/ACM ownership

Partner integration services remain private ECS services. Outbound RestTemplate, WebClient, and OkHttp calls leave private subnets through controlled NAT or an approved egress proxy/firewall and connect only to reviewed partner HTTPS endpoints. Host services own client TLS/trust/hostname/redirect configuration; observability tasks and the starter do not alter it.

External callbacks enter a service-owned internet-facing ALB. The callback ALB has only a 443 HTTPS listener, an approved TLS policy with TLS 1.2 minimum, and an ACM-managed certificate matching the approved DNS name. Port 80 is absent rather than redirected. Its target group contains private service tasks with `assign_public_ip=false`; the task security group accepts the application target port only from the callback ALB security group. The ALB termination boundary is not callback authentication, so host signature/authentication/decryption still precedes trusted partner telemetry.

The `observability-network` module owns the equivalent 443-only ALB/ACM attachment for Grafana. It does not create or change callback ALBs belonging to partner integration services. Callback onboarding records an ownership/evidence reference so M8/M9 policy checks can prove compliant listener, certificate, target, routing, and security-group posture without importing TLS ownership into the SDK.

Terraform receives an approved ACM certificate ARN and pins a reviewed listener security policy. Public ACM certificates use the account's approved DNS-validation workflow and managed renewal. Expiry/renewal alarms are internal-only. Replacement attaches and validates the new certificate before removing the still-valid old certificate; rollback reattaches the prior certificate. ACM private keys are not exportable to ECS/Terraform and never appear in plans, outputs, manifests, telemetry, or logs.

## Network boundaries

- Grafana ALB is the observability stack's only partner-facing endpoint; service-owned callback ALBs are the only partner-facing endpoints for callback services.
- External callback and Grafana ALBs accept 443 only. Port 80 listeners/rules are prohibited, including redirect-only listeners.
- Alloy ingest NLB is private and reachable only from onboarded integration-service security groups.
- Actuator scrape endpoints are private and reachable only from Alloy.
- Loki and Prometheus write/query endpoints accept only Alloy/query-gateway security groups as appropriate.
- EFS mount targets and S3/KMS VPC endpoints are private; public bucket access is blocked.
- Security groups reference security groups, not broad CIDRs, where supported. Callback/Grafana target security groups accept only their ALB security group.
- ECS tasks run in private subnets with `assign_public_ip=false`; public route tables attach only to ALB subnets. Task subnets have no internet-gateway route. Controlled outbound HTTPS uses NAT or an approved egress proxy/firewall.
- TLS 1.2+ is required on every external partner listener/connection; AWS Certificate Manager manages load-balancer certificates. ALB is the approved external termination boundary. Backend TLS/plaintext choices inside the private boundary require threat review and default to TLS; they do not weaken the external HTTPS invariant.

No observability component participates in an application load balancer health check, ECS deployment circuit breaker, or readiness decision for partner services.

## Storage, backup, and retention

| Data | Store | Retention / recovery |
| --- | --- | --- |
| Partner-safe Loki events | Dedicated encrypted S3 bucket/prefix per stack | Loki compactor 384h; two-hour delete delay; S3 backstop 18 days; versioning off |
| Loki WAL/cache/work | Encrypted EFS | Operational only; may be recreated after controlled recovery |
| Partner SLI metrics | Prometheus encrypted EFS | 16 days plus size cap; gaps/loss do not affect business |
| Grafana accounts/config DB | Encrypted EFS SQLite | Daily AWS Backup, seven days initially; not partner telemetry |
| Internal component/application logs | CloudWatch/internal account controls | Separate internal retention set by security/operations policy |
| ALB/audit/config evidence | Separate internal S3/CloudWatch | Not governed by the 16-day partner telemetry rule; unresolved policy input |

Secrets live in AWS Secrets Manager and are injected/retrieved with ECS task roles. Certificate/client private keys, keystore/trust-store bytes/passwords, session/signature material, and URI credentials are never manifest values or Terraform outputs. ACM listener configuration uses certificate ARNs only. Terraform state uses an approved encrypted remote backend with locking outside this repository. Neither secrets nor state are committed.

## Configuration artifacts

The versioned non-secret market manifest generates:

- Alloy receivers/processors/tenant routes and metric scrape/relabel rules;
- ingress/query gateway identity maps containing secret references, not secret values;
- Grafana organization, datasource, dashboard, and folder definitions;
- journey-resolver typed identifier schemas, bounded query templates, and record/stage allowlists;
- ECS task configuration locations/digests;
- approved HTTPS endpoint/host/port and callback/Grafana ALB/ACM ownership references, never certificate or trust-store content;
- Terraform variables for partner slots, Cloud Map services, storage, and service sizing.

Generated configuration is validated, assigned a content digest, stored in a versioned deployment artifact location, and mounted/downloaded at task startup. An invalid/missing config makes that observability task unhealthy; it never affects partner-service health. Sensitive runtime maps are readable only by the relevant task role.

## Terraform module boundaries

`terraform/modules` will contain these composable modules:

| Module | Responsibility |
| --- | --- |
| `market-observability-stack` | Composition and cross-module outputs; no resources hidden outside child modules |
| `observability-network` | Existing VPC/cluster inputs, private task/security-group boundaries, private NLB, 443-only Grafana ALB/WAF/ACM attachment, DNS, VPC endpoints; no callback-service ALB ownership |
| `observability-identity` | ECS task/execution roles, least-privilege policies, KMS grants, secret references |
| `loki-storage` | S3 bucket/policy/lifecycle and encrypted EFS access point |
| `ecs-alloy-ingest` | Task/service/autoscaling/config/health/logging for proxy + Alloy |
| `ecs-loki` | Loki task/service, EFS mount, S3/IAM wiring, private discovery |
| `ecs-prometheus` | Prometheus task/service, EFS, retention/size flags, private discovery |
| `ecs-grafana` | Grafana task/service, EFS, ALB target, config/secret wiring, backup policy |
| `ecs-query-gateway` | Nginx + prom-label-proxy + stateless journey-resolver task/service, fixed identity maps, query limits, private discovery |
| `observability-alerts` | ECS/LB/EFS/S3/platform CloudWatch alarms and notification inputs |

Modules take existing ECS cluster, private/public subnets by role, VPC, DNS zone, approved ACM certificate ARN, pinned TLS policy, image digests, sizing, manifest digest, and approved secret ARNs as inputs. They do not create partner integration services or callback ALBs, import certificate/private key data, create users/password values, Terraform backends, production credentials, or perform deployments from documentation checks.

The M8 module set implements these boundaries for an existing market ECS cluster. The composition also includes the architecture-required query gateway even though partner dashboard/query behavior remains an M7 runtime concern: Grafana must never gain a direct Loki or Prometheus network path while M7 is incomplete. The module creates exact SG-to-SG paths, a private TLS Alloy NLB, the single 443-only Grafana ALB, encrypted state, fixed stateful task counts, bounded stateless autoscaling, and exact secret/artifact ARN grants. Provider-schema validation and a mocked local network plan are evidence of configuration correctness only; they are not an AWS plan or deployment-readiness approval for any real environment.

Examples under `terraform/examples/dev`, `stage`, and `prod` eventually demonstrate composition with placeholders. `plan` requires explicit account/environment confirmation; `apply` is never an autonomous default.

## Sizing and autoscaling

Initial task CPU/memory values are deployment inputs validated against minimums after M9 tests. Stateless Alloy/query gateways scale on CPU plus accepted request/query rate with min/max bounds (PROD 2-6, non-prod 1-2). Journey-resolver request concurrency, Loki query timeout, response bytes, and per-credential rate are bounded deployment inputs with safe hard maxima defined by the application contract; overload returns a query error rather than broadening bounds. Stateful Loki/Prometheus/Grafana do not autoscale horizontally in the initial topology. Storage alarms fire at 70%, scaling review at 70% sustained, and critical at 85%.

Application queue/rate limits are the first protection against ingest-cost spikes. Loki per-tenant ingestion/query limits, Prometheus series budgets, ALB/NLB limits, and dashboard query time ranges provide platform bounds.

## Upgrade and rollback

- Pin every container to an approved version and immutable digest.
- Pin and policy-test external ALB TLS policy, HTTPS-only listeners, ACM ARN attachment, and private target reachability; never inherit a permissive mutable listener default.
- Build and validate configurations against the exact image before task rollout.
- Promote identical artifacts through DEV, STAGE, and PROD with soak evidence.
- Roll stateless services before SDK enablement; retain N/N-1 event schema support.
- Back up Grafana state and verify Loki schema/compactor/storage compatibility before stateful upgrades.
- Loki schema entries are append-only with future UTC activation dates; do not edit a historical entry.
- Rollback uses the prior task definition/config when storage schemas permit. Kill switches turn capture metadata-only/off when rollback is unsafe.

No document or Terraform example authorizes a production deployment. Production change approval, credentials, account IDs, domains, and maintenance windows remain external inputs.

## Operational failure expectations

Alloy/query/backend task loss causes bounded drops or unavailable dashboards, not business failure. S3/EFS/KMS/IAM/DNS/TLS errors remain inside observability services. ECS restarts unhealthy observability tasks independently. Partner services do not depend on backend readiness and keep their local dispatch timeouts/drop policy. An outbound partner certificate/hostname failure remains the host client's original business transport failure; the starter does not bypass or retry it. Callback ALB handshake failure never reaches the service and creates internal-only aggregate evidence. Recovery prioritizes TLS validation, certificate/listener integrity, isolation, and configuration validation before resuming ingress.
