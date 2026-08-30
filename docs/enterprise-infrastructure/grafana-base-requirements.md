# Grafana Base Infrastructure Requirements

## Central Terraform ownership

Central Terraform brings up the base Grafana runtime for STAGE/PROD:

- one initial private Grafana OSS ECS task/service while SQLite is used;
- immutable image and application configuration artifact interface;
- encrypted persistent state and enterprise-approved backup/restore policy;
- separate execution/task roles and exact secret/config references;
- a private target group and Grafana task security group;
- a partner-facing ALB with HTTPS 443 only, approved TLS policy, ACM certificate, DNS, WAF/rate
  control, and approved ingress allowlisting hook;
- infrastructure health checks, internal logging, alarms, and service availability;
- no direct task/public Loki/Prometheus path.

Port 80, anonymous access paths, public task IPs, and certificate/private-key export are prohibited.
Scaling beyond one task requires an approved external database/session architecture; this contract
does not create a relational database requirement.

## Required application deployment interface

The base runtime must permit GHA to roll versioned Grafana provisioning artifacts, dashboards,
alerts, plugins if approved, and configuration without recreating ALB, DNS, storage, IAM, or base
secrets. It must provide the Grafana HTTPS URL, service/target identifiers, provisioning artifact
destination/interface, referenced secret ARNs, and a post-deployment health endpoint.

Partner users remain Viewer-only in exactly one organization and cannot edit provisioned
datasources/dashboards, use a backend directly, or choose a tenant/metric slot. Identity/federation
and formal audit policy are enterprise inputs and must preserve these authorization properties.
