# IAM Requirements

## Ownership and role separation

Central Terraform creates or reuses enterprise-standard ECS execution and component task roles for
STAGE/PROD. Roles are scoped per account/market/environment deployment and are not shared across
markets. Human operators, GHA deployment identity, ECS execution, component runtime, backup, and
break-glass identities are separate.

## ECS execution role

The execution role may pull only approved ECR repositories/digests, write only its assigned log
groups, and retrieve only task-definition-referenced secrets/config bootstrap values. Any AWS API
that unavoidably requires `Resource: *`, such as ECR authorization token retrieval, must be
explicitly justified and limited to the minimum action.

## Component task roles

| Component | Required permissions | Explicitly excluded |
| --- | --- | --- |
| Alloy/proxy | Read its config artifacts and named source-auth secrets; publish internal logs/metrics; discover/scrape approved targets | Loki S3 object access, unrelated secrets, cross-market discovery |
| Loki | Read its config; read/write/delete only its deployment's Loki bucket prefix; use required KMS key; mount its EFS access point | Other buckets/prefixes, arbitrary secrets, cross-tenant operator access |
| Prometheus | Read its config/rules; mount its EFS access point; internal logs/metrics | Loki objects, partner secrets, unrelated query services |
| Grafana | Read provisioning artifacts and named Grafana/datasource secret references; mount its state access point | Direct Loki/Prometheus data permissions, secret enumeration, infrastructure mutation |
| Query gateway/resolver | Read its config and exact datasource/source secret references; call private Loki/Prometheus query endpoints | Write APIs, S3 telemetry objects, tenant selection outside fixed map |

KMS grants are limited to the keys and operations required by the associated encrypted resource.
Secrets Manager/SSM policies list exact ARNs and deny enumeration where the central pattern permits.
Configuration artifact permissions list exact versioned object ARNs/prefixes.

## Deployment identity

The enterprise GHA role may update only approved application/runtime assets and ECS deployment
surfaces after infrastructure exists. It cannot create/delete VPC, load balancer, DNS, KMS, IAM,
S3, EFS, or base secrets infrastructure, and it cannot read secret values unless the established
deployment mechanism strictly requires a named value. Prefer passing secret ARNs into task
definitions over fetching values in CI.

## Outputs and audit

Only ARNs, IDs, names, endpoints, and version/change references are outputs. Secret values,
passwords, tokens, private keys, certificate bodies, trust-store content, and Terraform state are
prohibited outputs. Role assumption, secret access, infrastructure changes, and deployment changes
must produce named internal audit evidence under enterprise retention policy.
