# Adversarial Security Review

## Verdict and scope

**Production security verdict: REJECTED / BLOCKED.** The implemented SDK, Alloy/Loki,
Prometheus, local Grafana/query gateway, and Terraform boundaries passed their available
local security suites, and two confirmed isolation defects were fixed with failing-first
regressions. The local Grafana completion boundary has real provisioning, isolated
organizations, Viewer accounts, fixed datasources, and adversarial API/query tests. The
application-to-authorized-query suite now drives the real test application and passes its
two-partner security boundary. Exact performance profiles and staging-owned callback/TLS
evidence remain mandatory release blockers. Partner access must remain disabled outside the
validated local synthetic boundary until those separate gates pass.

This review was performed on 2026-08-23 and updated on 2026-08-24 against the current worktree. It covered the Java
17/Spring Boot 2.7 SDK, callback fixture and filters, selected SLF4J compatibility, Alloy,
Loki, Prometheus, local gateways, Docker Compose, Terraform/ECS policy, documentation, and
completion scripts. No AWS account, production credential, deployment, push, or production
data was used. All fixtures are synthetic.

Evidence commands executed during the review:

- `./scripts/test-security.sh --core` — PASS after fixes.
- `./scripts/test-security.sh --data-plane` — PASS using real Alloy/Loki containers.
- `./scripts/test-security.sh --metrics-plane` — PASS using real Alloy/Prometheus containers.
- `./scripts/test-grafana.sh` — PASS using real Grafana/Loki/Prometheus/gateway containers and generated local users/secrets.
- `./scripts/test-grafana.sh --validate-only` — PASS using live Grafana provisioning/API validation.
- `./test/integration/run-local-end-to-end.sh` — PASS using application-originated RestTemplate and real callback traffic through the SDK, Alloy, fixed query gateway, and Grafana.
- `TERRAFORM_BIN=/tmp/partner-observability-terraform-1.11.4/terraform ./scripts/test-security.sh --terraform` — PASS, including the mocked network plan.
- `./test/security/run-adversarial-static.sh` — PASS; it rejects production TLS bypass/downgrade primitives, tracked private keys, sensitive Terraform outputs, and missing origin/route guards.

`scripts/verify-all.sh` is reported in the verification section below. A passing scoped
command does not compensate for a missing authorization boundary.

## Authoritative verification result

The final current-worktree run used Java 17, Gradle 7.6.4, Docker Compose
2.40.3-desktop.1, Terraform 1.11.4, and AWS provider 6.61.0:

```bash
GRADLE_USER_HOME=/tmp/partner-observability-gradle \
TERRAFORM_BIN=/tmp/partner-observability-terraform-1.11.4/terraform \
./scripts/verify-all.sh
```

Result on 2026-08-24: `FINAL RESULT: FAIL (1 of 22 stages failed)`. Twenty-one stages passed,
including requirements 35, 36–46, 48, and the complete local security gate. The only failure
was the independently open full-duration performance boundary (B003), which still reports
`NOT IMPLEMENTED`. No implemented stage regressed.

Status terms: **PASS** means the implemented local boundary resisted the attack; **FIXED**
means this review first reproduced and then repaired a defect; **PARTIAL** means bounded
supporting evidence exists but the complete attack boundary is absent; **BLOCKED** means a
mandatory component/test does not exist; **STAGING** means the scenario requires deployed
non-production infrastructure to be meaningful.

## Confirmed findings and fixes

| ID | Severity | Finding and root cause | Reproduction first | Fix and retained regression |
| --- | --- | --- | --- | --- |
| SR-01 | High | Outbound automatic capture matched only HTTP method and path. A different origin with the same path was classified under the configured partner, allowing wrong-party traffic to enter that partner's telemetry tenant. | `outboundSelectionCannotCrossAConfiguredPartnerOrigin` failed before the implementation change. | Every outbound definition now requires a configuration-owned origin. Matching includes scheme, host, effective port, method, and path. Startup rejects missing/plaintext origins. The only HTTP exception is explicit `local-synthetic=true` in DEV with a literal loopback origin. |
| SR-02 | Medium | Callback route templates were checked for literal uniqueness only. Semantically overlapping routes such as `/callbacks/{applicationId}` and `/callbacks/{partnerReferenceId}` selected the first definition, creating configuration-order-dependent denial/misattribution risk. | `overlappingCallbackRoutesForDifferentPartnersFailStartupClosed` failed before the validator change. | Startup now rejects same-method callback routes when every segment can match the same request, including literal-versus-variable overlaps. |

