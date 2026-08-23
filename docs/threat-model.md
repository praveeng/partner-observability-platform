# Threat Model

## Method and scope

This STRIDE-oriented model covers outbound clients, inbound MVC/WebFlux callbacks, explicit plaintext/processing hooks, context propagation, bounded queues/dispatcher, Alloy ingress and processing, Loki, Prometheus, Grafana, tenant-fixed correlation/query gateways, S3/EFS, ECS/IAM/networking, Terraform/configuration, and operator/account workflows. Partner business systems and host callback authentication/idempotency/business processing are outside the platform boundary, but their exchanged data and claimed identifiers are hostile input.

## Assets

- Partner isolation and authorization mappings.
- Partner exchange data and partner-safe telemetry.
- Credentials, cryptographic material, local accounts, datasource/source secrets, and AWS identities.
- HTTPS endpoint integrity, server certificate/hostname validation, ACM listener configuration, and transport-secret confidentiality.
- Business-service availability/latency and bounded JVM resources.
- Telemetry integrity, SLI definitions, dashboards, and configuration provenance.
- Callback authentication outcomes, lifecycle semantics, typed correlation graph, and confidence/coverage presentation.
- Loki S3 objects, Prometheus/Grafana state, internal audit evidence, and Terraform state.

## Actors

- External partner user/system controlling request/body/header/timing/volume.
- Authenticated partner Grafana Viewer, potentially malicious.
- Application developer or misconfigured service.
- Internal platform operator and break-glass administrator.
- Compromised application, Grafana, gateway, Alloy, backend task, dependency, or AWS credential.
- Autonomous agent, which has repository scope but no production authority.

## Trust boundaries

1. Untrusted partner input to authenticated business context.
2. Business domain objects/plaintext to first-stage sanitizer.
3. Callback transport ingress to host authentication/signature validation and trusted callback resolver.
4. Business/callback thread to bounded safe-event queue.
5. Dispatcher across private TLS/load balancer to authenticated Alloy ingress.
6. Ingress source identity plus partner mapping to fixed Alloy pipeline.
7. Alloy to authenticated Loki/Prometheus write endpoints.
8. Partner browser/local account to Grafana organization.
9. Grafana datasource credential to query gateway, fixed tenant/slot, and bounded journey resolver.
10. ECS task roles/network to S3, EFS, Secrets Manager, KMS, and CloudWatch.
11. Terraform/config pipeline to non-production AWS APIs and artifacts.
12. Private ECS egress through controlled NAT/proxy to the partner's validated HTTPS endpoint.
13. Partner callback/browser ingress through the 443-only ALB/ACM boundary to a private ECS target.

## Threats, controls, and verification

