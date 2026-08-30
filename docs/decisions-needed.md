# Decisions and Inputs Still Needed

## M0 decisions resolved by the M1 design

The security/availability architecture decisions previously listed as D001-D015 now have implementable defaults. They do not silently remain TBD:

| Original ID | Resolution | ADR / contract |
| --- | --- | --- |
| D001 trusted partner identity | Authenticated server resolver plus source-service allowlist; fail closed | ADR 0003/0004, `partner-isolation.md` |
| D002 Loki tenant/credentials/operator access | One opaque tenant, authenticating gateways, fixed mapping, named operator access | ADR 0004/0005 |
| D003 safe event schema | Versioned immutable schema-2 envelope with seven first-class outbound/acknowledgement/callback/business record types | ADR 0002/0009/0010, `telemetry-contract.md` |
| D004 removal/masking | Non-overridable classification and deterministic mask rules | ADR 0001, `payload-policy.md` |
| D005 binary/Base64/size limits | Pre-queue detectors and numeric hard limits | ADR 0001, `payload-policy.md` |
| D006 queue/drop/shutdown | Dual bounded MPSC queues, byte caps, drop-newest, two-second drain | ADR 0002 |
| D007 transport/retry | OTLP/HTTP to Alloy, dispatcher-only short timeouts, one bounded retry | ADR 0002 |
| D008 encrypted boundary | Never decrypt; explicit immediate safe projection at existing plaintext boundary | ADR 0003 |
| D009 Loki labels/metadata | Fixed eight labels; high-cardinality IDs structured metadata | ADR 0005 |
| D010 metrics/SLIs/cardinality | Contract Micrometer metrics, bounded slot, formulas/buckets/series caps | ADR 0006, `metrics-sli.md` |
| D011 Grafana auth/authorization | Individual local Viewers, org per partner, backend-enforced datasources | ADR 0004 |
| D012 ECS/IAM/network topology | Independent market stack and generic capability/security boundaries implemented by the centralized enterprise Terraform repository | ADR 0007/0013, `enterprise-infrastructure/` |
| D013 storage/retention/HA | S3 Loki 384h, EFS state, initial non-HA single stateful tasks | ADR 0005/0007 |
| D014 Spring clients/context | RestTemplate/WebClient/OkHttp plus scoped MVC/WebFlux callback, servlet/executor/Reactor/MDC, and explicit semantic hooks | ADR 0003/0010 |
| D015 performance budgets | Workload profiles and quantitative gates | `acceptance-criteria.md` |

The later HTTPS/TLS requirement is resolved by ADR 0011: external partner communication is HTTPS-only, external ALBs expose 443 only with ACM, host integrations retain TLS validation ownership, and observability never mutates TLS. The remaining certificate/domain questions below are deployment inputs, not permission to use HTTP or weaken validation.

Q015 is resolved by the approved Q015, Q015-A, and conflict-resolution contracts. Their executable
nine-profile definition is `test/performance/profiles.json`; B003 completion now depends on actual
full-duration execution and evidence, not another design decision.

## Unresolved organizational/deployment inputs

These questions do not authorize unsafe defaults. Their affected production action remains blocked until an accountable owner answers them.