No trust-all manager, permissive hostname verifier, TLS verification bypass, production/staging
plaintext partner origin, public internal-backend listener, or SDK HTTPS downgrade was found.

## Open completion blockers

| ID | Severity | Blocker | Security effect |
| --- | --- | --- | --- |
| SR-05 | Medium | Exact saturation, callback-flood, soak, reactive-cancellation, and heap/GC profiles are `NOT IMPLEMENTED`. | Queues are bounded and business calls are isolated in unit/integration tests, but cross-workload telemetry starvation and long-duration memory behavior are not accepted. |
| SR-06 | High | Callback ALB/DNS/ACM/security-group infrastructure is host-service-owned and has no deployed staging evidence in this repository. | Inbound HTTPS-only behavior, forwarding-header spoof denial, direct-task reachability, certificate rotation, and WAF/rate controls require onboarding and staging tests before any external callback exposure. |

Resolved on 2026-08-24: **SR-03 / B001**. `grafana/provisioning`, the generic Partner Operations dashboard, the fixed Loki/Prometheus query routes, and `test/integration/run-local-grafana.sh` now exercise PARTNER_A/PARTNER_B local authentication, exactly-one-organization Viewer membership, datasource/dashboard provisioning, secret non-disclosure, cross-tenant searches, colliding IDs, timeline/detail/SLI results, and API/Explore/org/header/UID/PromQL bypasses.

Resolved on 2026-08-24: **SR-04 / B002**. `test/integration/run-local-end-to-end.sh` builds and drives the real synthetic Spring application through the SDK bounded dispatcher, fixed Alloy ingest routes, tenant Loki/Prometheus, query gateway, and Viewer-authenticated Grafana datasource/dashboard APIs. It proves real request/response and HTTP 202/callback journeys, typed searches, events, metrics/SLIs, bidirectional and colliding-ID isolation, and payload-safety sink absence without direct OTLP seeding or direct Loki acceptance queries.

## Attack scenario results

### Cross-partner isolation

| # | Attack | Result | Evidence / limitation |
| ---: | --- | --- | --- |
| 1 | Partner A reads Partner B logs | PASS | Grafana resolves the authenticated organization datasource to a fixed gateway identity; A/B exact and header-spoof queries return only the fixed Loki tenant. |
| 2 | Partner A reads Partner B events | PASS | Real tenant-fixed ingest plus authenticated Grafana datasource-proxy queries deny foreign event IDs and transaction searches. |
| 3 | Partner A reads Partner B metrics | PASS | Grafana uses organization-local fixed datasources; the gateway injects the slot and `prom-label-proxy` rejects/intersects conflicting, regex, absent, and header-spoof selectors. |
| 4 | Partner A reads Partner B callbacks | PASS | Callback records remain tenant-fixed and are retrieved only through the authenticated organization's datasource path. |
| 5 | Same `applicationId` across tenants | PASS | Core batches, test app, and real Loki collision queries remain partner-pure. |
| 6 | Same `partnerReferenceId` across tenants | PASS | Typed metadata is queried only after tenant fixation; it is never an authorization key or label. |
| 7 | Same `callbackReferenceId` across tenants | PASS | Synthetic A/B collisions remain isolated in test-app and Loki evidence. |
| 8 | Spoofed `partnerId` | PASS | Context resolver rejects client identity; body spoof cannot select an Alloy route or callback tenant. |
| 9 | Spoofed `X-Scope-OrgID` | PASS | Ingest/query gateways overwrite the header; real query-header spoof returned no foreign data. |
| 10 | Grafana variable manipulation | PASS | The generic dashboard exposes no partner/tenant/slot selector; typed search values remain inside the already-fixed datasource tenant. Saved dashboard edits are denied. |
| 11 | Direct datasource/query manipulation | PASS | Header injection, foreign UID guesses, datasource proxy queries, conflicting/regex PromQL, label enumeration, unsupported endpoints, and direct gateway authentication are exercised and remain fixed or denied. |
| 12 | Grafana API access | PASS | Generated Viewer sessions cannot create datasources, administer users/organizations, switch to the foreign org, edit provisioned dashboards, or retrieve datasource secure values. |
| 13 | Insecure local-user permissions | PASS | PARTNER_A and PARTNER_B users authenticate with generated passwords, are non-server-admin Viewers in exactly one organization, and invalid credentials/logout behavior is verified. |

### Callback-specific attacks

