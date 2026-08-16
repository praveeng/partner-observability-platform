# Decisions and Inputs Still Needed

## M0 decisions resolved by the M1 design

The security/availability architecture decisions previously listed as D001-D015 now have implementable defaults. They do not silently remain TBD:

| Original ID | Resolution | ADR / contract |
| --- | --- | --- |
| D001 trusted partner identity | Authenticated server resolver plus source-service allowlist; fail closed | ADR 0003/0004, `partner-isolation.md` |
| D002 Loki tenant/credentials/operator access | One opaque tenant, authenticating gateways, fixed mapping, named operator access | ADR 0004/0005 |
| D003 safe event schema | Versioned immutable envelope and three record types | ADR 0002, `telemetry-contract.md` |
| D004 removal/masking | Non-overridable classification and deterministic mask rules | ADR 0001, `payload-policy.md` |
| D005 binary/Base64/size limits | Pre-queue detectors and numeric hard limits | ADR 0001, `payload-policy.md` |
| D006 queue/drop/shutdown | Dual bounded MPSC queues, byte caps, drop-newest, two-second drain | ADR 0002 |
| D007 transport/retry | OTLP/HTTP to Alloy, dispatcher-only short timeouts, one bounded retry | ADR 0002 |
| D008 encrypted boundary | Never decrypt; explicit immediate safe projection at existing plaintext boundary | ADR 0003 |
| D009 Loki labels/metadata | Fixed eight labels; high-cardinality IDs structured metadata | ADR 0005 |
| D010 metrics/SLIs/cardinality | Contract Micrometer metrics, bounded slot, formulas/buckets/series caps | ADR 0006, `metrics-sli.md` |
| D011 Grafana auth/authorization | Individual local Viewers, org per partner, backend-enforced datasources | ADR 0004 |
| D012 ECS/IAM/network topology | Independent market stack and explicit Terraform modules/security groups | ADR 0007 |
| D013 storage/retention/HA | S3 Loki 384h, EFS state, initial non-HA single stateful tasks | ADR 0005/0007 |
| D014 Spring clients/context | RestTemplate/WebClient/OkHttp plus scoped servlet/executor/Reactor/MDC | ADR 0003 |
| D015 performance budgets | Workload profiles and quantitative gates | `acceptance-criteria.md` |

## Unresolved organizational/deployment inputs

These questions do not authorize unsafe defaults. Their affected production action remains blocked until an accountable owner answers them.

| ID | Question / required owner | Safe default until resolved | Needed by |
| --- | --- | --- | --- |
| Q001 | Does corporate production identity policy permit initial Grafana local accounts without guaranteed MFA, and what is the OIDC/SAML deadline? Security/IAM owner | No production partner account; local accounts only in approved non-production/pilot | Before PROD Grafana access |
| Q002 | Required partner dashboard/ingest availability, RTO, and RPO? Product/operations owner | Honest initial non-HA topology; no HA SLA claim | Before PROD capacity/availability approval |
| Q003 | Expected partners/services/APIs/event rate/payload distribution per market? Product/capacity owner | Enforce 64 partners, 32 services, 64 APIs/service, documented rate/series caps | Before market sizing and any cap increase |
| Q004 | Required destination and retention for internal-only application/platform/audit logs and ALB evidence? Security/compliance owner | CloudWatch/internal storage using existing account policy; never partner tenant | Before PROD compliance review |
| Q005 | Existing ECS launch type/capacity provider, subnets, VPC endpoints, DNS, ingress domains, certificate, and EFS constraints? Cloud platform owner | Terraform accepts inputs; no production plan/apply | Before M8 environment plan |
| Q006 | For each service, what authenticated principal source, API inventory, encryption ordering, safe field schema, and service owner apply? Service/security owners | Starter disabled; then metadata-only after resolver/API approval | Before each onboarding |
| Q007 | Which exact supported image versions/digests and artifact registries are approved at implementation time? Platform/security owner | No floating/latest tags; select and pin during M5-M8 with compatibility tests | Before component implementation/deploy |
| Q008 | Contractual partner SLA targets, eligible/excluded outcomes, calendar/time zone, and alert recipients? Product/legal/partner owner | Show measured SLI with formula; no SLA percentage/alert claim | Before partner SLA activation |

## Explicit requirement challenges

- Full sanitized payload capture cannot mean verbatim/full raw capture. It is the bounded safe projection in ADR 0001; otherwise it conflicts with mandatory removal, masking, binary exclusion, and fail-closed disclosure.
- Existing arbitrary SLF4J messages cannot safely become partner telemetry after rendering. Only marked structured safe-log records are captured; raw logs remain internal-only.
- Grafana OSS organization isolation alone is not sufficient for shared Prometheus/Loki authorization. Fixed credential mappings, tenant-header injection, PromQL label enforcement, and network denial are mandatory.
- One initial stateful ECS task per backend is cost-conscious but not highly available. No production HA/RTO claim is made without Q002.
- Local Grafana accounts satisfy the stated initial mechanism but may not satisfy production MFA/audit policy; Q001 remains explicit.

No unresolved question weakens `AGENTS.md`, permits production deployment, or permits a fallback shared tenant.
