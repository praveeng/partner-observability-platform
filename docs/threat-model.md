# Threat Model

## Method and scope

This STRIDE-oriented model covers Spring instrumentation, explicit plaintext hooks, context propagation, bounded queues/dispatcher, Alloy ingress and processing, Loki, Prometheus, Grafana, query gateways, S3/EFS, ECS/IAM/networking, Terraform/configuration, and operator/account workflows. Partner business systems themselves are outside the platform boundary, but their exchanged data is hostile input.

## Assets

- Partner isolation and authorization mappings.
- Partner exchange data and partner-safe telemetry.
- Credentials, cryptographic material, local accounts, datasource/source secrets, and AWS identities.
- Business-service availability/latency and bounded JVM resources.
- Telemetry integrity, SLI definitions, dashboards, and configuration provenance.
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
3. Business thread to bounded safe-event queue.
4. Dispatcher across private TLS/load balancer to authenticated Alloy ingress.
5. Ingress source identity plus partner mapping to fixed Alloy pipeline.
6. Alloy to authenticated Loki/Prometheus write endpoints.
7. Partner browser/local account to Grafana organization.
8. Grafana datasource credential to query gateway and fixed tenant/slot.
9. ECS task roles/network to S3, EFS, Secrets Manager, KMS, and CloudWatch.
10. Terraform/config pipeline to non-production AWS APIs and artifacts.

## Threats, controls, and verification

| Threat | Attack/failure | Primary controls | Required verification |
| --- | --- | --- | --- |
| Spoofed partner | Forge partner header/MDC/body/route key | Authenticated server resolver; source-partner gateway map; strip/overwrite tenant headers | Negative resolver, ingress, and cross-tenant tests |
| Cross-tenant Grafana query | Switch org, datasource, header, Loki tenant, PromQL matcher | One org/datasource per partner; fixed credential mapping; Loki header injection; prom-label-proxy; SG denial | Browser/API/direct-backend/PromQL bypass tests |
| Secret/PII disclosure | Nested aliases, values, exception text, rendered log | Path allowlist, removal/masking/value detectors, no arbitrary logs, Alloy second stage | Property/fuzz corpus at queue, wire, Loki, metrics, diagnostics |
| Binary/Base64 evasion | MIME lie, byte type, data URI, padded/unpadded encoding, PDF signature | Type/content/magic/UTF-8/Base64 detection before queue; omit ambiguity | Adversarial candidate corpus and memory bounds |
| Encryption boundary expansion | SDK decrypts or retains plaintext/key | No decryption; explicit hook only at existing plaintext point; immediate safe projection | Integration tests with ciphertext/key sentinels and lifetime checks |
| Availability coupling | Backend/DNS/TLS/auth stalls business | Non-blocking producer; dispatcher-only I/O/timeouts; bounded retry; no readiness dependency | Backend blackhole/latency/failure injection with business assertions |
| Resource exhaustion | Deep/large payload, queue flood, meter/label explosion | Hard traversal/event/queue byte bounds, rate buckets, precomputed meters/cardinality | Heap/CPU/load tests and config rejection |
| Reactive/context leakage | Thread pool reuses partner context; cancellation duplicates | Reactor-context authority, scoped MDC, finally cleanup, immutable per-event context, terminal guard | Concurrent randomized MVC/Reactor/executor tests |
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

The design assumes an attacker can choose field case/separators/Unicode, JSON duplicate keys, nesting, size, content types, encodings, identifiers, URLs, status bodies, exception-triggering input, concurrency, cancellations, and volume. It assumes observability backends can be absent, slow, compromised, or return malformed data. It assumes a partner Viewer can issue arbitrary queries supported by its Grafana datasource.

A fully compromised onboarded application can access its configured source credential and emit fabricated partner-safe events for partners that service is authorized to serve. The gateway limits blast radius to that allowlist but cannot prove business truth. Reducing this residual risk requires workload identity/attestation and domain-source signing beyond the initial scope.

## Residual risk and explicit limitations

- Initial single stateful ECS tasks cause dashboard/ingest downtime during failure/upgrades; business service remains available.
- Local Grafana accounts may not satisfy production MFA/federation policy and Grafana OSS does not provide a complete tamper-proof audit facility.
- Sanitization reduces disclosure risk but configuration mistakes can omit useful data or incorrectly allow a business field; two-stage tests and review are mandatory.
- Structured-metadata identifier searches scan bounded time/streams and may be slower than indexed labels by design.
- At-most-once export loses events; a single retry may duplicate. Telemetry is not financial/audit evidence.
- A compromised internal operator/backend role can access data within its IAM/network permissions; least privilege, named access, and audit evidence reduce but do not eliminate this risk.

Unresolved organizational inputs and owners are listed in `decisions-needed.md`. None permits weakening the repository constitution.