| Threat | Attack/failure | Primary controls | Required verification |
| --- | --- | --- | --- |
| Spoofed partner | Forge partner header/MDC/body/route key | Authenticated server resolver; source-partner gateway map; strip/overwrite tenant headers | Negative resolver, ingress, and cross-tenant tests |
| Outbound origin confusion | Send a different partner/host request with the same approved method and path | Required configuration-owned HTTPS origin; exact scheme/host/effective-port/method/path selection; no URI value in telemetry | Cross-origin registry test and plaintext/missing-origin startup rejection |
| Spoofed/wrong-partner callback | Use expected route, forged signature/header/body ID, or conflicting authenticated identity | Host auth result only; callback resolver after security chain; configured route-partner consistency; no fallback tenant | Signature/auth failure, wrong-partner, conflicting-route tests with queue absence |
| Ambiguous callback route | Configure variable templates that match the same request and rely on list order | Startup rejection of any same-method literal/variable route overlap | Failing-first overlapping-template configuration regression |
| TLS server impersonation | Untrusted/expired/wrong-host certificate, forged DNS/endpoint, private-CA misuse | Standard client chain and hostname validation; reviewed scoped custom CA; approved endpoint manifest | Synthetic unknown-CA, expired, chain, hostname, and DNS/endpoint tests for all three clients |
| TLS downgrade or bypass | HTTP endpoint, HTTPS-to-HTTP redirect, trust-all manager, permissive hostname verifier | HTTPS-only validation; no port 80; redirect downgrade denial; source/static checks; SDK TLS immutability | Configuration mutation, redirect, trust-all source scan, and enabled/disabled behavior comparison |
| TLS-setting mutation by instrumentation | Starter replaces client/connector/request factory or changes SSL/pinning/redirect behavior | Filter/interceptor-only ownership contract; exactly-once client reuse; no SSL setter calls | TLS configuration identity/effective behavior before and after starter activation |
| Direct ECS ingress | Bypass ALB/WAF/TLS by reaching task ENI/public IP | Private subnets, no public IP, ALB-SG-only target ingress, no public task DNS/route | Terraform plan/reachability tests and external connection denial |
| Callback forwarding-header spoof | Send `X-Forwarded-Proto=https` over an untrusted path | Trusted-proxy configuration plus ALB SG path; TLS not callback identity | Direct/spoofed header tests with no trusted receipt/tenant fallback |
| TLS secret disclosure | Key/trust-store bytes/password/path, certificate chain/private material in config, error, telemetry, state | Secrets Manager/ACM, ARN references, pre-queue removal, no message/chain emission | Git/config/plan/queue/wire/Loki/metric/dashboard sentinel scans |
| Certificate renewal/rotation failure | Expired ACM/custom CA, partial trust rollout, listener outage | ACM managed renewal, expiry alarms, attach-before-remove, bounded CA overlap, staged rollback | Synthetic ACM/custom-CA rotation and rollback drills |
| Cross-tenant Grafana query | Switch org, datasource, header, Loki tenant, PromQL matcher | One org/datasource per partner; fixed credential mapping; Loki header injection; prom-label-proxy; SG denial | Browser/API/direct-backend/PromQL bypass tests |
| Secret/PII disclosure | Nested aliases, values, exception text, rendered log | Path allowlist, removal/masking/value detectors, exact unformatted log-template registry, no formatted messages/throwables, Alloy second stage | Property/fuzz corpus plus selected/non-selected/exception/Authorization/Base64 logs at queue, wire, Loki, metrics, diagnostics |
| Binary/Base64 evasion | MIME lie, byte type, data URI, padded/unpadded encoding, PDF signature | Type/content/magic/UTF-8/Base64 detection before queue; omit ambiguity | Adversarial candidate corpus and memory bounds |
| Encryption boundary expansion | SDK decrypts or retains plaintext/key | No decryption; explicit hook only at existing plaintext point; immediate safe projection | Integration tests with ciphertext/key sentinels and lifetime checks |
| Availability coupling | Backend/DNS/TLS/auth stalls business | Non-blocking producer; dispatcher-only I/O/timeouts; bounded retry; no readiness dependency | Backend blackhole/latency/failure injection with business assertions |
| Resource exhaustion | Deep/large payload, queue flood, meter/label explosion | Hard traversal/event/queue byte bounds, rate buckets, precomputed meters/cardinality | Heap/CPU/load tests and config rejection |
| Reactive/context leakage | Thread pool reuses partner context; cancellation duplicates | Reactor-context authority, scoped MDC, finally cleanup, immutable per-event context, terminal guard | Concurrent randomized MVC/Reactor/executor tests |
| Callback body semantic change | Observability consumes body first, invalidates signature, changes MVC/WebFlux demand, or retains buffer | Post-auth ordering, typed advice, bounded tee-as-consumed, metadata fallback, explicit API, buffer release | Signature-order, byte equality, async dispatch, cancellation/backpressure/leak tests |
| False lifecycle claim | Treat receipt/202/2xx as authentication or processing completion | Separate immutable receipt/auth/validation/start/terminal/response facts; explicit semantic API | Accepted-before-complete, parsing/process/write failure tests |
| Replay/duplicate confusion | Retry is treated as original or SDK deduplicates business work | New attempt ID per delivery; trusted business idempotency result; no SDK business dedup | Duplicate/retry/out-of-order test matrix |
| Correlation injection/collision | Attacker-supplied ID joins another journey or expands query cost | Authenticated tenant fixed first; typed validators; exact matching; stable/weak confidence; conflict stop; hard rounds/key/result/time bounds | Colliding identifiers across partners/within tenant, malformed seed, conflict and saturation tests |
| Body semantic change | Interceptor consumes/serializes stream, changes backpressure | Tee only as application consumes; no repeat of OkHttp body; payload opt-in | Byte-for-byte client contract, cancellation, one-shot/duplex tests |
| Telemetry injection | Control chars/duplicate fields/forged labels/schema | Fixed schema, canonical JSON, field/token validation, Alloy allowlists | Parser ambiguity and label-injection tests |
| Source credential theft | Compromised service emits for other partner | Per-source secret, exact allowed partner set, rotation/revocation, SG boundary | Stolen credential cannot reach unassigned tenant |
| Backend/header bypass | Direct Loki/Prometheus call or combined tenant query | Private SGs, auth proxy, multi-tenant query disabled, fixed header map | Network and API policy tests |
| Prometheus write pollution | Service exposes forged partner_slot/labels | Alloy `honor_labels=false`, relabel overwrite/drop, contract-only scrape | Malicious metrics endpoint test |
| Config/upgrade tampering | Reassign tenant/slot, unreviewed image, unsafe schema | Manifest validation, immutable digests, Git/ADR review, uniqueness/tombstones | Golden/config mutation tests and plan review |
| Retention failure | Compactor stopped or S3 version keeps data | Compactor health alert, 384h config, 18d lifecycle, versioning off | Aged synthetic object/end-to-end deletion test |
| Local-account takeover | Weak/shared password, brute force, stale user | Individual accounts, 16+ password, TLS, throttling/WAF, disable workflow | Account lifecycle/session/brute-force configuration checks |
| Operator abuse | Admin queries multiple partners or edits datasource | Separate named break-glass access, one-tenant gateway, ticket, immutable provisioning, logs | Access review and audited synthetic operator exercise |
| Audit repudiation | OSS logs incomplete/tampered | Git/CloudTrail/ALB/CloudWatch/config digests; explicitly no ledger claim | Evidence-chain review; decide formal requirement |
| Supply-chain compromise | Malicious SDK/container/dependency | Pinned versions/digests, dependency/SBOM/signature scanning, staged promotion | Reproducible build and image provenance gates |
| State loss/outage | Single Loki/Prometheus/Grafana task fails | S3/EFS/backups, ECS restart, documented non-HA, no business dependency | Restore/restart drills and honest availability reporting |

