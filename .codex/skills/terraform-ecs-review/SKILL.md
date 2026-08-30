---
name: terraform-ecs-review
description: Review the Partner Observability enterprise infrastructure requirements, AWS ECS topology, and centralized Terraform evidence without generating or executing Terraform in this repository. Use for ECS task/service, networking, IAM, S3, secrets, environment topology, enterprise Terraform integration, upgrade, or market onboarding changes.
---

# Enterprise Infrastructure and ECS Review

Review the application-to-central-infrastructure contract. Never generate or execute enterprise
Terraform in this repository, access AWS, deploy, or use production credentials.

## Load authoritative requirements

Read `AGENTS.md`, `PLANS.md`, `.agent-state/status.json`, every document in
`docs/enterprise-infrastructure/`, `docs/architecture.md`, `docs/security-invariants.md`,
`docs/threat-model.md`, `docs/partner-isolation.md`, `docs/deployment-model.md`,
`docs/acceptance-criteria.md`, ADR 0005, ADR 0007, ADR 0008, ADR 0013, and applicable unresolved
decisions. Derive environments, capability boundaries, ports, retention, sizing, IAM, secrets,
outputs, and rollout rules from those sources.

## Review workflow

1. Confirm the target repository. This repository may change only requirements, application/runtime
   assets, tests, and the GHA integration contract. Terraform implementation review belongs in the
   separate centralized repository.
2. Trace each required ECS service, listener, security-group edge, IAM role, secret reference, log
   path, persistent store, service-discovery endpoint, health check, and deployment output.
3. Prove the reusable one-deployment-per-market/environment model and multi-partner isolation.
   Enterprise integration applies only to STAGE and PROD. LOCAL and DEV remain unchanged.
4. Prove observability services cannot become synchronous health or deployment dependencies for
   partner business traffic.
5. Review capacity, scaling, Loki retention, encryption, backup assumptions, upgrade order,
   rollback, auditability, cost controls, and GHA handoff without prescribing new enterprise module
   names or organization-specific implementation.
6. Require reviewed central Terraform plan/policy and staging evidence for any infrastructure claim.
   Absence of that external evidence is not repaired by creating Terraform here.

## Deterministic checks

```bash
git diff --check
./scripts/test-enterprise-infrastructure-contract.sh
rg -n -i "kubernetes|k8s|helm" docker docs/enterprise-infrastructure --glob '!*.md'
rg -n -i "0\.0\.0\.0/0|::/0|publicly_accessible|password|secret|token|access_key" docs/enterprise-infrastructure
rg -n -i "latest|:latest" docs/enterprise-infrastructure docker
./scripts/test-security.sh
./scripts/verify-all.sh
```

Do not run Terraform commands from this repository. Inspect central plan JSON only when the user
provides it or the future task is explicitly scoped to the centralized repository. Review such
evidence for public exposure, broad IAM, plaintext secrets, unencrypted storage, mutable image
tags, replacement/data-loss risk, and unexpected cost-bearing resources.

Missing required contract fields, repository-owned Terraform artifacts, missing central evidence
for a claimed deployable environment, parse errors, non-zero commands, or `NOT IMPLEMENTED` is
`FAIL`. A requirements-only contract can pass its scoped gate but cannot pass a central
implementation or deployment-readiness claim.

## PASS/FAIL criteria

`PASS` for this repository requires centralized ownership, complete STAGE/PROD capability and
input/output contracts, LOCAL/DEV independence, least-privilege requirements, internal-only
backends, secret references, encrypted Loki storage with correct retention, pinned artifact
interfaces, bounded resources, explicit human review/manual infrastructure execution, and safe
upgrade/rollback requirements.

`FAIL` on repository-owned enterprise Terraform, Terraform/AWS execution here, Kubernetes/Helm,
public internal-backend exposure, client-selected tenant routing, broad IAM requirements, embedded
secrets, shared market/environment state, new DEV requirements, retention mismatch, destructive
upgrade without rollback, or unreviewed material cost.

Never weaken contract checks, central evidence requirements, security groups, IAM, encryption,
retention, or acceptance criteria merely to obtain `PASS`.

## Record valid findings

Set `.agent-state/status.json` to `VERIFYING` during review, then `READY_FOR_REVIEW` on a scoped pass
or `IN_PROGRESS` on failure. Preserve existing fields. Record external implementation evidence and
remediation precisely, update `PLANS.md` only for proven progress, and put unresolved cloud choices
in `docs/decisions-needed.md`. Never mark central infrastructure implemented or deployed without
direct evidence.
