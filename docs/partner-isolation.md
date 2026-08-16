# Partner Isolation

## Security boundary

Partner isolation is enforced server-side. A browser control, dashboard variable, query filter, Loki label, or arbitrary request header is never an authorization boundary.

## Required model

- Authenticated server-side context resolves a canonical partner identity.
- The canonical identity maps through controlled configuration to exactly one Loki tenant per partner.
- Writes carry tenant routing derived only from that trusted mapping.
- Query credentials/policies restrict a principal to its authorized tenant set before query execution.
- Operational cross-tenant access, if later required, uses a separately authorized role and produces auditable records; it is not inherited from partner-facing access.
- Unknown, missing, conflicting, or unmapped identity fails closed: no telemetry is disclosed or routed to a fallback shared tenant.

Prometheus and Grafana isolation designs remain M1 decisions. Any shared metrics must exclude partner-identifying or high-cardinality dimensions unless an approved server-side authorization architecture exists.

## Tests required before release

- A partner cannot write to or query another partner tenant by changing client inputs.
- Missing/unknown identity does not route to another or default partner.
- Concurrent partners do not leak context across threads, pools, batches, retries, or caches.
- Dashboard and direct backend access enforce the same authorization outcome.
- Credential rotation and stale mapping behavior fail closed.

Exact identity source, tenant naming, role topology, and operator access are tracked in `decisions-needed.md`.
