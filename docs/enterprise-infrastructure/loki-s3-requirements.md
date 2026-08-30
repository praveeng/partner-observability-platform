# Loki and S3 Requirements

## Infrastructure owned by central Terraform

Central Terraform provides the private Loki ECS runtime, task/service discovery, security groups,
execution/task roles, encrypted persistent WAL/cache/compactor work storage, an encrypted S3 bucket
or deployment-exclusive prefix, bucket policy, KMS integration, VPC access, infrastructure health
checks, bounded internal logs, and storage/compactor alarms.

The S3 boundary must:

- block public access and require TLS;
- grant the Loki task role least-privilege object/list/delete access only to its deployment prefix;
- permit only an approved named break-glass recovery role beyond the runtime role;
- encrypt objects with the enterprise-approved S3/KMS control;
- disable object versioning/noncurrent telemetry retention;
- apply an 18-day lifecycle expiration as a deletion safety backstop;
- prevent any account/market/environment prefix sharing that weakens isolation;
- expose bucket/prefix/KMS identifiers, never credentials, to the deployment workflow.

## Application/runtime policy owned here

Sure Partner Observability owns and validates the Loki configuration artifact and policy:

- multi-tenancy enabled with exactly one opaque tenant per partner;
- TSDB index/schema v13 and structured metadata;
- fixed eight low-cardinality indexed labels;
- transaction identifiers in structured metadata, not labels;
- compactor retention exactly `384h` (16 days) and a two-hour deletion delay;
- bounded ingest/query limits, schema compatibility, tenant routing, and sanitization contracts;
- configuration-driven partner onboarding/offboarding and non-reuse of tenant IDs.

GHA deploys the validated configuration after Loki base infrastructure exists. Central Terraform
must not embed application label schemas, partner payload policy, dashboards, or tenant routing
logic in infrastructure modules.

## Health and recovery

Compactor/storage failures are internal alarms. Loki may be temporarily unavailable without
affecting business services. The initial single-binary topology is cost-conscious and explicitly
not HA. Restore, version upgrade, capacity, and any move to simple-scalable mode require reviewed
central infrastructure procedures and application compatibility evidence.