| # | Attack | Result | Evidence / limitation |
| ---: | --- | --- | --- |
| 14 | Body claims another `partnerId` | PASS | Telemetry starts only after trusted resolver success; body spoof remains content and is sanitized. |
| 15 | Partner-A path receives Partner-B data | PASS | Authenticated route conflict yields no trusted callback record; ambiguous configured routes now fail startup (SR-02). |
| 16 | Spoofed `applicationId` | PASS for isolation | The value can correlate only inside the already-fixed tenant; it cannot select a tenant. Business truth remains host-owned. |
| 17 | Spoofed `partnerReferenceId` | PASS for isolation | Same fixed-tenant rule and bounded identifier validation. |
| 18 | Spoofed `callbackReferenceId` | PASS for isolation | Same fixed-tenant rule; collisions across partners remain isolated. |
| 19 | Unknown callback reference | PASS | Recorded only under authenticated partner context with explicit unknown/late semantics; no cross-tenant lookup. |
| 20 | Malformed callback correlation values | PASS | Typed bounded identifier constructors reject invalid values and parsing/validation failures emit no raw value. |
| 21 | Authentication/signature failure | PASS | Synthetic invalid signature receives 401 and creates no trusted partner callback facts. |
| 22 | Callback replay | PASS for telemetry semantics | Every delivery gets a distinct attempt; the SDK does not claim or perform business idempotency. |
| 23 | Duplicate callback abuse | PARTIAL | Distinct duplicate attempts and bounded queues are proven; sustained abuse/rate-control profile is SR-05 and host-owned WAF is SR-06. |
| 24 | Extremely high callback retry rate | PARTIAL | Non-blocking drop-on-saturation protects business traffic; duration/rate and fairness evidence is absent (SR-05). |
| 25 | Payload poisons another tenant | PASS | Tenant is fixed before capture; Alloy independently drops unsafe/cross-routing metadata. |
| 26 | Path/query/header manipulation | PASS locally / STAGING externally | Exact method/path matching, route-overlap rejection, signature checks, and header spoof tests pass. Forwarded-header/ALB proof is SR-06. |
| 27 | Internal-looking identifiers | PARTIAL | Identifier syntax/size is bounded and never labels; semantic provenance cannot be inferred from attacker text and must remain tenant-fixed. |
| 28 | Processing exception leaks stack trace | PASS | Callback records contain bounded codes/status only; filters never read exception messages or stacks. |
| 29 | Callback response exposes internals | PASS for SDK/fixture | Response telemetry is status/write outcome; fixture responses are fixed strings. Host response bodies remain a service security responsibility. |
| 30 | Telemetry before trusted resolution | PASS | MVC/WebFlux filters resolve and compare the trusted key before creating observation/context. |
| 31 | Route using body `partnerId` | PASS | Implemented default resolver uses authenticated `Principal`; synthetic adapter consumes a trusted request attribute set by signature/auth checks. |

### Data leakage

| # | Attack | Result | Evidence / limitation |
| ---: | --- | --- | --- |
| 32 | Authorization | PASS | Removed at sanitizer, selected-log, callback, Alloy, and Loki checks. |
| 33 | Access/refresh tokens | PASS | Alias and JWT/value-shape corpus removes them. |
| 34 | Cookies | PASS | Cookie/set-cookie/session aliases are removed. |
| 35 | API keys | PASS | Case/separator aliases and value corpus are removed. |
| 36 | Passwords | PASS | Nested and top-level variants are removed. |
| 37 | Encryption key/IV | PASS | Encrypted-flow test proves prequeue absence; keys/IVs are never safe metadata. |
| 38 | OTP | PASS | OTP/PIN aliases are removed. |
| 39 | Card data | PASS | PAN/CVV/track/expiry aliases and known value shapes are removed. |
| 40 | Unmasked PII | PASS for required corpus | Phone/email/account/national-ID/address masks pass before queue and at Alloy defense in depth. |
| 41 | Base64 document | PASS | Large, nested, unknown-field, log, outbound, encrypted, and callback cases are omitted. |
| 42 | Image/PDF | PASS | MIME, key-name, generated PDF/JPEG, and Alloy sink-absence cases pass. |
| 43 | Binary under unexpected names | PASS | Byte/buffer/stream/path types and encoded-content heuristics run independently of field name. |
| 44 | Binary inside callback | PASS | Hostile callback fixtures reduce to omission metadata; raw bodies never enter SDK queues. |
| 45 | Nested secrets | PASS | Nested case/separator aliases and allowlist-precedence tests pass. |
| 46 | Malformed JSON bypass | PASS | Malformed input yields bounded omission/processing facts and no raw fallback. |
| 47 | Payload size bypass | PASS locally | Declared/raw/safe tree/string/queue bounds and 5–10 MiB fixtures pass; heap/GC duration proof remains SR-05. |

