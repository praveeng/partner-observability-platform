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

## Data-class separation

- Raw data exchanged with a partner remains business data and never becomes a queued telemetry object.
- Only a safe bounded projection produced by the first-stage sanitizer is partner-safe derived observability.
- Internal-only logs, stack traces, configuration, credentials, audit events, infrastructure identity, and operator data never enter partner Loki tenants/datasources.
- Partner telemetry is not an audit ledger and has no guaranteed delivery semantics.

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
- Operator cross-tenant access is separate, named, one tenant at a time, and auditable.

## Cardinality and query safety

- `applicationId`, `loanId`, `correlationId`, `requestId`, `partnerReference`, `eventId`, and `interactionId` are never Loki labels or metric labels.
- Approved transaction identifiers use Loki structured metadata and validated bounded values.
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

## Retention and deployment

- Partner Loki telemetry is logically deleted after 384 hours by the compactor, with only the documented two-hour deletion delay; S3 lifecycle is a backstop, not an extension.
- Telemetry-object S3 versioning is disabled so deleted data is not retained as noncurrent versions.
- Each account/market/environment stack is independent; no cross-market/environment bucket, tenant, credential, or datasource.
- DEV calls only mock partner services.
- AWS ECS and Terraform are mandatory. Kubernetes and Helm are prohibited.
- Agents never deploy to production, obtain production credentials, push, or merge.

## Verification rule

Tests prove absence and isolation at the earliest queue boundary and every downstream sink, including malformed/error/fallback paths. A green downstream scan cannot compensate for raw prohibited data entering a queue. Missing verification is `NOT IMPLEMENTED`, never a pass.
