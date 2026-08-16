---
name: terraform-ecs-review
description: Review Terraform modules and AWS ECS topology for secure, cost-conscious, reversible deployment without applying infrastructure. Use for Terraform, ECS task/service, networking, IAM, S3, secrets, environment topology, upgrade, or market onboarding changes.
---

# Terraform ECS Review

Perform a plan-only infrastructure safety review. Never deploy or use production credentials.

## Load authoritative requirements

Read `AGENTS.md`, `PLANS.md`, `.agent-state/status.json`, `docs/architecture.md`, `docs/security-invariants.md`, `docs/threat-model.md`, `docs/partner-isolation.md`, `docs/deployment-model.md`, `docs/acceptance-criteria.md`, ADR 0005, ADR 0007, ADR 0008, and applicable unresolved decisions. Derive environments, module boundaries, ports, retention, sizing, IAM, secrets, and rollout rules from those sources.

## Review workflow

1. Classify the target as a reusable module or a non-production example/root composition. Ensure module boundaries match the deployment model.
2. Trace each ECS service, listener, security group, IAM role, secret, log path, persistent store, service-discovery name, and dependency.
3. Prove one deployment per market cluster: PROD only in the production account; STAGE and DEV in staging; DEV points only to mock partner services.
4. Prove observability services cannot become synchronous health/deployment dependencies for business services.
5. Review capacity, autoscaling, S3/Loki retention, backup assumptions, upgrade order, rollback, auditability, and cost controls.
6. Initialize and plan only against synthetic/non-production values. Do not run `terraform apply`, `destroy`, `import`, state mutation, or credential discovery.

## Deterministic checks

```bash
git diff --check
terraform fmt -check -recursive terraform
rg -n -i "kubernetes|k8s|helm" terraform docker --glob '!*.md'
rg -n -i "0\.0\.0\.0/0|::/0|assign_public_ip|publicly_accessible|password|secret|token|access_key" terraform
rg -n -i "latest|:latest" terraform docker
rg -n "market-observability-stack|observability-network|observability-identity|loki-storage|ecs-alloy-ingest|ecs-loki|ecs-prometheus|ecs-grafana|ecs-query-gateway|observability-alerts" terraform docs/deployment-model.md
./scripts/test-security.sh
./scripts/verify-all.sh
```

Run `terraform init -backend=false` and `terraform validate` in each root module/example when Terraform files exist. Run configured lint/security tools and generate a saved plan only for a synthetic or approved non-production environment. Inspect plan JSON for public exposure, broad IAM actions/resources, plaintext secrets, unencrypted storage, mutable image tags, replacement/data-loss risk, and unexpected cost-bearing resources.

Missing validation, missing plan evidence for a claimed deployable example, parse errors, non-zero commands, or `NOT IMPLEMENTED` is `FAIL`. Documentation-only skeletons may be honestly marked not yet applicable but cannot pass a deployment-readiness claim.

## PASS/FAIL criteria

`PASS` requires formatting/validation/security checks, authoritative module/topology alignment, least-privilege IAM, internal-only observability endpoints, Secrets Manager references, encrypted S3-backed Loki storage with correct lifecycle, pinned artifacts, bounded resources, explicit non-production plan evidence, and a safe upgrade/rollback path.

`FAIL` on Kubernetes/Helm, a production action or credential, public/internal-backend exposure, open ingress without documented necessity, client-selected tenant routing, broad IAM, embedded secret, shared environment state, DEV real-partner connectivity, retention mismatch, destructive upgrade without rollback, or unreviewed material cost.

Never weaken policy checks, Terraform tests, plan assertions, security groups, IAM, encryption, retention, or acceptance criteria merely to obtain `PASS`. Correct the module or record the failure.

## Record valid findings

Set `.agent-state/status.json` to `VERIFYING` during review, then `READY_FOR_REVIEW` on a scoped pass or `IN_PROGRESS` on failure. Preserve existing fields and capture plan target/evidence in `summary`, remediation in `nextActions`, and genuine blockers in `blockers`. Update `PLANS.md` only for proven milestone progress and put unresolved cloud choices in `docs/decisions-needed.md`. Never mark production deployed.