### TLS / HTTPS attacks

| # | Attack | Result | Evidence / limitation |
| ---: | --- | --- | --- |
| 48 | Outbound partner call over HTTP | FIXED / PARTIAL | Configured automatic capture now rejects HTTP except explicit DEV literal-loopback `local-synthetic`. Host calls outside the manifest still require service CI/egress enforcement. |
| 49 | Inbound callback over HTTP | STAGING | Observability creates no endpoint; host callback ALB proof is required by SR-06. |
| 50 | Trust-all `TrustManager` | PASS | Production-source and tracked-file scan found none and fails on introduction. |
| 51 | Permissive `HostnameVerifier` | PASS | Production-source scan found none and fails on introduction. |
| 52 | Disabled hostname verification | PASS | No setter/bypass exists; wrong-host behavior is unchanged with instrumentation enabled. |
| 53 | Disabled certificate validation | PASS | No bypass exists; untrusted certificates fail for all three clients. |
| 54 | Self-signed/untrusted certificate | PASS | Runtime generated certificate fails identically enabled/disabled for RestTemplate, WebClient, and OkHttp. |
| 55 | Expired certificate | PASS | Runtime expired certificate fails identically for all three clients and emits no certificate details. |
| 56 | Wrong-host certificate | PASS | All three clients reject it identically; the unapproved origin is not captured after SR-01. |
| 57 | TLS handshake failure classification | PARTIAL | Typed untrusted failures become `TLS_CERTIFICATE_VALIDATION`. Some expired-client stacks expose only timeout/generic types; code deliberately does not parse messages to guess. |
| 58 | Silent HTTPS-to-HTTP fallback | PARTIAL | Static scan and origin binding find no SDK downgrade/retry. Runtime redirect policy remains host-owned and lacks the mandatory per-client redirect suite. |
| 59 | RestTemplate SSL behavior altered | PASS | Original request factory is reused and enabled/disabled outcomes match. |
| 60 | WebClient SSL behavior altered | PASS | Original connector is reused and enabled/disabled outcomes match. |
| 61 | OkHttp SSL behavior altered | PASS | Socket factory, trust manager, verifier, pinner, and connection specs remain identical. |
| 62 | Callback observability creates plaintext bypass | PASS in code / STAGING network | It only registers a filter on host routes and creates no listener; external reachability remains SR-06. |
| 63 | Grafana external ingress not HTTPS-only | PASS in Terraform | Mocked plan asserts one 443 HTTPS/ACM/TLS-policy listener and no port 80. |
| 64 | Grafana tasks internet reachable | PASS in Terraform / STAGING reachability | Private subnets, no public IP, ALB-SG-only ingress. |
| 65 | Loki internet reachable | PASS in Terraform / STAGING reachability | No public listener/IP; exact internal SG sources. |
| 66 | Prometheus internet reachable | PASS in Terraform / STAGING reachability | Same private/no-public-IP controls. |
| 67 | Alloy internet reachable | PASS in Terraform / STAGING reachability | Internal TLS NLB only and allowlisted service SG sources. |
| 68 | TLS/private-key secrets in fixtures | PASS | Tracked-file scan found no PEM private key; TLS telemetry sentinel scans contain no key/trust/session material. |
| 69 | ACM/private key committed | PASS | Only synthetic ACM ARNs are tracked; no private certificate material. |
| 70 | Terraform secret outputs | PASS | Static policy and adversarial scan reject secret/key/password/certificate-shaped outputs. |
| 71 | External port 80 | PASS for Grafana / STAGING callback | Terraform has no Grafana port-80 listener/rule; service-owned callback ALB evidence remains SR-06. |

### Platform and resilience

