# Stage and Production Infrastructure Requirements

## Deployment unit and reusable market model

The deployment unit is one Partner Observability platform for one
`(AWS account, market, environment, dedicated ECS cluster)` tuple. A market cluster may serve
multiple partner-facing services and multiple isolated partner identities. For example, PH-STAGE
and PH-PROD are independent deployments, while partner A and partner B are onboarded through
configuration in their respective deployment. Adding a partner must not require another Java
codebase or a new platform architecture.

No bucket prefix, tenant, metric slot, credential, service discovery name, Grafana organization,
query scope, or runtime configuration may cross the deployment tuple.

## Central enterprise Terraform requirements

For STAGE and PROD, the centralized Terraform repository must satisfy these requirements using its
existing patterns:

1. Integrate with the existing dedicated market ECS cluster; do not create a cross-market cluster.
2. Provide independently deployable ECS services/tasks for Alloy ingress, Loki, Prometheus,
   Grafana, and the query gateway/journey resolver unless an approved central pattern provides an
   equivalent isolation-preserving runtime.
3. Provide minimum task definitions with immutable image references, configuration artifact
   references/digests, bounded CPU/memory, non-root/read-only controls where supported, health
   checks, log destinations, secrets references, and required persistent mounts.
4. Accept versioned container images and application configuration artifacts produced by the
   application release process; never require secret values in image or configuration metadata.
5. Integrate with the market VPC and its approved routing, endpoint, egress, DNS, and inspection
   patterns.
6. Run every ECS task in private subnets with `assign_public_ip=false`; only approved ALB subnets
   may be public.
7. Enforce exact service-to-service connectivity described in `network-requirements.md`; backends
   must not be directly partner reachable.
8. Expose Grafana through the approved partner-facing ingress only.
9. Provide HTTPS-only external ingress with TLS 1.2 or newer and no port-80 listener or rule.
10. Attach an approved ACM certificate by ARN and support managed renewal/attach-before-remove
    rotation without exporting private keys.
11. Create or reuse least-privilege security groups with SG-to-SG rules where AWS supports them.
12. Provide a WAF association/rate-control hook for Grafana according to enterprise policy.
13. Provide environment/partner IP allowlisting inputs where the enterprise edge supports them;
    absence of a final allowlist is an unresolved onboarding input, not permission for broad access.
14. Provide encrypted S3 storage dedicated to the deployment's Loki TSDB objects.
15. Use enterprise-approved KMS/encryption controls for S3, EFS, secrets, logs, and backups where
    required; require TLS for storage access and block public S3 access.
16. Provide separate ECS execution and least-privilege task roles by runtime responsibility.
17. Scope permissions to exact image repositories, config objects, secret ARNs, log groups, bucket
    prefix, KMS keys, service discovery operations, and runtime APIs.
18. Provision/reference Secrets Manager or SSM entries for source, datasource, Grafana, gateway,
    and runtime credentials; Terraform outputs expose references only, never values.
19. Permit Prometheus ingestion only from Alloy and queries only from the fixed query gateway and
    approved internal operators.
20. Permit Alloy ingress only from onboarded application-service security groups and allow Alloy
    to reach only Loki/Prometheus destinations required by its application pipeline.
21. Permit Loki writes from Alloy and reads from the query gateway; deny public, browser-direct,
    and multi-tenant bypass paths.
22. Permit Grafana to reach only the fixed query gateway and required secret/config/state services;
    Grafana must not have a direct Loki or Prometheus network path.
23. Provide approved Grafana DNS and private service-discovery names for internal components.
24. Configure component-specific infrastructure health checks. Observability health must never
    participate in partner-service readiness, deployment circuit breaking, or business ALB health.
25. Send only unavoidable infrastructure/runtime operational logs to internal CloudWatch surfaces;
    never route those logs to partner tenants.
26. Apply explicit, cost-conscious CloudWatch/access-log retention and alarms; do not use indefinite
    defaults or copy partner payloads into infrastructure logs.
27. Expose the non-secret outputs required by the application deployment workflow, listed below.
28. Accept explicit environment inputs for market, account, region, cluster, network, images,
    configuration digests, sizing, desired counts, autoscaling bounds, storage, DNS/certificate,
    WAF/allowlists, secret references, partners, thresholds, notification routes, and maintenance
    controls.

## Required outputs for application deployment

Central Terraform must make these outputs available through the enterprise-approved, access-
controlled integration mechanism—not committed secret files:

- environment/account/region/market identity and ECS cluster ARN/name;
- ECS service names/ARNs and current task-definition families for all platform components;
- private Alloy ingress DNS name/port and trust/certificate reference needed by application tasks;
- internal Loki, Prometheus, and query-gateway service-discovery endpoints;
- Grafana HTTPS URL, ALB/target identifiers needed for deployment validation, and DNS name;
- configuration artifact bucket/prefix or equivalent deployment destination and allowed role;
- Loki bucket name/prefix and storage/KMS reference identifiers, never data-access credentials;
- referenced secret/parameter ARNs and KMS key ARNs, never secret values;
- security-group IDs needed to authorize onboarded source/scrape services;
- log group names, health-check endpoints, and alarm/notification integration identifiers;
- persistent filesystem/access-point identifiers when runtime updates must mount them;
- an infrastructure version/change reference proving the manual infrastructure step completed.

Outputs must be scoped per market/environment and must not include passwords, tokens, certificate
or private-key material, datasource credentials, trust-store contents, or Terraform state data.

## Application-owned deployment inputs

The application release supplies immutable image digests and validated application-level artifacts
for Alloy, Loki, Prometheus, Grafana provisioning, gateways, dashboards, alerts, and rules. It also
supplies the reviewed non-secret market/partner manifest digest. GHA may update ECS runtime/task
configuration only through the enterprise-approved deployment interface after the infrastructure
outputs above exist.

## Database determination

**NO DATABASE REQUIRED FOR CURRENT ARCHITECTURE.**

The Java modules have no JDBC/R2DBC, database driver, Liquibase, or Flyway dependency or schema.
The stateless journey resolver has no partner-data database. Grafana's initial SQLite/EFS state is
component-owned base runtime state, not a Sure Partner Observability business schema. Therefore no
database, RDS, or Liquibase requirement is created. If a future approved design introduces an
application schema, its migrations would be application-owned and executed by enterprise GHA only
after the base database exists.
