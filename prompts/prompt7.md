# Integrate SureWebServices GitHub Actions

## Mode

APPROVAL-GATED IMPLEMENTATION. Inspect and propose first; modify workflows only after explicit approval. Do not run a deployment, access AWS, change centralized Terraform, run Terraform, publish a release, or expose secrets.

## Objective

Integrate the pilot and Partner Observability application assets into the existing SureWebServices GitHub Actions model. Set `TARGET_PARTNER_SERVICE=sure-nbfc-unionbank-ph` for the pilot and apply service-specific workflow changes only to that exact target. Resolve it through optional `SUREWEBSERVICES_ROOT`; reject wildcard, multiple, inferred, or absent targets. Reuse enterprise workflows, reusable actions, environments, credentials, change controls, and deployment conventions. Do not enumerate or mass-modify other `sure-nbfc-*` services, and do not invent a parallel CI/CD framework.

Read SureWebServices, platform, and selected-target `AGENTS.md` files. Inspect root/shared `.github/workflows`, shared/composite actions, selected-target workflow references, environment mappings, Gradle caches/checkouts, ECS deployment workflows, image publishing, runtime configuration rollout, observability asset deployment, and post-deployment checks. Reuse convention sources referenced by shared configuration without treating another partner service as an integration target. If workflows are stored elsewhere in the monorepo, discover them from repository configuration rather than fabricating files.

## Mandatory release boundary

The selected target consumes `sure-partner-observability-spring-boot-starter` directly from source through the established Gradle composite build. A clean runner must check out or otherwise include `sure-partner-observability` and only the selected target for the pilot build without an artifact repository or copied JAR. Public SDK imports remain `com.samsung.sure.partner.observability.*`.

Canonical Spring profiles are exactly `local`, `dev`, `stage`, and `prod`, with `.properties` application configuration only. Map enterprise development deployment to `dev`, staging deployment to `stage`, and production deployment to `prod`. LOCAL tests explicitly use `local`. DEV is AWS plus a mock partner and remains isolated from STAGE even if both use the same market account. STAGE uses partner staging; PROD uses partner production. Profile activation is supplied externally and production is never the packaged default.

Enterprise Terraform is separate, manually reviewed, and manually executed in the centralized Terraform repository. No SureWebServices workflow may run `terraform init`, `plan`, `apply`, `destroy`, import, or infrastructure creation. Deployment must fail closed if the approved infrastructure change reference and required non-secret outputs/base health are absent.

After base infrastructure exists, GHA may follow existing conventions for:

- composite source build, unit and integration tests;
- security, profile, naming, exact-target OpenAPI fixture preparation, OpenAPI-to-observability coverage, and dependency checks;
- container build, scan, provenance, immutable digest publication, and ECS application/runtime update;
- application-owned Alloy pipeline, Loki runtime policy, Prometheus rules/configuration, query-gateway configuration, and market manifest rollout;
- `grafana/dashboards/` and `grafana/alerts/` deployment after base Grafana is healthy;
- post-deployment health, tenant isolation, configuration digest, and rollback verification;
- Liquibase only if repository inspection proves a real application database/schema. Current Sure Partner Observability architecture requires no database, so do not add a platform Liquibase step.

Terraform owns base ECS/network/VPC/security groups/ALB/ACM/DNS/WAF/IAM/S3/KMS/EFS/secrets/runtime infrastructure for Grafana, Loki, Alloy, and Prometheus. GHA owns only application/software assets and approved ECS rollout surfaces. Secret values remain in approved runtime stores and must not appear in workflow inputs, outputs, logs, artifacts, caches, or Terraform outputs.

## Approval checkpoint

Before editing, present:

1. Existing workflow/reusable-action patterns selected and why.
2. Exact workflow/action/files proposed.
3. Clean-checkout composite-build path.
4. Test and artifact dependency graph.
5. `local`/`dev`/`stage`/`prod` activation mapping.
6. Infrastructure prerequisites and non-secret outputs consumed.
7. Dashboard, alert, Prometheus, Alloy/Loki, and post-deployment ownership.
8. Environment protection, approvals, secret handling, rollback, and risks.
9. Confirmation that no Terraform command or production deployment will run during implementation.

Stop and ask for explicit approval.

After approval, implement the minimum changes, validate workflow syntax and repository-standard static checks, and exercise non-deploy build/test paths where safe. Do not trigger deployment workflows. Create one coherent local commit, do not push, and report exact changes, validations, unresolved enterprise inputs, and commit hash.
