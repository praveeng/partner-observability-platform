# ADR 0013: Enterprise infrastructure ownership boundary

- Status: Accepted
- Date: 2026-08-30
- Decision owners: Enterprise cloud platform and Partner Observability release architecture

## Context

ADR 0007 established the required AWS ECS topology and this repository previously implemented
reusable Terraform modules/examples to validate it. Enterprise AWS infrastructure is actually
maintained in a separate centralized Terraform repository. Keeping deployable AWS Terraform,
provider tests, or plan execution here creates split ownership and an incorrect CI/deployment
boundary.

## Decision

The centralized enterprise Terraform repository exclusively owns AWS Terraform code, state,
planning, review, and manual execution for Partner Observability. Sure Partner Observability owns an
implementation-neutral infrastructure requirements contract for STAGE and PROD, plus application
runtime/configuration assets and post-infrastructure deployment tests.

LOCAL remains Docker-based and independent. DEV remains the existing AWS mock-partner environment
with no new requirement from this decision. There are no Spring profiles; the runtime enum remains
`DEV`, `STAGE`, and `PROD`, and LOCAL continues to use the guarded `LOCAL_SYNTHETIC` convention.

The central repository brings up ECS base services, networking, TLS/ACM/DNS/WAF, IAM, S3/EFS,
secrets infrastructure, service discovery, health checks, and infrastructure logging. After a
human-reviewed/manual infrastructure execution, enterprise GHA may deploy immutable runtime images,
application-owned Alloy/Loki/Prometheus/Grafana/query configuration, dashboards, alerts, and rules.
No database or Liquibase migration is required by the current architecture.

Repository-owned enterprise `.tf` files, mocked provider plans, and Terraform CLI gates are
retired. `scripts/test-terraform.sh` becomes a non-Terraform compatibility wrapper for the contract
validator. The architecture/security requirements previously proven by M8 remain requirements for
the central implementation; the historical evidence is not rewritten as an AWS deployment claim.

## Consequences

- One authoritative Terraform implementation/state owner replaces split ownership.
- This repository cannot independently validate a real infrastructure plan; the central change must
  return reviewed plan and output evidence before application deployment.
- LOCAL and DEV workflows gain no central-Terraform prerequisite.
- STAGE/PROD application deployment fails closed when required infrastructure outputs/health are
  absent, without affecting partner-service business availability.
- New central modules are not prescribed. Integration first reuses established enterprise patterns.

## Supersession

This ADR supersedes ADR 0007 only where it assigned Terraform module/example implementation and
validation to this repository. ADR 0007's ECS topology, isolation, retention, security, and
cost-conscious design remain requirements. ADRs 0005, 0008, and 0011 retain their runtime and
security decisions with central Terraform as the infrastructure implementation owner.
