# M8 Terraform/ECS evidence

Date: 2026-08-23

Scope: reusable Terraform for one Partner Observability deployment in an existing AWS ECS market cluster, plus synthetic DEV/STAGE/PROD composition examples. No AWS account, credentials, backend state, real-account plan, apply, import, destroy, or deployment was used.

## Requirement trace

| Boundary | Implemented evidence |
| --- | --- |
| Existing market infrastructure | `market-observability-stack` takes the VPC, private/public subnets, ECS cluster ARN/name, source-service and endpoint security groups, S3 prefix list, ACM ARNs, WAF ARN, DNS inputs, and immutable image/config references. It does not create the market cluster or callback services. |
| External Grafana | `observability-network` creates one internet-facing ALB with one HTTPS 443 listener, an ACM certificate, a configurable approved TLS policy, mandatory regional WAF association, approved CIDRs, and no HTTP listener. Grafana tasks have no public IP and accept traffic only from the ALB security group. |
| Private backends | Alloy has a private TLS NLB. Loki and Prometheus accept only Alloy/query-gateway security groups. Grafana reaches data only through the query gateway. No public listener exists for Alloy, Loki, Prometheus, or the gateway. |
| ECS safety | All tasks use private subnets and `assign_public_ip = false`, immutable digest images, read-only root filesystems, non-root users, health checks, bounded CPU/memory, and conservative CloudWatch log retention. Loki, Prometheus, and Grafana remain fixed single-task services; only stateless Alloy/query gateway have bounded CPU target tracking. |
| Identity and secrets | Each service has a distinct task role and exact execution-role grants for named ECR repositories, configuration objects, secret/parameter ARNs, and KMS keys. No secret value, certificate body, private key, or credential is accepted or output. |
| State and retention | Loki uses encrypted S3 and EFS, compactor retention of 384 hours, a two-hour deletion delay, disabled object versioning, and an 18-day S3 lifecycle backstop. Prometheus uses encrypted EFS with 16-day and bounded size retention. Grafana uses encrypted EFS and a seven-day backup lifecycle. |
| Callback boundary | Existing partner-facing services retain their HTTPS ALB/ACM/private-target callback path. Partner onboarding records evidence of that listener and trusted authentication adapter. Observability creates no callback listener and the SDK does not alter TLS termination. |
| Partner onboarding | The environment-neutral partner map binds a synthetic key to a unique Loki tenant, bounded Prometheus slot, Grafana organization, exact secret ARNs, and callback-ingress evidence. DEV rejects non-mock routes; PROD requires explicit external enablement and a change reference. No Java or dashboard source edit is required. |
| Reversibility | Configuration artifacts and images are immutable and digest pinned. Stateful services stay single-writer, encrypted storage is external to task replacement, S3 telemetry has no versioning, and changes must be rolled forward/back by restoring the prior task/config digest. Real-market replacement and restore impact must be reviewed in an external saved plan before deployment. |

## Local verification

The approved local tool was HashiCorp Terraform 1.11.4 with AWS provider 6.61.0 from the local plugin cache.

```text
terraform fmt -check -recursive terraform
PASS

test/terraform/static-policy.sh
PASS: HTTPS, private-task, storage, IAM, secret-reference, and onboarding policies

terraform init -backend=false + terraform validate (all modules and DEV/STAGE/PROD roots)
Success: every configuration is valid

terraform test (observability-network; mock_provider aws; command = plan)
Success: 1 passed, 0 failed

scripts/test-terraform.sh
PASS

scripts/test-security.sh --terraform
PASS: M8 configuration scope

scripts/test.sh
PASS
```

`scripts/test-security.sh` and `scripts/verify-all.sh` intentionally remain non-zero because the M7 Grafana/query authorization attack suite and M9 deployed-runtime/performance gates report `NOT IMPLEMENTED`. This is an honesty gate, not an M8 Terraform regression. TFLint, Checkov, and Trivy were not installed locally.

## Required skill verdicts

### terraform-ecs-review

**PASS for the reusable-module and fully local/mock M8 configuration scope. Not a deployment-readiness or real-environment plan verdict.** Module ownership, listener and network paths, IAM, secret references, encrypted storage, retention, pinned artifacts, bounded resources, environment guards, and rollback inputs match the repository contracts. The static policies, provider-schema validation, and mocked network plan are green. Before any real market rollout, the owning team must resolve the open market inputs, review a saved non-production plan, confirm replacement/cost impact, and exercise restore/certificate-rotation procedures. Production enablement remains external and off by default.

### partner-security

**PASS for the M8 infrastructure exposure boundary; FAIL/not implemented for a whole-platform partner-security claim.** The module exposes only WAF-protected Grafana HTTPS, keeps all task IPs and backends private, restricts SG paths to trusted components, and represents partner mappings as operator configuration rather than request input. Existing M5 tests cover Loki tenant ingest/query isolation and spoofed routing. The mandatory M7 public UI/query attack matrix, account-to-org/session tests, audit decisions, and deployed direct-reachability attempts do not yet exist, so no end-to-end partner-security PASS is claimed. Grafana/query access must remain unavailable to partners until M7 supplies and passes those tests.

## Deferred external evidence

- Resolve the accountable market/account/DNS/allowlist/WAF/secret-owner inputs recorded in `docs/decisions-needed.md`.
- Generate and inspect a saved plan only in an approved synthetic or non-production workflow with the intended market provider/backend controls.
- Verify runtime reachability from the Grafana ALB, existing service security groups, and denied public/direct-backend paths.
- Exercise ACM rotation, S3/EFS/Grafana restoration, configuration rollback, and alarm routing before production readiness.
- Complete M7 partner query authorization and M9 performance/deployed security gates.
