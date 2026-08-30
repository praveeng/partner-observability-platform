# SureWebServices Partner Observability prompt pack

This directory is a durable, ordered Codex runbook for piloting Sure Partner Observability in `sure-nbfc-unionbank-ph`, integrating separately owned STAGE/PROD infrastructure, validating the lifecycle, and planning rollout to additional `sure-nbfc-*` services. Each prompt is self-contained and must run in a fresh Codex session from the enterprise SureWebServices workspace. Do not execute several prompts in one session merely to bypass their human gates.

The fixed runtime model is:

| Profile | Runtime | Partner |
| --- | --- | --- |
| `local` | Local VM, Docker, and LocalStack/Testcontainers where needed | Local/mock |
| `dev` | Dedicated AWS DEV ECS cluster and VPC | Mock |
| `stage` | Dedicated AWS STAGE ECS cluster and VPC | Real partner staging |
| `prod` | AWS production | Real partner production |

DEV and STAGE may share a market AWS account, but the standard requires separate DEV/STAGE
clusters, separate DEV/STAGE VPCs, resources, configuration, endpoints, and secrets. Spring
application configuration is `.properties` only. Partner services consume
`sure-partner-observability-spring-boot-starter` from source through Gradle composite build and
import `com.samsung.sure.partner.observability.*`.

## Execution sequence

1. [prompt1.md](prompt1.md) — compatibility assessment.
2. [prompt2.md](prompt2.md) — approved generic platform gaps. Run only when prompt 1 identifies `Gxxx` gaps and a human inserts the approved IDs.
3. [prompt3.md](prompt3.md) — pilot service configuration and four-profile integration.
4. [prompt4.md](prompt4.md) — Gradle composite starter integration.
5. [prompt5.md](prompt5.md) — permanent OpenAPI-to-observability coverage gate.
6. [prompt6.md](prompt6.md) — SureWebServices GHA integration.
7. [prompt7.md](prompt7.md) — local real application-to-platform end-to-end validation.
8. [prompt8.md](prompt8.md) — centralized Terraform infrastructure integration. This is the former Prompt 13B step.
9. [prompt9.md](prompt9.md) — STAGE/PROD readiness assessment.
10. [prompt10.md](prompt10.md) — independent security and regression review.
11. [prompt11.md](prompt11.md) — rollout assessment for remaining `sure-nbfc-*` services.

Do not skip a failed stage. Re-run an earlier stage when subsequent repository changes invalidate its evidence.

## Human gates

| Prompt | Gate |
| --- | --- |
| 1 | Assessment only; human selects approved `Gxxx` and `Sxxx` scope. |
| 2 | Show generic SDK files/design and obtain approval before implementation. |
| 3 | Show exact pilot configuration/adapters and obtain approval before implementation. |
| 4 | Show exact composite-build/service changes and obtain approval before implementation. |
| 5 | May implement the quality gate; stop for ambiguous contract, generated-code, or security decisions. |
| 6 | Show exact GHA changes and obtain approval before modifying workflows. |
| 7 | Local tests are allowed; ask before material business-service changes. |
| 8 | Mandatory Terraform assessment and explicit approval before modification; separate approval for any destructive/replacement change or plan. |
| 9 | Assessment only. |
| 10 | Review and regression-first defect fixes; ask before material redesign. |
| 11 | Assessment only; no mass rollout modifications. |

Assessment-only prompts are 1, 9, and 11. Prompts 2, 3, 4, 6, and 8 are approval-gated modification prompts. Prompt 5 implements a quality gate within its stated ambiguity boundary. Prompt 7 performs local validation. Prompt 10 performs adversarial review and only narrowly scoped regression-first fixes.

## Infrastructure and deployment order

Central Terraform owns base ECS/network/VPC/SG/ALB/ACM/DNS/WAF/IAM/S3/KMS/EFS/secrets and base Grafana/Loki/Alloy/Prometheus runtime. Sure Partner Observability and SureWebServices GHA retain application pipeline/configuration, Grafana dashboards and alerts, Prometheus rules, tests, and service deployment. GHA never runs enterprise Terraform.

After code readiness, the enterprise sequence is:

```text
prompt8-approved Terraform code
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

No prompt authorizes automatic production deployment or `terraform apply` by Codex. LOCAL remains independent and DEV remains unchanged by the STAGE/PROD infrastructure integration.

## Completion gates

The sequence preserves B001 Grafana completion, B002 real application-to-platform end-to-end evidence, and B003 full-duration performance. Local smoke or shortened performance checks never close B003. Every prompt uses synthetic test data and preserves business availability, server-side partner isolation, HTTPS/TLS, secret removal, PII masking, and pre-queue Base64/binary omission.
