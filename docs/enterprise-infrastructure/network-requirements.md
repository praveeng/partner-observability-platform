# Network Requirements

## VPC and subnet placement

Central Terraform integrates with the existing market VPC. All platform ECS tasks and EFS mount
targets use private subnets in the target market/environment. Tasks have no public IP and task
subnet route tables have no direct internet-gateway route. Controlled egress uses the enterprise
NAT, egress proxy, firewall, and VPC endpoints already approved for the account.

Only the Grafana ALB may be partner-facing for this platform. Public ALB subnets, if used, are
supplied by the central network pattern. Partner callback ALBs remain owned by their host services
and are not created for this platform contract.

## Required connectivity matrix

| Source | Destination | Purpose |
| --- | --- | --- |
| Onboarded partner-service SGs | private Alloy ingress | asynchronous OTLP/log export only |
| Alloy tasks | onboarded private Actuator targets | approved Micrometer scrape |
| Alloy tasks | Loki write endpoint | sanitized fixed-tenant event write |
| Alloy tasks | Prometheus remote-write endpoint | allowlisted metric write |
| Grafana tasks | query gateway | partner datasource/query traffic |
| Query gateway | Loki query endpoint | fixed-tenant queries only |
| Query gateway | Prometheus query endpoint | fixed-slot parsed queries only |
| Stateful tasks | their EFS mount targets | component state only |
| Authorized tasks | S3/KMS/Secrets/SSM/CloudWatch/ECR/config artifacts | exact runtime dependency |
| Approved internal operators | documented management/query endpoints | audited operational access |

All other paths are denied by default. Loki and Prometheus are not reachable from a browser,
Grafana ALB, partner-service task, or the public internet. Grafana does not bypass the query
gateway. Query gateway and Alloy have no partner-data store outside their defined runtime needs.

## Grafana ingress

- ALB listener: HTTPS 443 only; no listener or SG rule on port 80.
- Certificate: approved ACM ARN, managed renewal, hostname-matching DNS.
- TLS policy: pinned enterprise-approved policy with TLS 1.2 minimum.
- Edge controls: WAF association/rate limit hook and market-approved IPv4/IPv6/partner allowlists.
- Target: private Grafana tasks; target SG accepts the application port only from the ALB SG.
- Direct task reachability and spoofed forwarding-header paths must fail.
- ALB access logs, if required, use an internal encrypted destination with explicit retention.

## Alloy ingress and service discovery

Alloy ingress is private and authenticated. The central pattern may use a private TLS NLB or an
approved internal equivalent. It must expose a stable private DNS endpoint to onboarded source
services, preserve short dispatcher timeouts, strip tenant headers, and provide certificate/trust
references without exposing key material.

Internal component names use private service discovery scoped to the deployment tuple. DNS names
must not resolve across market/environment stacks. Central Terraform exposes the non-secret names
to the application deployment workflow.

## Storage and AWS endpoints

EFS, S3, KMS, Secrets Manager/SSM, CloudWatch, configuration storage, and image-registry access use
enterprise-approved private endpoints/prefix lists where available. Public S3 access is blocked.
Security groups reference other security groups instead of broad CIDRs where supported.
