# Security Invariants

These properties are non-configurable acceptance gates. ADRs and onboarding manifests may strengthen but cannot waive them.

## Availability and resource safety

- Business availability outranks telemetry completeness.
- Business threads never perform or wait for network calls to Alloy, Loki, Prometheus, Grafana, AWS, DNS, or a remote configuration service.
- Producers make one bounded local sanitization attempt and a non-blocking queue `offer`; they never call blocking queue operations, sleep, retry, or evict another event.
- Every queue, byte buffer, batch, retry slot, executor, meter registry, partner/API registry, payload traversal, and shutdown drain has a hard bound.
- Saturation, timeout, malformed backend response, dispatcher failure, and shutdown timeout drop telemetry.
- Every observability exception is caught at the outer integration boundary and never replaces, suppresses, or changes a business response/exception.
- Observability backends are not partner-service readiness dependencies.
- Callback receipt, acknowledgement, response, and background-processing observations use the same bounded non-blocking producer path; no callback thread waits for correlation lookup or telemetry export.

## Data-class separation

- Raw data exchanged with a partner remains business data and never becomes a queued telemetry object.
- Only a safe bounded projection produced by the first-stage sanitizer is partner-safe derived observability.
- Internal-only logs, stack traces, configuration, credentials, audit events, infrastructure identity, and operator data never enter partner Loki tenants/datasources.
- Partner telemetry is not an audit ledger and has no guaranteed delivery semantics.
- Receipt, authentication/validation, processing completion/failure, and response transmission are separate facts. One must never be inferred from another solely from HTTP status.

## Disclosure control

- Unknown or ambiguous content fails closed and is omitted.
- Credentials, secrets, Authorization, tokens/JWTs, cookies, API keys, private/signing/encryption keys, OTP/authentication PINs, and card data are removed completely.
- Phone, email, bank account, national identifiers, and addresses are masked according to `payload-policy.md`.
- Documents, images, PDFs, signatures, archives, streams, bytes, binary content, and Base64/encoded binary are never captured.
- Type/content/size/binary/Base64 rejection happens before a safe event or queue entry exists.
- Oversized payloads are omitted as a whole; capturing a prefix is forbidden because secrets may occur after the prefix.
- Truncation can apply only to an already-approved safe display string; it cannot make prohibited data safe.
- Raw input, rejected keys/values, arbitrary `toString()`, exception messages, response error bodies, and stack traces cannot appear in partner telemetry or sanitizer diagnostics.
- First-stage sanitization is authoritative. Alloy repeats validation/sanitization and drops on error; it never forwards an original fallback line.
- Only synthetic data is permitted in repository tests and local environments.

## Capture boundary

- Default onboarding is disabled; first enablement is metadata-only.
- Full sanitized capture requires a per-API/direction reviewed field schema and remains subject to all removal/exclusion/size rules.
- Instrumentation never decrypts data for observability and never captures ciphertext as payload.
- Explicit pre-encryption/post-decryption hooks operate only where business code already has authorized plaintext, sanitize immediately, and never retain source objects.
- Callback request capture occurs only after trusted partner authentication/decryption and before business processing; callback response capture occurs after processing and before serialization/encryption. Uncertain ordering reduces capture to metadata-only/off.
- Automatic interceptors do not serialize one-shot, streaming, duplex, reactive, binary, or unknown bodies for observation.
- Arbitrary existing SLF4J rendered messages remain internal-only. Only marked structured safe-log events pass the normal sanitizer.

## Partner isolation

- Partner identity is resolved from authenticated server-side context and startup configuration, never directly from a client field, MDC, dashboard variable, or telemetry line.
- One opaque Loki tenant exists per partner per market/environment; there is no default/shared tenant and tenant IDs are never reused.
- The Alloy ingress authenticates source service and authorizes the exact source-partner pair before routing to a fixed partner pipeline.
- Client `X-Scope-OrgID`, tenant, slot, and routing headers are stripped and overwritten at trusted gateways.
- Loki has no public/direct access; an authenticating proxy injects the tenant because Loki itself is not the authentication boundary.
- Prometheus partner queries pass through an authenticated label-enforcement proxy that pins the configured bounded partner slot.
- One Grafana organization and fixed proxy datasources exist per partner. Partner users have Viewer access to exactly one organization and never receive admin/editor/backend credentials.
- Unknown, missing, disabled, conflicting, or stale mapping fails closed for capture and query.
- Callback route/body/header values cannot establish partner identity. Authentication/signature failure or a callback belonging to the wrong partner produces no partner record and no expected-partner fallback tenant.
- Operator cross-tenant access is separate, named, one tenant at a time, and auditable.

## Cardinality and query safety

