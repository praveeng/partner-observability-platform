---
name: partner-security
description: Verify server-side partner identity, authorization, tenant routing, Grafana access, query isolation, and fail-closed defaults. Use for authentication, context resolution, gateways, tenant maps, datasource access, partner onboarding, or security regression testing.
---

# Partner Security

Prove isolation with negative tests. Configuration appearance is not sufficient evidence.

## Load authoritative requirements

Read `AGENTS.md`, `.agent-state/status.json`, `docs/security-invariants.md`, `docs/threat-model.md`, `docs/partner-isolation.md`, `docs/deployment-model.md`, `docs/acceptance-criteria.md`, ADR 0004, ADR 0005, and applicable unresolved decisions. Take partner identity sources, tenant mappings, authorization boundaries, and environment rules from these files.

## Mandatory attack matrix

Create automated tests for every scenario below and assert both denial and absence of cross-partner data:

- Partner A querying Partner B;
- a spoofed `partnerId` in headers, query parameters, body, MDC, or dashboard variables;
- a spoofed Loki tenant header, including `X-Scope-OrgID` casing/duplication variants;
- Grafana variable manipulation, crafted Explore queries, and saved-dashboard edits;
- direct datasource, Loki, Prometheus, and internal endpoint access attempts;
- the same `applicationId` present in two tenants;
- unknown or missing partner context on ingest and query paths;
- absent, malformed, duplicate, or stale mappings and other unsafe defaults.

Also test local Grafana account-to-org mapping, Viewer-only privileges, disabled anonymous/signup paths, logout/session behavior, query-gateway partner binding, Prometheus partner-slot binding, and audit events for successful and denied sensitive actions.

## Verification workflow

1. Derive two synthetic partners with deliberately colliding transaction identifiers and distinguishable canaries.
2. Trace trusted identity from authentication/configuration to immutable partner context and fixed Loki tenant/Grafana org/Prometheus slot.
3. Attempt every attack through public UI/API paths and direct network paths available to a partner.
4. Query all data surfaces after each attempt. Assert the attacker sees neither records, metric series, labels, autocomplete values, counts, nor error details belonging to the other partner.
5. Inspect security groups, listeners, proxies, headers, logs, and audit records to prove server-side enforcement and fail-closed behavior.

## Commands

```bash
git diff --check
rg -n -i "partnerId|partner_slot|X-Scope-OrgID|tenant|orgId|anonymous|sign.?up|datasource" sure-partner-observability-* alloy loki prometheus grafana test docs/enterprise-infrastructure
./scripts/test-security.sh
./scripts/test.sh
./scripts/verify-all.sh
```

Run Docker Compose integration tests when the repository supplies them, but never use production credentials or endpoints. A missing suite, non-zero exit, `NOT IMPLEMENTED`, or a test without explicit cross-tenant absence assertions is `FAIL` for an implemented isolation claim.

## PASS/FAIL criteria

`PASS` requires every mandatory attack to be denied safely, no cross-tenant datum to appear on any surface, identity to be server-derived, tenant headers to be stripped and reinjected only by trusted components, internal endpoints to be unreachable, and audit evidence to identify the actor and decision without sensitive content.

`FAIL` on client-selected identity, permissive fallback, direct datasource/backend reachability, variable-based authorization, ambiguous missing context, shared query scope, leakage through metadata or errors, or any untested mandatory attack.

Never weaken authorization, network rules, negative assertions, fixtures, or test scope merely to obtain `PASS`. Do not relabel a denial or leak as expected behavior; fix it or record it.

## Record valid findings

Move `.agent-state/status.json` to `VERIFYING` while checks run, then `READY_FOR_REVIEW` for a scoped pass or `IN_PROGRESS` for a failure. Use existing `summary`, `blockers`, and `nextActions` fields only. Record unresolved identity/onboarding choices in `docs/decisions-needed.md`, update `PLANS.md` when milestone evidence changes, and obey the constitution before using `BLOCKED` or `COMPLETE`.
