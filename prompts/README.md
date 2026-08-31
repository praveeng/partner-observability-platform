# SureWebServices Partner Observability prompt pack

This directory is a durable, ordered Codex runbook for piloting Sure Partner Observability with one explicitly selected partner service inside the enterprise SureWebServices workspace. The pilot is `sure-nbfc-unionbank-ph`; the same sequence is reusable for later `sure-nbfc-*` services one target at a time.

Each prompt is self-contained and must run in a fresh Codex session. Do not execute several prompts in one session to bypass a human gate.

## Exact target-service policy

The canonical input for every service-specific stage is:

```text
TARGET_PARTNER_SERVICE=sure-nbfc-unionbank-ph
```

The optional workspace-root input is:

```text
SUREWEBSERVICES_ROOT=/path/to/SureWebServices
```

Do not hard-code an absolute workspace path into repository files. If `SUREWEBSERVICES_ROOT` is not supplied, a prompt may use the unambiguous repository parent only when it clearly contains the exact target.

The target rule is strict:

- exactly one literal `TARGET_PARTNER_SERVICE` is allowed;
- only that exact service may be inspected for service-specific integration, parsed for OpenAPI, built, started, tested, or used for target-derived fixtures;
- every other `sure-nbfc-*` service is ignored during prompts 1–11;
- no prompt may select the first match, accept a wildcard/list, or silently fall back to another service;
- a missing or invalid target fails clearly;
- prompt 12 is the only broad service-discovery stage, and it is assessment-only: it does not build, start, test, or modify the discovered services.

Future services run the same sequence with one new literal `TARGET_PARTNER_SERVICE` value. They are never integrated as a batch.

## Two local testing layers

Both layers are required before STAGE readiness:

| Mode | Application | Responsibilities |
| --- | --- | --- |
| **GENERIC** | `sure-partner-observability-test-app` | B001, generic B002, B003, full-duration performance, queue saturation, hostile/artificial payloads, sanitizer and dependency failures, reactive stress, binary omission, and tenant-collision security tests |
| **TARGET-SERVICE** | exactly `TARGET_PARTNER_SERVICE` | real OpenAPI compatibility, actual HTTP-client instrumentation, callback architecture, correlation fields, encryption hooks, and real application-to-platform integration against local mocks |

Target-derived coverage enriches the generic test application with data-driven, synthetic interaction fixtures. It does not replace the generic application or B001/B002/B003. Preparation is deterministic and pre-test; runtime self-modifying Java is prohibited. Generated manifests stay separated by target service, and generic Java capabilities stay partner-neutral.

## Fixed profile and build model

| Profile | Runtime | Partner |
| --- | --- | --- |
| `local` | Local VM, Docker, and LocalStack/Testcontainers where needed | Local/mock |
| `dev` | Dedicated AWS DEV ECS cluster and VPC | Mock |
| `stage` | Dedicated AWS STAGE ECS cluster and VPC | Real partner staging |
| `prod` | AWS production | Real partner production |

DEV and STAGE may share a market AWS account, but they use separate clusters, VPCs, resources, configuration, endpoints, and secrets. Spring application configuration is `.properties` only:

- `application.properties`
- `application-local.properties`
- `application-dev.properties`
- `application-stage.properties`
- `application-prod.properties`

Real target-service local validation always sets `SPRING_PROFILES_ACTIVE=local` and must not access AWS, DEV, STAGE, PROD, a real partner endpoint, or real credentials. Partner services consume `sure-partner-observability-spring-boot-starter` from source through Gradle composite build and import `com.samsung.sure.partner.observability.*`.

## Execution sequence

