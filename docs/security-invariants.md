# Security Invariants

These requirements are always true, regardless of configuration, backend health, or input shape.

## Availability

- Business availability outranks telemetry completeness.
- Business threads do not synchronously call or wait for Alloy, Loki, Prometheus, or Grafana.
- Every queue and executor is bounded. Saturation drops telemetry and increments a safe, bounded counter.
- Observability exceptions are caught at the integration boundary and never propagate to business logic.

## Disclosure control

- Unknown unsafe content is omitted by default.
- Credentials, secrets, OTPs, and card data are completely removed.
- Phone, email, account number, national identifier, and address data is masked according to an approved deterministic policy.
- Images, documents, PDFs, raw binary, and Base64-like content are never captured.
- Binary/type/size/Base64 exclusion happens before any telemetry queue; unsafe raw values cannot appear in diagnostic fallback logs.
- Only synthetic test data is permitted in the repository and automated environments.

## Isolation

- Partner identity is derived from authenticated, server-side trusted context.
- One Loki tenant is used per partner. Queries, writes, dashboards, and operational tooling may not bypass the tenant boundary.
- Client-supplied filters, labels, headers, or dashboard variables are not authorization.

## Cardinality

- `applicationId`, `loanId`, `correlationId`, and `requestId` are prohibited as normal Loki labels.
- Approved high-cardinality transaction identifiers use structured metadata or another reviewed mechanism.
- Metrics dimensions and Loki indexed labels must be bounded and documented before introduction.

## Deployment and credentials

- Infrastructure is Terraform-managed and targets AWS ECS; no Kubernetes or Helm assets are allowed.
- Agents do not deploy to production, obtain production credentials, or commit secrets/state.

Any exception requires changing the project constitution; an ADR alone cannot waive these invariants.
