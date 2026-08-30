# Security Requirements

## Hard boundaries

The central infrastructure implementation must preserve all repository security invariants. In
particular, partner identity is server-fixed, there is one Loki tenant per partner, transaction
identifiers are not Loki labels, backends are private, and observability never becomes a business
readiness or synchronous traffic dependency.

## External transport

- Every STAGE/PROD partner-facing connection is HTTPS/TLS.
- Grafana exposes only TCP 443 through an approved ALB/ACM boundary.
- Port 80 is absent rather than redirected.
- TLS 1.2 is the minimum; the listener policy is explicit and reviewed.
- ACM private keys never leave ACM and never enter Terraform variables/outputs, ECS, Git,
  application artifacts, logs, or telemetry.
- Certificate rotation attaches and validates the replacement before removal of a still-valid
  certificate and retains a rollback path.
- TLS termination is not partner authentication; Grafana login and query gateway identity remain
  mandatory.

## Isolation and ingress controls

- VPC/account/market/environment resources and identities are independently scoped.
- ECS tasks are private with no public IP or direct internet route.
- SG-to-SG allow rules implement the exact connectivity matrix; broad internal access is not an
  acceptable substitute.
- WAF/rate limiting and approved ingress allowlists are attachable without application changes.
- Tenant/slot headers from clients are stripped and overwritten at trusted gateways.
- Direct Loki/Prometheus access, multi-tenant Loki query, Grafana datasource bypass, and browser-
  direct datasource access are denied.
- Secrets and configuration failures fail closed for telemetry/query access without affecting
  partner-service traffic.

## Storage and encryption

- S3 public-access blocks, TLS-only bucket policy, and encryption at rest are mandatory.
- EFS state is encrypted and limited to the relevant task/access point.
- Secrets/parameters, logs, backups, and configuration artifacts use enterprise-approved encryption
  and KMS policy.
- Loki telemetry object versioning is disabled so deleted objects are not retained as noncurrent
  versions; lifecycle provides an 18-day backstop to the application-owned 384-hour retention.
- Break-glass access, if allowed, is named, separately scoped, audited, and never used as a normal
  runtime role.

## Infrastructure logging

CloudWatch and load-balancer logs contain only unavoidable internal operational evidence. They must
not duplicate sanitized partner payloads, raw application exchanges, secrets, credentials,
certificate details, or datasource/query content. Retention is explicit and cost-conscious per
enterprise policy. Access and configuration changes are covered by CloudTrail or the centralized
equivalent.

## Required review evidence in the central repository

The central Terraform change must demonstrate private tasks, exact SG paths, HTTPS-only ingress,
ACM/WAF attachment, encrypted storage, least-privilege IAM, secret references, retention settings,
immutable images, environment isolation, and expected cost/replacement impact. STAGE validation
must precede PROD. Plans and applies are human-reviewed and manually executed outside this
repository.