| ID | Question / required owner | Safe default until resolved | Needed by |
| --- | --- | --- | --- |
| Q001 | Does corporate production identity policy permit initial Grafana local accounts without guaranteed MFA, and what is the OIDC/SAML deadline? Security/IAM owner | No production partner account; local accounts only in approved non-production/pilot | Before PROD Grafana access |
| Q002 | Required partner dashboard/ingest availability, RTO, and RPO? Product/operations owner | Honest initial non-HA topology; no HA SLA claim | Before PROD capacity/availability approval |
| Q003 | Expected partners/services/APIs/event rate/payload distribution per market? Product/capacity owner | Enforce 64 partners, 32 services, 64 APIs/service, documented rate/series caps | Before market sizing and any cap increase |
| Q004 | Required destination and retention for internal-only application/platform/audit logs and ALB evidence? Security/compliance owner | CloudWatch/internal storage using existing account policy; never partner tenant | Before PROD compliance review |
| Q005 | Existing ECS launch type/capacity provider, subnets, VPC endpoints, DNS, ingress domains, certificate, and persistent-storage constraints? Cloud platform owner | Central Terraform integration remains unimplemented; no infrastructure action from this repository | Before central STAGE/PROD implementation review |
| Q006 | For each service, what authenticated principal source, API inventory, encryption ordering, safe field schema, and service owner apply? Service/security owners | Starter disabled; then metadata-only after resolver/API approval | Before each onboarding |
| Q007 | Which exact supported image versions/digests and artifact registries are approved for deployment? Platform/security owner | LOCAL_SYNTHETIC pins tested Alloy 1.18.0, Loki 3.7.2, and Nginx 1.28.0 images by digest; no image may be promoted until its registry/digest is approved | Before M8 environment plan or deploy |
| Q008 | Contractual partner SLA targets, eligible/excluded outcomes, calendar/time zone, and alert recipients? Product/legal/partner owner | Show measured SLI with formula; no SLA percentage/alert claim | Before partner SLA activation |
| Q009 | What is the maximum callback/retry arrival horizon for each partner API, and must journeys remain searchable after the required 16-day telemetry retention? Product/legal owner | Correlate only inside retained telemetry; do not extend retention or create a second store | Before claiming callback completeness beyond 16 days |
| Q010 | For each callback route, which host authentication/signature result is authoritative, what is the security/decryption filter order, and which business idempotency result classifies retry/duplicate? Service/security owner | Callback partner telemetry disabled; no expected-route fallback tenant | Before enabling that callback in any environment |
| Q011 | For each callback API, what exact business point means `CALLBACK_PROCESSED`, can a 202 precede it, and which failures/exclusions are partner-visible? Service/product owner | Emit only transport receipt/response metadata; semantic processing events disabled | Before enabling processing SLIs or contractual dashboards |
| Q012 | Which external callback and Grafana DNS names, ACM certificate ARNs/validation zones, approved ALB TLS policy, and certificate-expiry alert owners apply per market? Cloud platform/security owner | No external listener or production partner access; port 80 remains absent | Before central listener plan or any external environment access |
| Q013 | Which partners require a private/custom CA, what approved trust-anchor distribution/revocation/rotation policy applies, and may trust be scoped per client without changing global JVM trust? Service/security/PKI owners | Default JVM trust only; integrations needing unknown custom trust remain disabled | Before enabling that outbound endpoint |
| Q014 | Does any partner require inbound or outbound mTLS, and what certificate identity, issuance, revocation, rotation, ALB mode, and failure policy applies? Partner/security/PKI owners | mTLS not implemented; existing signature/authentication remains mandatory | Before an mTLS ADR or implementation |
| Q016 | Which centralized Terraform repository/team owns each required capability; which existing enterprise modules and naming/tagging standards must be reused; and what approved output handoff, GHA role/authentication, image registry, DNS, certificate, WAF/allowlist, secret-reference, sizing, and rollback values apply in each STAGE/PROD market? Enterprise cloud platform, security, networking, and release owners | Keep the generic contract implementation-neutral; do not add Terraform here or claim central implementation/deployment | Before the future central Terraform integration change or STAGE/PROD rollout |

## Explicit requirement challenges

- Full sanitized payload capture cannot mean verbatim/full raw capture. It is the bounded safe projection in ADR 0001; otherwise it conflicts with mandatory removal, masking, binary exclusion, and fail-closed disclosure.
- Existing arbitrary SLF4J messages cannot safely become partner telemetry after rendering. ADR 0012 permits only startup-approved exact logger/template mappings with configured scalar schemas, optional exact markers, trusted partner context, and first-stage sanitization; raw logs, rendered messages, and throwables remain internal-only.
- Grafana OSS organization isolation alone is not sufficient for shared Prometheus/Loki authorization. Fixed credential mappings, tenant-header injection, PromQL label enforcement, and network denial are mandatory.
- One initial stateful ECS task per backend is cost-conscious but not highly available. No production HA/RTO claim is made without Q002.
- Local Grafana accounts satisfy the stated initial mechanism but may not satisfy production MFA/audit policy; Q001 remains explicit.
- Callback receipt cannot safely be emitted to an expected partner tenant before the host establishes authenticated partner context. The design preserves the ingress timestamp and emits the partner receipt fact only after trust succeeds; failed authentication is internal-only.
- Sixteen-day telemetry retention bounds read-time journey correlation. Q009 must be answered before anyone promises completeness for later callbacks; the platform does not silently add a business-path correlation database.
- Port-80 redirect was not selected. Even redirect-only accepts a plaintext first hop and may change callback POST/authentication behavior; external callback and Grafana listeners are 443-only.
- The starter cannot enforce HTTPS by rewriting or blocking business requests because that would alter application behavior. Host configuration, onboarding/CI policy, deployment controls, and client TLS validation enforce the invariant; the starter safely declines TLS mutation and records only bounded outcomes.
- TLS termination at ALB is not callback authentication and is not represented as end-to-end partner mTLS. Q014 requires a separate ADR before certificate identity can become a trusted callback adapter input.
- The resolved M9 contract is intentionally long and resource-constrained. A mechanics-only smoke
  run, a shortened profile, or a locally reduced load cannot substitute for its full evidence.

No unresolved question weakens `AGENTS.md`, permits production deployment, or permits a fallback shared tenant.
