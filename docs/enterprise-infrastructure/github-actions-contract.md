# GitHub Actions Integration Contract

## Required sequence

1. The centralized enterprise Terraform repository is changed for STAGE or PROD.
2. A human reviews the Terraform change, plan, isolation, cost, and replacement impact.
3. An authorized human-controlled workflow manually executes Terraform in the central repository.
4. Base infrastructure becomes healthy and publishes the approved non-secret outputs/change
   reference.
5. Sure Partner Observability GitHub Actions starts only after that prerequisite is satisfied.
6. GHA performs the established application release activities and post-deployment validation.

This repository and its GHA must not plan/apply Terraform or compensate for missing base
infrastructure by creating AWS resources.

## Application release responsibilities

Subject to the existing enterprise CI/CD conventions, GHA may perform:

- build, unit/integration/security validation, packaging, provenance, and image scanning;
- immutable container image publication and ECS runtime/task update through the approved interface;
- rollout of application-owned Alloy, Loki, Prometheus, query-gateway, and Grafana configuration;
- deployment of `grafana/dashboards/` and `grafana/alerts/`;
- deployment of application-owned Prometheus recording/alert rules;
- post-deployment health, routing, isolation, dashboard, and rollback validation;
- Liquibase only if a future approved application database/schema genuinely exists.

**NO DATABASE REQUIRED FOR CURRENT ARCHITECTURE**, so current GHA has no Liquibase action for this
platform.

## Inputs consumed from infrastructure

GHA consumes only approved non-secret outputs from `requirements.md`: target environment and
cluster/services, deployment endpoints, artifact destinations, role/secret/config reference ARNs,
security-group/service-discovery identifiers where needed, health URLs, and an infrastructure
version/change reference. Secret values stay in Secrets Manager/SSM and are resolved by task roles
or the approved deployment platform.

## Required controls

- environment protection, named approvers, and production change reference;
- STAGE promotion and validation before PROD;
- immutable image/config digests and identical application artifacts promoted between environments;
- no AWS credentials, partner data, secret values, or Terraform state committed or printed;
- fail closed if required infrastructure outputs or health are missing;
- rollback to prior application artifacts without destroying base infrastructure;
- no change to LOCAL or DEV prerequisites under this contract.

No `.github/workflows` directory currently exists in this repository. This document defines the
integration contract for the established enterprise release mechanism and deliberately does not
redesign or scaffold it.
