---
name: loki-isolation
description: Review and test Loki multi-tenancy, trusted tenant routing, labels, structured metadata, retention, S3 persistence, and network exposure. Use for Loki or Alloy configuration, query gateways, retention, tenant onboarding, storage, and log-schema changes.
---

# Loki Isolation

Verify the complete ingest and query trust boundary for one Loki tenant per partner.

## Load authoritative requirements

Read `AGENTS.md`, `.agent-state/status.json`, `docs/architecture.md`, `docs/telemetry-contract.md`, `docs/payload-policy.md`, `docs/partner-isolation.md`, `docs/deployment-model.md`, ADR 0004, ADR 0005, and relevant unresolved decisions. Derive the current label allowlist, tenant mapping, storage schema, and retention settings from those files rather than copying them into this skill.

## Review workflow

1. Trace ingest from trusted partner context through the application envelope, Alloy routing, tenant header injection, Loki, and S3.
2. Trace queries from a local Grafana user through org-bound datasource and query gateway to a fixed tenant.
3. Compare onboarding configuration across application, Alloy, query gateway, Grafana, the central
   infrastructure contract/evidence, and audit records; require one consistent mapping.
4. Exercise two synthetic tenants with colliding transaction identifiers and distinct canaries.
5. Verify retention, deletion, storage encryption, and rollback behavior without applying production changes.

## Required deterministic checks

- Loki multi-tenancy is enabled; untrusted tenant headers are stripped before a trusted proxy injects a fixed value.
- Every configured partner maps to exactly one market/environment-scoped Loki tenant, and no fallback/shared tenant exists.
- Loki and its administrative/query endpoints are internal-only; partners cannot bypass Grafana/query gateway.
- Normal labels exactly follow the authoritative low-cardinality allowlist. Transaction identifiers appear only as structured metadata or another approved high-cardinality mechanism.
- Structured metadata is enabled and queryable for application/loan/correlation/request identifiers without promoting them to labels.
- Alloy applies second-stage sanitization and bounded delivery; Loki failure cannot block business traffic.
- Loki schema, S3 persistence, retention, delete delay, and S3 lifecycle match the authoritative deployment contract.
- Tenant creation, mapping change, access denial, and retention change are auditable without exposing payload data.

Run:

```bash
git diff --check
rg -n -i "auth_enabled|X-Scope-OrgID|tenant|structured_metadata|schema|retention|delete|s3" alloy loki docker grafana test docs/enterprise-infrastructure
rg -n "applicationId|loanId|correlationId|requestId" alloy loki grafana test sure-partner-observability-*
rg -n -i "publicly_accessible|assign_public_ip|0\.0\.0\.0/0|ingress|security_group" docker docs/enterprise-infrastructure
./scripts/test-security.sh
./scripts/verify-all.sh
```

Validate parsable YAML/JSON/HCL with repository tools when configured, and run the two-tenant Docker integration test when implemented. Missing tests, parse failures, non-zero commands, or `NOT IMPLEMENTED` are `FAIL` for claimed Loki readiness.

## PASS/FAIL criteria

`PASS` requires a unique tenant for every partner fixture, trusted fixed routing in both directions, no direct Loki path, correct current label/metadata contract, zero cross-tenant results under collision tests, and retention/storage configuration matching the repository sources.

`FAIL` on a shared or fallback tenant, client-controlled scope header, direct exposure, label-cardinality violation, inconsistent onboarding map, unsafe payload surviving Alloy, unbounded retry/buffering, retention mismatch, or missing negative evidence.

Never weaken tenant isolation, sanitization, retention checks, collision fixtures, or assertions merely to obtain `PASS`. Do not solve a label test by removing searchability; use the approved metadata mechanism.

## Record valid findings

Use `.agent-state/status.json` lifecycle `VERIFYING` during checks, then `READY_FOR_REVIEW` on scoped pass or `IN_PROGRESS` on failure. Preserve its schema and record evidence/remediation in `summary` and `nextActions`. Record unresolved tenant/schema choices in `docs/decisions-needed.md` and milestone evidence in `PLANS.md`. Use `BLOCKED` and `COMPLETE` only as permitted by `AGENTS.md`.