| # | Attack | Result | Evidence / limitation |
| ---: | --- | --- | --- |
| 72 | Sanitizer failure | PASS | `submitSafely` drops malformed construction with no queue admission or business exception. |
| 73 | Alloy rule failure/misconfiguration | PARTIAL | Configuration validation and backend-outage isolation pass; deliberate live rule corruption/recovery is not automated. |
| 74 | Unsafe defaults | FIXED / PASS | Disabled/metadata-only defaults, required allowlists, required HTTPS origin, explicit local fixture mode, and callback-overlap rejection fail closed. |
| 75 | Internal stack trace | PASS | Payload/log/TLS/callback paths never read or emit throwable messages/stacks. |
| 76 | Internal hostname/URL | PASS by default / configuration risk | Automatic metadata never emits URI/host. Reviewed safe-field schemas must not allow free-form internal diagnostics. |
| 77 | SQL/DB implementation detail | PASS by default / configuration risk | No arbitrary log or exception capture exists. A wrongly approved free-form payload field cannot be made safe by regex and must be rejected during schema review. |
| 78 | High cardinality abuse | PASS | Fixed labels/tags, 64-partner cap, startup meter budget, and real Alloy label stripping pass. |
| 79 | Queue exhaustion | PASS | Fixed event/byte queues drop newest with bounded accounting and prompt producers. |
| 80 | Memory amplification | PARTIAL | 10 MiB Base64 and bounded tree/queue tests pass; full heap/GC profile is SR-05. |
| 81 | Telemetry outage affects outbound traffic | PASS locally | Publisher failure and saturation leave client statuses/exceptions unchanged. |
| 82 | Telemetry outage affects callback processing | PASS locally | Callback filters use the same non-blocking dispatcher and contain export failures. |
| 83 | Callback flood starves outbound telemetry | PARTIAL / residual risk | Business traffic remains protected by bounded drop behavior, but the shared success queue has no per-workload fairness guarantee; SR-05 must quantify this before acceptance. |
| 84 | Malicious callback causes OOM/GC pressure | PARTIAL | SDK callback interception is metadata-only and does not buffer bodies; sanitizer and queues are bounded. Host body parsing and long-duration heap behavior require SR-05/SR-06 evidence. |

## Callback-specific conclusions

Partner identity is established before telemetry creation. The shipped default MVC resolver
uses `HttpServletRequest.getUserPrincipal()` and the reactive resolver uses the authenticated
reactive principal. The synthetic fixture resolver consumes only a server-owned attribute set
after constant-time signature verification and route/run authorization. Body fields, query
parameters, MDC, and `X-Scope-OrgID` do not select a callback tenant.

Custom resolver interfaces necessarily receive the host request/exchange so an integration can
consume host-authenticated state. That is an extension trust boundary, not a technical proof of
correct host authentication. Every onboarding therefore needs a negative test showing body,
route, query, forwarding, and tenant headers cannot change the resolver result.

## TLS / HTTPS conclusions

The SDK contains no TLS ownership code and does not mutate service clients. This review added an
origin authorization boundary for automatic outbound telemetry and startup rejection of
plaintext configured origins. It does not turn observability into an egress firewall: the host
service still owns redirect policy, DNS/proxy behavior, and the completeness of the endpoint
manifest.

Terraform proves the repository-owned Grafana ALB is HTTPS 443 only and that observability tasks
are private. Callback ALBs are outside this Terraform scope. Internal service-discovery hops to
Loki/Prometheus currently use private-network HTTP after the documented trust boundary; this
relies on VPC/security-group integrity and must be revisited where a market requires encryption
for private target hops.

## Residual risks

- The local Grafana boundary uses generated basic-auth accounts. Production identity lifecycle, rotation, recovery, and external HTTPS exposure remain deployment/onboarding responsibilities and are not claimed by B001.
- The shared dispatcher intentionally prioritizes bounded business impact over telemetry delivery;
  sustained callback traffic can reduce other success-event visibility until fairness/rate evidence
  is defined.
- Allowlisted payload strings are not automatically safe from internal SQL/URL prose. Schemas must
  allow stable typed business fields, never exception messages or free-form diagnostic text.
- Custom callback trusted-context adapters and host redirect policies remain service-owned security
  boundaries and require per-service negative tests.
- Stateful Loki, Prometheus, and Grafana remain single-task/non-HA; their outage must remain invisible
  to lending success paths.

## Staging-only or externally owned tests

- Production partner Grafana identity provisioning, password rotation/recovery/session revocation, and onboarding/offboarding lifecycle beyond the generated local accounts.
- Public DNS/ACM hostname, callback ALB 443-only listener, trusted-proxy behavior, WAF/rate policy,
  private task reachability, and direct Loki/Prometheus/Alloy reachability from the internet.
- HTTPS-to-HTTP redirect denial for each service-owned production client configuration, incomplete
  chain/not-yet-valid certificate cases, custom-CA overlap/rollback, ACM renewal, and revocation drills.
- Terraform plan inspection with approved non-production account inputs, followed by network probes;
  no apply was authorized in this review.
- Full-duration callback flood, blackhole, mixed soak, reactive cancellation, heap/GC, component
  restart, disk/retention, backup, and restore profiles.
