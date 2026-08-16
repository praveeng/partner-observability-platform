---
name: test-adequacy
description: Assess whether automated tests prove the requirements and risks claimed by a change or milestone. Use for test plans, pull-request review, milestone verification, acceptance gaps, flaky or skipped tests, and before declaring implementation ready for review.
---

# Test Adequacy

Judge evidence strength, not test count or coverage percentage alone.

## Load authoritative requirements

Read `AGENTS.md`, `PLANS.md`, `.agent-state/status.json`, `docs/acceptance-criteria.md`, `docs/security-invariants.md`, `docs/threat-model.md`, `docs/telemetry-contract.md`, `docs/payload-policy.md`, `docs/metrics-sli.md`, `docs/partner-isolation.md`, `docs/deployment-model.md`, all applicable ADRs, and the implementation under review.

## Build a traceability matrix

For every applicable requirement, invariant, ADR consequence, threat mitigation, and milestone exit criterion, record:

- requirement source and section;
- production path and failure mode;
- test file and test name;
- layer: unit, integration, security, performance, end-to-end, or static configuration;
- positive, negative, boundary, fault-injection, and concurrency evidence;
- deterministic result and artifact location.

An untested applicable row is `FAIL`. A mocked unit test cannot be the only evidence for routing, serialization, network isolation, Terraform, Loki/Alloy, Grafana, retention, or reactive lifecycle behavior.

## Required adequacy checks

- Tests cover success, disabled, metadata-only, no-payload, exception, saturation, backend outage, shutdown, and recovery paths where applicable.
- Security tests assert absence as well as expected status, including every payload and partner-isolation case required by their repository-local skills.
- Concurrency tests prove boundedness, non-blocking business behavior, context cleanup, exact terminal emission, and drop accounting.
- Contract tests validate schemas, allowed labels, structured metadata, metric dimensions, configuration, and compatibility.
- Integration tests use real configured components where component interaction is the risk; test doubles do not hide trust-boundary behavior.
- Performance tests execute every current acceptance profile without shortened loads or durations.
- Tests are deterministic, isolated, synthetic-data-only, and fail on unsafe fallback. Flaky tests are failures until root-caused.
- Skips, disabled tests, broad exception swallowing, assertions with no behavioral value, and golden files updated without review are identified.

## Commands

```bash
git diff --check
rg -n -i "@Disabled|@Ignore|skip|TODO|NOT IMPLEMENTED|assertTrue\(true\)|catch \(.*Exception" partner-observability-* test scripts
./scripts/build.sh
./scripts/test.sh
./scripts/test-security.sh
./scripts/test-performance.sh
./scripts/verify-all.sh
```

Run module-specific tasks and configuration validators named by the implementation. Capture every exit code. A missing suite, `NOT IMPLEMENTED`, silent skip, non-zero command, or absent artifact is `FAIL` for a claimed milestone, even if another aggregate command exits zero.

## PASS/FAIL criteria

`PASS` requires complete traceability for the claimed scope, appropriate test layers for each risk, all applicable commands passing, no unexplained skips/flakes, and assertions capable of detecting the prohibited behavior. Document legitimate `NOT APPLICABLE` rows with evidence.

`FAIL` on any uncovered critical invariant, test-only implementation behavior, insufficient negative/boundary/fault evidence, weakened fixture, flaky result, false-success script, or check that does not execute what its name claims.

Never weaken, delete, skip, quarantine, relax, or rewrite a meaningful test merely to obtain `PASS`. Never change production requirements to match existing tests. Add or correct evidence and preserve the regression case.

## Record valid findings

Use `VERIFYING` in `.agent-state/status.json` while evaluating. Use `READY_FOR_REVIEW` only for a scoped pass and `IN_PROGRESS` for gaps. Preserve the schema; summarize coverage in `summary`, actionable missing rows in `nextActions`, and genuine blockers in `blockers`. Update `PLANS.md` milestone checkboxes only when the evidence exists, and record unresolved requirement questions in `docs/decisions-needed.md`.
