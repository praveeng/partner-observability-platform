# Authoritative Local Completion Gate Blockers

Originally validated on 2026-08-23 in the adversarial-security-review worktree and
updated on 2026-08-24 for the B001 and B002 implementations. That historical run included
the then-repository-owned Terraform implementation. ADR 0013 retired that ownership; current local
verification requires no Terraform CLI, provider, AWS credential, or AWS access.

Command:

```bash
GRADLE_USER_HOME=/tmp/partner-observability-gradle ./scripts/verify-all.sh
```

The historical updated command completed with `FINAL RESULT: FAIL (1 of 22 stages failed)`.
Twenty-one stages passed, including requirements 35, 36–46, 48, and the complete local
security gate. The sole remaining failure is B003.

## Resolved: B001 — Grafana completion boundary

Resolved on 2026-08-24. The mandatory assets are implemented:

- `grafana/provisioning`
- `grafana/dashboards`
- `test/integration/run-local-grafana.sh`

`test/integration/run-local-grafana.sh` passes real local Grafana health,
authentication, one-organization-per-partner Viewer access, fixed
datasource/query-gateway isolation, bypass denial, search/timeline/detail/SLI results,
and dashboard/provisioning validation. Requirements 35 and 48 pass. Application-originated
requirements 36–46 are proven separately by the resolved B002 boundary; direct synthetic
OTLP injection in the Grafana runner is not counted as that evidence.

## Resolved: B002 — Application-to-platform end-to-end boundary

Resolved on 2026-08-24. The mandatory executable is implemented:

- `test/integration/run-local-end-to-end.sh`

The runner builds and drives `sure-partner-observability-test-app` through the SDK bounded
dispatcher, fixed authenticated Alloy receivers, tenant-isolated Loki and Prometheus,
the query gateway, and Viewer-authenticated Grafana datasources/dashboard APIs. Real
RestTemplate request/response and HTTP 202 acknowledgement/delayed callback journeys
prove typed transaction/callback search, ordered timelines, selected safe events,
application metrics/SLIs, bidirectional and colliding-ID isolation, PII masking, secret
removal, and large-Base64 omission. Direct OTLP injection and direct Loki queries are
not used as requirements 36–46 evidence.

## B003 — IMPLEMENTED_BUT_NOT_FULLY_VALIDATED

The nine-profile manifest, K6 drivers, local fixtures, matched-baseline evaluator, outage controls,
and machine-readable aggregation are implemented. B003 remains open until all three unshortened
repetitions of all nine profiles complete successfully and the aggregate full result passes. Smoke
mechanics, partial runs, and shortened loads are not completion evidence.

Verification on 2026-08-30: smoke run `b003-smoke-20260830-r11` executed all nine profile drivers,
produced assertions for P01-P24, passed the two-tenant/16-day journey seed mechanics, and exited
successfully while correctly recording `mode=smoke` and `overallPassed=false`. The clean Gradle
build, B002 runner, and complete local security gate pass. Two standalone B001 attempts timed out
at the bounded Prometheus SLI readiness query; the same unchanged B001 runner subsequently passed
inside the complete security gate. No B003 change touches that runner or Prometheus configuration.

## Required next action

Run `SPRING_PROFILES_ACTIVE=local PERF_MODE=full ./scripts/test-performance.sh` on a stable machine
meeting the fixed resource prerequisite, inspect retained evidence, then run the full
`./scripts/verify-all.sh` release path. Any failed or unmeasured mandatory threshold keeps B003 open.
Do not claim local completion until every stage passes.

The requested B002 snapshot may be committed locally in its explicit remaining-B003 state.
That commit is B002 review evidence only and must not be described as whole-platform
completion, release readiness, or production approval. See
`docs/security-review.md` for all 84 attack dispositions, fixed findings, and staging-only
tests.