1. [prompt1.md](prompt1.md) — assess compatibility for the exact selected target.
2. [prompt2.md](prompt2.md) — implement approved generic platform gaps; run only when prompt 1 identifies approved `Gxxx` gaps.
3. [prompt3.md](prompt3.md) — configure the selected target and its four profiles.
4. [prompt4.md](prompt4.md) — integrate the selected target through Gradle composite source dependency.
5. [prompt5.md](prompt5.md) — prepare selected-target OpenAPI fixtures and enrich the generic test application.
6. [prompt6.md](prompt6.md) — create the selected-target OpenAPI-to-observability coverage gate.
7. [prompt7.md](prompt7.md) — integrate the platform and selected target with SureWebServices GHA.
8. [prompt8.md](prompt8.md) — run the real selected-service local application-to-platform E2E while preserving the separate generic E2E.
9. [prompt9.md](prompt9.md) — integrate centralized Terraform infrastructure. This is the former Prompt 13B functionality.
10. [prompt10.md](prompt10.md) — assess STAGE/PROD readiness, requiring both generic and selected-target local E2E evidence.
11. [prompt11.md](prompt11.md) — independently review generic-platform and selected-target security/regression behavior.
12. [prompt12.md](prompt12.md) — assess rollout candidates across remaining `sure-nbfc-*` services without executing or modifying them.

Do not skip a failed stage. Re-run an earlier stage when later repository changes invalidate its evidence.

## Human gates

| Prompt | Gate |
| --- | --- |
| 1 | Assessment only; human selects approved `Gxxx` and `Sxxx` scope. |
| 2 | Show generic platform files/design and obtain approval before implementation. |
| 3 | Show exact selected-target configuration/adapters and obtain approval before implementation. |
| 4 | Show exact selected-target composite-build changes and obtain approval before implementation. |
| 5 | May generate deterministic fixtures; show every substantial generic Java capability gap and obtain approval before that enhancement. |
| 6 | May implement the exact-target quality gate; stop for ambiguous contract, generated-code, or security decisions. |
| 7 | Show exact GHA changes and obtain approval before modifying workflows. |
| 8 | Local tests are allowed; ask before a material business-service change. |
| 9 | Mandatory Terraform assessment and explicit approval before modification; separate approval for destructive/replacement change or plan. |
| 10 | Assessment only. |
| 11 | Review and regression-first fixes; ask before material redesign. |
| 12 | Assessment only; broad discovery is permitted, but no mass service execution or modification. |

Assessment-only prompts are 1, 10, and 12. Approval-gated modification prompts are 2, 3, 4, 7, and 9. Prompt 5 has a mandatory approval boundary for substantial generic Java changes. Prompt 6 implements a quality gate within its ambiguity boundary. Prompt 8 performs local validation. Prompt 11 performs adversarial review with only narrowly scoped regression-first fixes.

## Infrastructure and deployment order

Central Terraform owns base ECS/network/VPC/security-group/ALB/ACM/DNS/WAF/IAM/S3/KMS/EFS/secrets and base Grafana/Loki/Alloy/Prometheus runtime. Sure Partner Observability and SureWebServices GHA retain application pipeline/configuration, Grafana dashboards and alerts, Prometheus rules, tests, and service deployment. GHA never runs enterprise Terraform.

After code readiness, the enterprise sequence is:

```text
prompt9-approved Terraform code
  -> human infrastructure review
  -> manual terraform execution
  -> base STAGE/PROD infrastructure
  -> SureWebServices GHA
  -> service/runtime deployment
  -> Grafana dashboards
  -> Grafana alerts
  -> application-owned observability configuration
  -> post-deployment validation
```

No prompt authorizes automatic production deployment or `terraform apply` by Codex. LOCAL has no enterprise Terraform dependency. DEV remains unchanged by default. STAGE and PROD infrastructure changes occur only in prompt 9 after approval.

## Completion and security gates

The sequence preserves B001 Grafana completion, B002 generic application-to-platform E2E, the additional real selected-service local E2E, and B003 full-duration performance. Local smoke or shortened performance checks never close B003.

Every prompt uses synthetic test data and preserves business availability, server-side partner isolation, one Loki tenant per partner, HTTPS/TLS, secret removal, PII masking, and pre-queue Base64/binary omission. Real partner callbacks and APIs are never contacted during local validation.
