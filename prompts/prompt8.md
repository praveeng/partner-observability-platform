# Integrate Sure Partner Observability into centralized Terraform infrastructure

## Mode

ASSESSMENT -> HUMAN APPROVAL -> IMPLEMENTATION. This is the canonical ordered replacement for the former “Prompt 13B.” Assessment and explicit approval are mandatory before any Terraform modification.

Never run `terraform apply`, deploy infrastructure, access production credentials, or execute a production change. Run `terraform plan` only when the user separately authorizes the exact safe target after assessment. Terraform execution remains manual and human-controlled.

## Workspace and first reads

Run from an enterprise workspace containing SureWebServices and the separate centralized enterprise Terraform repository. First locate and identify the centralized Terraform repository from workspace evidence or ask the user if it cannot be identified safely. Do not guess its name or module layout.

Before inspecting Terraform, read:

- `sure-partner-observability/AGENTS.md`
- `sure-partner-observability/PLANS.md`
- `sure-partner-observability/.agent-state/status.json`
- applicable `sure-partner-observability/.codex/skills/`
- the architecture, security invariants, transport security, payload, telemetry, partner isolation, metrics/SLI, deployment, threat, acceptance, and decisions documents
- every file under `sure-partner-observability/docs/enterprise-infrastructure/`

Then read the centralized repository's governing instructions and current state before proposing changes.

## Fixed ownership and environment scope

Central Terraform owns base AWS infrastructure: ECS clusters/services/tasks as applicable, VPC/network integration, private subnets, security groups, ALB/target groups, HTTPS listeners, ACM, DNS, WAF hooks, IAM, S3/KMS/EFS, Secrets Manager/SSM infrastructure, service discovery, autoscaling bounds, base Grafana/Loki/Alloy/Prometheus/query-gateway runtimes, persistent storage, infrastructure health checks, and bounded operational logging.

Sure Partner Observability owns Java/Spring code, `sure-partner-observability-spring-boot-starter`, telemetry and sanitization contracts, application Alloy pipeline logic, Loki tenant/runtime policy, Prometheus application metrics/rules, Grafana dashboards/alerts/query semantics, integration/security/performance tests, and versioned application configuration artifacts. Dashboards and alerts remain GHA-owned application assets and must not move into Terraform.

Environment scope is non-negotiable:

- LOCAL: no Terraform change; local VM/Docker/LocalStack/Testcontainers/mock topology remains independent.
- DEV: no Terraform change by default; the existing isolated AWS DEV ECS cluster/VPC and mock partner remain unchanged.
- STAGE: Partner Observability base-infrastructure integration is allowed after approval for the dedicated AWS STAGE cluster/VPC and real partner staging environment.
- PROD: Partner Observability base-infrastructure integration is allowed after approval for the production environment, only after STAGE evidence and production controls.

DEV and STAGE may share a PH market AWS account but must use separate ECS clusters, VPCs, resources, endpoints, state, storage, tenants, credentials, and configuration. Do not modify DEV merely because it shares the account.

## Mandatory discovery in the centralized repository

Discover and document:

- repository composition, provider and backend conventions, account/region/market/environment model, state isolation, variable/output patterns, naming/tagging, review/manual execution controls;
- reusable modules/patterns for ECS, task definitions, services, capacity providers, autoscaling, VPC/subnets/endpoints/NAT/egress, security groups, ALB/NLB/target groups/listeners, ACM, DNS, WAF, IAM, S3, KMS, EFS, Secrets Manager, SSM, CloudWatch, service discovery, health checks, backups, image/config artifacts, and GHA output handoff;
- current STAGE and PROD composition patterns and any comparable observability/runtime services;
- current Grafana, Loki, Alloy, Prometheus, label-proxy, or query-gateway patterns;
- replacement, data-loss, public-exposure, IAM, secret, cost, and cross-environment risks.

Do not prescribe a new module until existing enterprise modules and composition patterns have been mapped against every contract requirement. Prefer generic extensions reusable by 12+ `sure-nbfc-*` services and multiple markets.

## Required infrastructure capability

Support one Partner Observability deployment per market/environment serving one or more partner-facing services and multiple isolated partner identities. A new partner must be onboarded through reviewed configuration/runtime provisioning rather than a new Partner Observability codebase. Enforce one opaque Loki tenant per partner; client-provided identity or tenant headers never select it.

The approved central implementation may need:

- private ECS tasks/services for authenticated Alloy ingress, Loki, Prometheus, Grafana, and the fixed query gateway/Prometheus label proxy/journey resolver, using existing enterprise task patterns;
- immutable image digests, bounded CPU/memory/storage/counts, component-only health checks, configuration artifact digest interfaces, and rollback;
- private subnets, no public task IPs, exact SG-to-SG connectivity, private service discovery, controlled egress, and approved VPC endpoints;
- encrypted Loki S3 storage, least-privilege prefix access, public-access block, TLS-only access, object versioning disabled for telemetry, exact 384-hour retention with two-hour deletion delay, and 18-day lifecycle backstop;
- encrypted persistent Loki work state, Prometheus TSDB, and Grafana state where the existing architecture requires it;
- separate execution/task/deployment roles with exact ECR, configuration, log, secret, S3, KMS, EFS, discovery, and runtime permissions;
- secret/parameter ARNs and task resolution without exposing secret values in Terraform outputs, state-visible configuration, CI logs, or artifacts;
- private Alloy ingestion from approved service SGs; Alloy-only Loki writes and Prometheus remote writes; fixed-gateway-only Loki/Prometheus queries; no direct Grafana-to-backend bypass;
- partner Grafana ingress through the approved ALB on HTTPS 443 only, explicit TLS 1.2-or-newer policy, ACM, DNS, WAF/rate-control and partner-IP allowlisting hook, private targets, and no port 80;
- component infrastructure health/alarms that never become partner-service readiness dependencies;
- conservative CloudWatch/access-log retention containing only unavoidable operational/infrastructure logs, never partner payload telemetry;
- non-secret outputs required by GHA: environment/market/cluster/service identifiers, private Alloy endpoint/trust reference, internal discovery endpoints, Grafana HTTPS URL/ALB target identifiers, configuration deployment destination, Loki storage/KMS references, secret/parameter reference ARNs, source SG identifiers, persistent storage IDs, log/health identifiers, and infrastructure version/change reference.

Do not create RDS, another relational database, or Liquibase infrastructure unless current source proves Sure Partner Observability has a real schema requirement. The current platform contract states **NO DATABASE REQUIRED FOR CURRENT ARCHITECTURE**. Grafana component state is not an application business schema.

## Security and availability requirements

- All externally reachable partner/Grafana traffic uses HTTPS/TLS. Reject trust-all TrustManager, permissive HostnameVerifier, certificate/hostname bypass, and HTTPS-to-HTTP fallback.
- Loki, Prometheus, Alloy, query internals, Actuator, and ECS tasks are not public or directly partner reachable.
- Partner identity and query scope are server-fixed. Grafana organization/UI variables do not authorize data.
- Transaction identifiers are structured metadata, not Loki or metric labels.
- Alloy/Loki/Prometheus/Grafana outages cause telemetry loss only and never block business traffic.
- Do not duplicate Partner Observability payload telemetry into CloudWatch; use bounded operational logs and cost-conscious retention.
- Use existing enterprise encryption, image scanning, tagging, backup, maintenance, promotion, and audit conventions.

## Mandatory assessment output before changes

Return all of the following with file/module evidence:

1. Centralized Terraform repository discovered.
2. Reusable enterprise modules and composition patterns.
3. Current STAGE account/cluster/VPC/state pattern.
4. Current PROD account/cluster/VPC/state pattern.
5. Exact proposed STAGE changes.
6. Exact proposed PROD changes.
7. Confirmation LOCAL changes = NONE.
8. Confirmation DEV changes = NONE.
9. Exact files proposed.
10. Existing modules reused.
11. Generic module extensions or genuinely missing modules proposed.
12. Destructive/replacement/data-loss/public-exposure/cost risks.
13. Exact non-secret outputs required by SureWebServices GHA.
14. Manual infrastructure and application handoff actions.
15. Human decisions and missing environment inputs.
16. Requirement-to-module/input/output/security-evidence matrix.

Stop and ask for explicit approval before modifying Terraform.

## Implementation after approval

Implement only the approved minimum STAGE/PROD changes, reusing established enterprise modules. Do not create a parallel Terraform architecture. Do not modify LOCAL or DEV. Preserve remote state isolation, import/migration practices, enterprise naming/tagging, and application-owned asset boundaries.

Run `terraform fmt`, `terraform validate`, and repository-standard lint, security, policy, documentation, unit/static, and cost checks that do not access or mutate AWS unless separately authorized. Do not run `terraform apply`. Run a plan only with explicit authorization for the exact target and inspect it for public exposure, broad IAM, secret values, replacement/data loss, unexpected DEV changes, and cost.

If any proposed or observed change may replace or destroy a VPC, subnet, ECS cluster/service, ALB/target group/listener, S3 bucket/prefix data, EFS, IAM role/policy, DNS record/zone, ACM association, security group, KMS key, secret, or another existing resource, stop before that change. Explain state migration/import/moved-block and staged alternatives, rollback, data preservation, outage, cost, and approvals.

Create a central-repository local commit only after approved changes and non-mutating checks pass. Do not push, deploy, or apply.

## Final report

Report files changed, modules reused/extended, STAGE changes, PROD changes, explicit LOCAL/DEV non-changes, ECS/runtime topology, Grafana, Loki, Alloy, Prometheus, S3/KMS/EFS, IAM/secrets, ALB/ACM/WAF/network, CloudWatch/cost impact, GHA outputs, validation results, replacement and rollback considerations, unresolved inputs, commit hash, and this manual order:

```text
approved Terraform code
  -> human infrastructure review
  -> manual Terraform execution
  -> base STAGE/PROD infrastructure available
  -> SureWebServices GHA
  -> service/runtime configuration
  -> Grafana dashboards and alerts
  -> post-deployment validation
```

No Terraform apply and no deployment are authorized by this prompt.
