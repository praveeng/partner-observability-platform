---
name: grafana-partner-dashboard
description: Review partner-facing Grafana authentication, organization and datasource isolation, dashboard safety, transaction search, timelines, detail views, and SLI presentation. Use for Grafana provisioning, dashboards, datasource proxies, variables, local accounts, and partner UX changes.
---

# Grafana Partner Dashboard

Verify useful partner visibility without treating dashboard variables or datasource settings as authorization.

## Load authoritative requirements

Read `AGENTS.md`, `.agent-state/status.json`, `docs/architecture.md`, `docs/security-invariants.md`, `docs/partner-isolation.md`, `docs/telemetry-contract.md`, `docs/metrics-sli.md`, `docs/acceptance-criteria.md`, ADR 0004, ADR 0005, and ADR 0006. Take current roles, org topology, datasource rules, fields, and SLI semantics from these sources.

## Review workflow

1. Provision two synthetic local partner users in separate organizations with colliding transaction identifiers.
2. Verify anonymous access and self-signup are disabled and partner users have Viewer-only capabilities.
3. Trace each datasource through a server-side gateway bound to the authenticated org's fixed Loki tenant or Prometheus partner slot.
4. Attempt variable manipulation, Explore queries, saved-dashboard edits, direct datasource calls, org switching, and direct backend access.
5. Verify the required partner journeys using only partner-safe fields: transaction search, event timeline, request/response detail, and SLA/SLI dashboard.
6. Confirm dashboards distinguish zero from no data, use approved identifiers as structured metadata rather than labels, and never expose internal-only diagnostics.

## Deterministic checks

```bash
git diff --check
find grafana -type f -name '*.json' -print -exec jq empty {} \;
rg -n -i "anonymous|sign.?up|role|org|datasource|proxy|tenant|partner_slot|explore" grafana docker test docs/enterprise-infrastructure
rg -n "applicationId|loanId|correlationId|requestId|X-Scope-OrgID" grafana
./scripts/test-security.sh
./scripts/test.sh
./scripts/verify-all.sh
```

Inspect every dashboard UID, datasource UID, template variable, link, annotation, query, and transform. Require automated browser/API tests for cross-org denial and direct access, plus query-result assertions for the four required views. Missing provisioning, missing fixtures, `NOT IMPLEMENTED`, non-zero commands, or visual-only/manual evidence is `FAIL` for a claimed dashboard milestone.

## PASS/FAIL criteria

`PASS` requires local authentication, fixed org membership, Viewer authorization, server-enforced datasource isolation, denial of every bypass attempt, no cross-partner values in variables or results, and accurate/searchable partner-safe content for all required views. SLI panels must use the repository's defined models and explicitly render no-data semantics.

`FAIL` on variable-based authorization, client-selected tenant/slot, shared unrestricted datasource, direct backend access, editable partner credentials/roles beyond policy, internal diagnostics disclosure, high-cardinality Loki labels, misleading SLI aggregation, or absent negative tests.

Never weaken roles, queries, tenant fixtures, test assertions, dashboard functionality, or SLI thresholds merely to obtain `PASS`. Fix the provisioning/query design or record the failure.

## Record valid findings

Set `.agent-state/status.json` to `VERIFYING` during the gate, then `READY_FOR_REVIEW` for scoped pass or `IN_PROGRESS` for failure. Keep the schema intact, summarize evidence in `summary`, and put concrete gaps in `nextActions`. Record unresolved UX/security choices in `docs/decisions-needed.md` and milestone changes in `PLANS.md`; obey constitution rules for `BLOCKED` and `COMPLETE`.