- `applicationId`, `loanId`, `originalCorrelationId`, `partnerReferenceId`, `externalTransactionId`, `callbackReferenceId`, `requestId`, `eventId`, `interactionId`, and `callbackAttemptId` are never Loki labels or metric labels.
- Approved transaction identifiers use Loki structured metadata and validated bounded values.
- Correlation occurs only after datasource authentication fixes one tenant. It uses typed exact-match identifiers, a required retained time range, and hard expansion/result limits; it never authorizes access, chooses a tenant, or controls business deduplication.
- Loki uses at most the fixed eight labels defined by the contract; user/config input cannot create label names/values outside bounded registries.
- The only partner metric dimension is the opaque, configured `partner_slot` with a hard maximum of 64 per market stack.
- Meter creation is configuration-fixed and rejected when calculated series budgets exceed the documented caps.
- Search requires an authorized datasource, bounded time range, and low-cardinality selector before structured-metadata filtering.

## Authentication, credentials, and audit

- Grafana local accounts are individual, non-shared, partner-Viewer accounts; anonymous access and self-signup are disabled.
- Datasource/source passwords, certificates, and keys live in AWS Secrets Manager and never in manifests, Terraform state values committed to Git, logs, dashboards, or telemetry.
- TLS protects external/private load-balancer hops; S3/EFS and backups are encrypted.
- Partner-visible actions cannot mutate provisioned dashboards/datasources or access internal operator surfaces.
- Configuration, infrastructure, secret access, account lifecycle, gateway denial, and operator access produce internal-only evidence; no claim of a tamper-proof audit ledger is made.

## External transport security

- Every external partner API request, acknowledgement/response, callback/webhook, and partner Grafana session uses HTTPS/TLS in DEV, STAGE, and PROD. ECS DEV mocks use HTTPS.
- Plain HTTP is permitted only for an explicitly marked `LOCAL_SYNTHETIC` fixture on loopback or an isolated Docker network with no real-partner/deployed-environment route.
- External callback and Grafana ALBs expose 443/HTTPS only. Port 80 has no listener and no security-group rule; redirect-only HTTP is not permitted because it still creates a plaintext first hop.
- ALB/ACM is the approved external TLS termination boundary. Callback TLS transport does not replace host authentication/signature verification or establish a partner tenant.
- Callback/Grafana ECS targets are private, have no public IP, and accept application traffic only from the owning ALB security group. Direct internet reachability to ECS tasks is prohibited.
- RestTemplate, WebClient, and OkHttp retain their service-owned standard certificate-path validation and hostname verification. Trust-all managers, permissive hostname verifiers, certificate-validation bypass, and HTTP downgrade/fallback are prohibited.
- The starter never creates, installs, assigns, mutates, relaxes, or bypasses an `SSLContext`, `SSLSocketFactory`, `TrustManager`, `HostnameVerifier`, WebClient connector/`SslProvider`, OkHttp certificate pinner/connection specification, TLS policy, proxy, or redirect policy.
- A custom partner CA is security-reviewed, scoped to the host integration, delivered through an approved read-only secret/artifact mechanism, and never disables hostname, validity, chain, or protocol checks. Failure cannot fall back to trust-all or HTTP.
- ACM/private/client keys, keystore or trust-store bytes/passwords, certificate private material, session/signature secrets, and URI credentials never enter telemetry, metrics, logs, dashboards, Git, generated manifests, or Terraform values/state committed to Git.
- Partner-safe TLS diagnostics contain only configured API/attempt/duration/correlation metadata and a bounded structured outcome/failure enum. Exception messages, peer URLs/addresses, certificate chains/subjects/issuers/SANs/serials/fingerprints, trust-store paths, cipher debug output, and key material are prohibited.
- TLS validation failures preserve the host client's original business error. Observability does not retry, suppress, replace, or transform it, and an inbound handshake failure before trusted callback context creates no partner record.
- Future mTLS may plug into the existing host-owned client TLS and callback trust-adapter boundaries, but the starter never owns keys, certificates, key managers, trust managers, issuance, revocation, or rotation.

## Retention and deployment

- Partner Loki telemetry is logically deleted after 384 hours by the compactor, with only the documented two-hour deletion delay; S3 lifecycle is a backstop, not an extension.
- Telemetry-object S3 versioning is disabled so deleted data is not retained as noncurrent versions.
- Each account/market/environment stack is independent; no cross-market/environment bucket, tenant, credential, or datasource.
- DEV calls only mock partner services.
- AWS ECS and Terraform are mandatory. Kubernetes and Helm are prohibited.
- Agents never deploy to production, obtain production credentials, push, or merge.

## Verification rule

Tests prove absence and isolation at the earliest queue boundary and every downstream sink, including malformed/error/fallback paths. A green downstream scan cannot compensate for raw prohibited data entering a queue. Missing verification is `NOT IMPLEMENTED`, never a pass.

Callback tests additionally prove separate receipt/processing facts, authenticated context before partner emission, wrong-partner denial, safe parsing/auth/write failure paths, retry/duplicate attempts, background context restoration, and tenant-fixed bounded correlation across late/out-of-order records.
