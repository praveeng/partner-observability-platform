# Validate STAGE and PROD readiness

## Mode

ASSESSMENT ONLY. Run after service configuration, Gradle composite integration, OpenAPI coverage, GHA integration, and centralized Terraform code integration, but before any infrastructure execution or deployment. Do not modify files, commit, push, deploy, access AWS, execute Terraform, or contact a real partner.

## Objective

In the enterprise workspace, set `TARGET_PARTNER_SERVICE=sure-nbfc-unionbank-ph` for the pilot and independently determine whether that exact target and Sure Partner Observability are ready for human-controlled STAGE and PROD rollout. Resolve it through optional `SUREWEBSERVICES_ROOT`; fail for an unset, invalid, wildcard, inferred, or absent target. Inspect SureWebServices shared configuration, `sure-partner-observability`, only the selected target, and the centralized Terraform repository. Do not inspect unrelated `sure-nbfc-*` services. Read all repository instructions and current evidence; do not treat an earlier report as proof.

## Fixed lifecycle model

- `local`: local VM/Docker with LocalStack/Testcontainers where needed and a mock partner; no AWS or enterprise Terraform dependency.
- `dev`: isolated AWS DEV ECS cluster/VPC and a mock partner; no real partner STAGE/PROD route. DEV remains unchanged by this rollout.
- `stage`: isolated AWS STAGE ECS cluster/VPC and the real partner staging environment.
- `prod`: AWS production and the real partner production environment.

DEV and STAGE may share the PH market account, but clusters, VPCs, resources, configuration, tenants, routes, endpoints, and secrets must be distinct. Spring configuration is properties-only through `application.properties`, `application-local.properties`, `application-dev.properties`, `application-stage.properties`, and `application-prod.properties`; activation is external. Active Spring YAML or aliases such as `staging`/`production` are failures.

## Required review

Confirm LOCAL remains functional and self-contained, and DEV retains AWS/mock-partner semantics. Before issuing `STAGE_READY`, require both (a) the generic `sure-partner-observability-test-app` B001/B002 local application-to-platform E2E and relevant B003 status/evidence, and (b) the selected real target-service local application-to-platform E2E using `SPRING_PROFILES_ACTIVE=local`, target-derived fixtures, local mock partner, real callback path, authorized queries, and no AWS/real partner access. A passing real-service test does not replace generic evidence, and generic evidence does not replace the real-service test. Confirm STAGE and PROD use the same application architecture/artifacts with environment-specific values rather than forks.

For STAGE and PROD inspect and trace:

- profile activation and configuration binding;
- service name, market, trusted partner identity, API/callback mappings, actual OpenAPI operation coverage, capture modes, correlation extractors, and callback lifecycle semantics;
- selected-target fixture preparation/coverage evidence and proof that no unselected service was inspected or tested;
- partner API/callback endpoints, HTTPS-only enforcement, standard certificate/hostname validation, redirect behavior, callback ALB trust boundary, and absence of trust-all/permissive TLS code;
- runtime-only partner credentials, source/datasource secrets, certificates, secret references, and absence of committed values;
- one opaque Loki tenant and bounded Prometheus slot per partner, source authorization, gateway-fixed query scope, Grafana organization/datasource isolation, and same-ID collision denial;
- private Alloy, Loki, Prometheus, query-gateway, and Grafana target connectivity;
- Loki S3/KMS/EFS, 384-hour partner-visible retention, two-hour deletion delay, 18-day lifecycle backstop, versioning policy, and Prometheus 16-day retention;
- central Terraform inputs/outputs, immutable image/config digests, IAM, SGs, ALB/ACM/DNS/WAF, health checks, backups, cost/retention, rollback, and replacement risk;
- GHA clean-checkout Gradle composite build, tests, OpenAPI coverage, image/runtime rollout, application-owned Alloy/Loki/Prometheus configuration, Grafana dashboards/alerts, and post-deployment gates;
- B001, B002, and B003 evidence without treating smoke tests as full-duration B003 closure.

Verify the deployment boundary and order:

```text
central Terraform code
  -> human review
  -> manual Terraform execution
  -> approved non-secret outputs and base health
  -> SureWebServices GHA
  -> service/runtime configuration
  -> dashboards and alerts
  -> post-deployment validation
```

GHA must not run Terraform. Terraform must not own application Grafana dashboards/alerts or Partner Observability processing policy. Do not require a database/Liquibase unless current source proves a real schema exists; current Partner Observability architecture requires no database.

## Output

Produce a requirement-to-evidence matrix with `PASS`, `FAIL`, `NOT_APPLICABLE`, or `MISSING_EVIDENCE`, exact file/line or artifact source, environment, owner, and remediation. Then issue exactly:

- `STAGE_READY` or `STAGE_NOT_READY`
- `PROD_READY` or `PROD_NOT_READY`

List exact blockers, owners, prerequisite order, and evidence needed to close each. PROD cannot be ready without successful STAGE evidence and production approvals. Do not change or deploy anything.
