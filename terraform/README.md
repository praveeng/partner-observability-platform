# Terraform for an existing market ECS cluster

Terraform is the only supported provisioned-infrastructure path. The reusable modules consume an existing VPC, ECS cluster, private/public subnets, endpoint controls, and externally approved certificate/DNS/WAF inputs. They do not create a cluster, callback service/ALB, certificate, credential value, Terraform backend, or production deployment. Kubernetes and Helm are not supported.

## Required existing-stack inputs

The owning market Terraform stack supplies:

- VPC ID/CIDR and at least two private task subnets plus two public Grafana-ALB subnets;
- existing ECS cluster ARN and name, plus an optional approved capacity-provider strategy;
- source-service and private Actuator security-group IDs;
- the existing AWS interface-endpoint security group and S3 gateway-endpoint prefix-list ID, so tasks need no unrestricted egress;
- Grafana and private Alloy-ingress ACM certificate ARNs plus reviewed TLS policies;
- an approved Grafana IPv4/IPv6 allowlist, an approved regional WAF web ACL ARN, optional Route53 zone, and Grafana DNS name;
- immutable image references pinned by digest and exact ECR repository ARNs;
- versioned non-secret configuration artifact S3 URIs/object ARNs/SHA-256 digests;
- exact Secrets Manager/SSM ARNs, KMS key ARNs where customer-managed keys apply, an approved Grafana backup role ARN, and an optional alert SNS topic;
- a unique Loki bucket name, optional recovery role, resource sizing, conservative CloudWatch retention, and approved tags.

Certificates and private keys are never generated here. ACM certificate rotation changes an ARN/listener attachment without application-code changes; attach/validate the replacement before retiring a still-valid certificate. Certificate/private-key contents, URI credentials, passwords, tokens, and secret values are not Terraform inputs or outputs. Future mTLS can add reviewed certificate/authentication references through the manifest and ALB/service-owned trust boundaries, but this module does not implement mTLS.

## Network and transport boundaries

Grafana is the only public observability endpoint. Its internet-facing ALB has one `HTTPS :443` listener, an ACM certificate, a pinned TLS 1.2-or-newer policy, an approved CIDR allowlist, and a mandatory regional WAF association. There is deliberately no port-80 listener or rule. Grafana tasks are private and accept port 3000 only from the ALB security group.

Alloy uses an internal TLS NLB reachable only from explicitly onboarded service security groups. Loki and Prometheus accept traffic only from Alloy and the trusted query gateway. Grafana reaches data only through that gateway. ECS services use private subnets and `assign_public_ip = false`; AWS APIs/config artifacts are reached through supplied VPC endpoint controls. No security-group rule grants unrestricted internal ingress.

The ALB is the approved external TLS termination boundary. Private ALB/NLB target protocols and backend TLS material are image/config-artifact responsibilities under the market threat review; no module exposes a backend publicly or treats TLS as partner authentication.

## Existing callback ingress

Partner callback/webhook ALBs remain owned by each partner-facing service. Onboarding configuration records the owning listener/certificate evidence, private-target confirmation, and trusted authentication-adapter ID. The observability stack neither creates nor mutates callback listeners.

The required service-owned path is partner HTTPS -> ALB `:443` with ACM and approved TLS policy -> private service target SG accepting only the ALB SG -> host authentication/signature/decryption -> trusted SDK context. Port 80, direct task ingress, and a new plaintext callback route are prohibited. The SDK operates after this termination/authentication boundary and never changes it.

## Retention, availability, and cost

- Loki uses encrypted S3 plus encrypted EFS. Loki compactor flags enforce `384h` with a two-hour deletion delay; the bucket is non-versioned and expires the telemetry prefix after 18 days as a backstop.
- Prometheus uses encrypted EFS with 16-day time retention and bounded DEV/STAGE/PROD defaults of 10/20/50 GiB.
- Grafana uses encrypted EFS SQLite plus a daily seven-day AWS Backup plan.
- Stateful Loki, Prometheus, and Grafana are exactly one task and do not autoscale. This initial topology is intentionally non-HA.
- Stateless Alloy/query-gateway services use bounded CPU target tracking: 1-2 tasks outside PROD and 2-6 in PROD.
- CloudWatch receives only bounded internal container/platform logs and alarms, with configurable conservative retention. Partner telemetry remains in Loki/Prometheus, not CloudWatch.

Backend loss can interrupt ingest/query/UI but never becomes a readiness dependency for existing partner services.

## Safe validation

No command in this repository performs `terraform apply` or accesses an AWS account. The PROD example remains blocked until an external human workflow explicitly enables it and supplies a change reference.

```bash
TERRAFORM_BIN=/path/to/terraform ./scripts/test-terraform.sh
./scripts/test-security.sh --terraform
```

The test runs recursive formatting checks, provider-schema `terraform validate`, repository policy scans, and a `terraform test` whose run uses `command = plan` with a fully mocked AWS provider. It does not produce or apply real-account plans. TFLint, Checkov, and Trivy are used when an approved CI environment supplies them; none is bundled or silently downloaded.
