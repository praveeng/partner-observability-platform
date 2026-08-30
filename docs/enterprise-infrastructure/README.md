# Enterprise Infrastructure Requirements Contract

## Authority and purpose

This directory is the application-owned contract for infrastructure that must already exist before
Sure Partner Observability is deployed to AWS STAGE or PROD. The centralized enterprise Terraform
repository owns all AWS Terraform implementation, state, planning, and execution. This repository
does not own enterprise Terraform and must not generate, plan, apply, import, or destroy AWS
infrastructure.

The contract is implementation-neutral. A future integration engineer must inspect the centralized
repository first and reuse its established modules, naming, tagging, account, state, approval, and
deployment patterns. Nothing here authorizes an AWS action.

## Scope

| Environment intent | Infrastructure change from this contract |
| --- | --- |
| LOCAL | None. Existing Docker Compose and synthetic workflows remain authoritative. |
| DEV | None. Existing AWS mock-partner behavior remains unchanged. |
| STAGE | Central enterprise Terraform must satisfy this contract. |
| PROD | Central enterprise Terraform must satisfy this contract with production approvals and sizing. |

The runtime environment vocabulary implemented by the SDK is `DEV`, `STAGE`, and `PROD`. There are
no Spring profiles in the repository. See [profile-model.md](profile-model.md) for the exact
discovery result and the separate LOCAL execution convention.

## Ownership summary

The centralized Terraform repository owns AWS base infrastructure: ECS integration and services,
VPC/subnets/security groups, load balancers and target groups, ACM/DNS/WAF hooks, IAM, encrypted
storage, secrets infrastructure, service discovery, base component runtimes, infrastructure health
checks, and bounded infrastructure logging.

Sure Partner Observability owns Java libraries, the starter and auto-configuration, telemetry and
sanitization policy, application-level Alloy/Loki/Prometheus configuration, Grafana dashboards and
alerts, tenant/query semantics, local development, tests, versioned runtime artifacts, and the
requirements in this directory. GitHub Actions may deploy those application-owned artifacts only
after base infrastructure exists.

## Contract index

- [requirements.md](requirements.md): complete cross-component requirements and required outputs
- [profile-model.md](profile-model.md): discovered profiles and LOCAL/DEV/STAGE/PROD intent
- [ecs-requirements.md](ecs-requirements.md): services, task definitions, images, storage, and health
- [network-requirements.md](network-requirements.md): VPC, private connectivity, ingress, DNS, and SGs
- [security-requirements.md](security-requirements.md): TLS, encryption, isolation, WAF, and logging
- [iam-requirements.md](iam-requirements.md): execution/task roles and least privilege
- [loki-s3-requirements.md](loki-s3-requirements.md): Loki infrastructure and 16-day policy boundary
- [alloy-requirements.md](alloy-requirements.md): base runtime versus application pipeline ownership
- [prometheus-requirements.md](prometheus-requirements.md): base metrics runtime and application rules
- [grafana-base-requirements.md](grafana-base-requirements.md): base Grafana infrastructure
- [grafana-deployment-boundary.md](grafana-deployment-boundary.md): dashboards/alerts and provisioning
- [github-actions-contract.md](github-actions-contract.md): post-infrastructure deployment sequence
- [terraform-integration-guide.md](terraform-integration-guide.md): instructions for the central repo
- [stage-requirements.md](stage-requirements.md) and [prod-requirements.md](prod-requirements.md)
- [infrastructure-contract.yaml](infrastructure-contract.yaml): machine-readable input/output inventory
- [legacy-terraform-classification.md](legacy-terraform-classification.md): retired repository assets

Normative security, payload, isolation, telemetry, and transport requirements remain in their
existing documents. This contract changes ownership, not those controls.