## Abuse cases

The design assumes an attacker can choose field case/separators/Unicode, JSON duplicate keys, nesting, size, content types, encodings, identifiers, callback ordering/replay/timing, routes, URLs, redirects, forwarding headers, status bodies, exception-triggering input, concurrency, cancellations, and volume. It assumes a network peer can present an untrusted, expired, incomplete, or wrong-host certificate and can try to induce HTTP downgrade or direct task access. It assumes signature/authentication can fail before a trusted context exists, a callback can arrive after timeout or before earlier telemetry is visible, and processing can finish after a 202 response. It assumes observability backends can be absent, slow, compromised, or return malformed data. It assumes a partner Viewer can issue arbitrary queries supported by its Grafana datasource.

A fully compromised onboarded application can access its configured source credential and emit fabricated partner-safe events for partners that service is authorized to serve. The gateway limits blast radius to that allowlist but cannot prove business truth. Reducing this residual risk requires workload identity/attestation and domain-source signing beyond the initial scope.

## Residual risk and explicit limitations

- Initial single stateful ECS tasks cause dashboard/ingest downtime during failure/upgrades; business service remains available.
- Local Grafana accounts may not satisfy production MFA/federation policy and Grafana OSS does not provide a complete tamper-proof audit facility.
- Sanitization reduces disclosure risk but configuration mistakes can omit useful data or incorrectly allow a business field; two-stage tests and review are mandatory.
- Structured-metadata identifier searches scan bounded time/streams and may be slower than indexed labels by design.
- Journey correlation is best-effort within the 16-day telemetry window. Missing bridge events, reused identifiers, or bound exhaustion yields explicit partial/weak/conflict results; it is not a business source of truth.
- At-most-once export loses events; a single retry may duplicate. Telemetry is not financial/audit evidence.
- A compromised internal operator/backend role can access data within its IAM/network permissions; least privilege, named access, and audit evidence reduce but do not eliminate this risk.
- TLS terminates at the approved ALB boundary; traffic inside that private target boundary is not cryptographically end-to-end partner-authenticated unless the host service separately enables reviewed backend TLS/mTLS. Security groups, private routing, and host callback authentication remain mandatory.
- Revocation behavior and private-CA availability depend on the approved corporate/partner PKI policy. No mTLS identity or revocation guarantee is claimed until a follow-up ADR resolves certificate lifecycle requirements.
- A sustained authenticated callback workload can consume the shared normal-priority telemetry queue and reduce visibility for other successful observations. Event/byte bounds preserve business availability, but per-workload fairness is not yet an accepted guarantee; the exact callback-flood profile remains mandatory.
- The partner-facing Grafana/query authorization boundary is not implemented. Underlying fixed-tenant Loki and fixed-slot Prometheus evidence is not authorization evidence for a partner UI or API. Partner access must remain disabled until M7 exists and passes the adversarial matrix in `security-review.md`.
- Custom callback resolver implementations can inspect the host request/exchange. They are trusted adapters and require onboarding-specific negative evidence that body, route, query, forwarding, and tenant headers cannot influence the authenticated partner result.

Unresolved organizational inputs and owners are listed in `decisions-needed.md`. None permits weakening the repository constitution.
