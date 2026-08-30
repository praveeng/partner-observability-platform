---
name: architecture-review
description: Review proposed or implemented platform architecture, module boundaries, dependency direction, telemetry flow, and deployment exposure against the repository constitution. Use for architecture changes, ADRs, cross-module designs, telemetry transport changes, and milestone design reviews.
---

# Architecture Review

Perform an evidence-based architecture gate. Review only unless the user also asks for changes.

## Load authoritative requirements

From the repository root, read these files completely before judging the change:

- `AGENTS.md` and `PLANS.md`
- `docs/product-requirements.md`, `docs/architecture.md`, `docs/security-invariants.md`, `docs/threat-model.md`
- `docs/telemetry-contract.md`, `docs/partner-isolation.md`, `docs/deployment-model.md`
- every applicable ADR in `docs/decisions/`
- `docs/acceptance-criteria.md`, `docs/decisions-needed.md`, and `.agent-state/status.json`

Treat those files as authoritative. Do not copy their mutable numeric settings into new review policy. If they conflict, fail the review and record the conflict in `docs/decisions-needed.md`.

## Review workflow

1. Identify the claimed scope, affected data boundary, request-thread behavior, dependency direction, and deployment exposure.
2. Trace data from partner exchange through sanitization, bounded dispatch, Alloy, storage, query gateways, and Grafana.
3. Inspect source, configuration, the enterprise infrastructure requirements/available central
   evidence, dashboards, and tests relevant to that trace. Do not infer safety from design prose
   when implementation evidence exists.
4. Run the deterministic searches and the applicable repository checks below.
5. Produce a requirement-to-evidence table with `PASS`, `FAIL`, or `NOT APPLICABLE` per row. `NOT IMPLEMENTED`, missing evidence, or an untested required path is `FAIL`.

## Mandatory rejection conditions

Return `FAIL` if any of these exists:

- synchronous Loki or Alloy access from a business request thread, including synchronous retries, flushes, health gates, or acknowledgements;
- an unbounded telemetry queue, executor queue, retry buffer, batch, or in-memory payload accumulator;
- Loki exposed to partners, the public internet, or an untrusted client path without the fixed server-side query boundary;
- partner identity, tenant ID, or `X-Scope-OrgID` accepted from client-controlled input;
- `applicationId`, `loanId`, `correlationId`, `requestId`, or another transaction identifier used as a normal Loki label;
- business code coupled directly to Grafana, Loki, Prometheus, or Alloy internals;
- an observability exception, queue wait, or backend outage able to change business response behavior;
- any Kubernetes or Helm dependency.

Also verify bounded queues, drop-on-saturation behavior, exception containment, data-class
separation, server-side partner isolation, and the configuration-driven onboarding and centralized
ECS/Terraform contract required by the authoritative documents.

## Deterministic checks

Run from the repository root and inspect every hit in changed production paths:

```bash
git diff --check
rg -n -i 'loki|alloy|grafana|prometheus|X-Scope-OrgID' sure-partner-observability-* alloy loki prometheus grafana docker docs/enterprise-infrastructure
rg -n 'applicationId|loanId|correlationId|requestId' alloy loki grafana sure-partner-observability-* test
rg -n 'LinkedBlockingQueue|SynchronousQueue|newCachedThreadPool|Executors\.|block\(|join\(|get\(' sure-partner-observability-* --glob '*.java'
rg -n -i 'kubernetes|k8s|helm' . --glob '!docs/**' --glob '!.git/**'
./scripts/verify-all.sh
```

Search hits are review prompts, not automatic failures, except prohibited Kubernetes/Helm artifacts. Resolve queue capacities, blocking calls, labels, identity sources, and external-client references by inspecting the surrounding code. Treat a missing script, non-zero exit, or `NOT IMPLEMENTED` as failed evidence for any claim that depends on it.

## Verdict

`PASS` only when every applicable invariant has concrete design or implementation evidence, all applicable checks pass, and no mandatory rejection condition exists. Otherwise return `FAIL` with file-and-line evidence, affected invariant, impact, and smallest compliant remediation.

Never weaken, delete, skip, quarantine, or relax tests, thresholds, security controls, or acceptance criteria merely to obtain `PASS`. New exceptions require an explicit ADR and must still comply with `AGENTS.md`.

## Record valid findings

Update existing fields in `.agent-state/status.json`: set `VERIFYING` while checks run, then `READY_FOR_REVIEW` on a scoped pass or `IN_PROGRESS` on a failure; summarize evidence in `summary` and remediation in `nextActions`. Use `BLOCKED` only under the constitution's repeated-blocker rule, and never mark the whole product `COMPLETE` for a scoped review. Record unresolved architectural choices in `docs/decisions-needed.md` and milestone impact in `PLANS.md`. Do not invent new state fields.
