# B003 performance validation

## Status and authority

The local K6 harness implements the approved Q015, Q015-A, and conflict-resolution contracts. The
nine rows in `docs/acceptance-criteria.md` remain the top-level profiles; P01-P24 are independently
identifiable scenario assertions mapped into those profiles. `test/performance/profiles.json` is the
machine-readable execution contract and `scripts/validate-performance-profiles.sh` rejects drift.

Implementation or smoke success is not B003 completion. Until every full repetition and threshold
passes, the precise state is `IMPLEMENTED_BUT_NOT_FULLY_VALIDATED`.

## Commands

Mechanics-only smoke mode uses reduced test parameters and one repetition:

```bash
SPRING_PROFILES_ACTIVE=local PERF_MODE=smoke ./scripts/test-performance.sh
```

It prints `SMOKE MODE — NOT RELEASE EVIDENCE`, never writes commit-safe release results, and cannot
close B003.

The only B003 command is:

```bash
SPRING_PROFILES_ACTIVE=local PERF_MODE=full ./scripts/test-performance.sh
```

`PERF_MODE` defaults to `full`; it never silently defaults to smoke. `RUN_ID` may supply a unique
filename-safe run identifier and `KEEP_RUNNING=1` may retain the local stack for bounded debugging.
Neither option changes acceptance parameters. `scripts/verify-all.sh` invokes full mode explicitly.

## Fixed environment

Full mode requires Java 17, Docker/Compose, the pinned K6 0.49.0 image, `jq`, `curl`, Python 3, at
least 8 logical CPUs, and 12 GiB available to both the host and Docker. It fails with
`BLOCKED_INSUFFICIENT_LOCAL_RESOURCES` rather than reducing load. It uses only the `local` profile,
synthetic data, the existing local Alloy/Loki/Prometheus/Grafana topology, a local mock partner, and
the two test applications. It never accesses AWS, Terraform, or a real partner.

The servlet and reactive test applications each use 2 vCPU, 2 GiB container memory, 512 MiB initial
heap, 1 GiB maximum heap, 256 MiB metaspace, G1GC, and matching JFR settings. Mock partner, Alloy,
Loki, Prometheus, and Grafana limits are fixed in the manifest and Compose overlay. A sequential
full suite is roughly 29.5 hours of measured/warm-up/cool-down runtime after accounting for the
seven matched workload baselines, before setup, journey seeding, recovery, and diagnostics; no
profile may run concurrently against the same constrained application benchmark.

## Profiles and phases

Every profile runs three repetitions. Warm-up is 180 seconds and cool-down is 120 seconds. Measured
durations are 900 seconds for Disabled, Saturation, and Journey query; 1,800 seconds for Metadata,
Full sanitized, Reactive, Callback MVC, and Callback WebFlux; and 3,600 seconds for Mixed soak. A
full repetition must record at least configured duration minus the five-second runner tolerance.

Disabled executes 1,000 requests/s. Arrival-rate profiles require at least 99% scheduled starts and
their approved minimum successful sample count. Reactive uses 500 concurrent streams and a
50-stream/s ramp, exercises a real auto-configured WebClient exchange for every stream, and proves
application telemetry reaches tenant-isolated Loki. Reactive callbacks preserve correlation,
return HTTP 202 before the approved 8%/2% deferred completion paths, and use an explicit bounded
test-only deferred-work capacity. Callback and mixed distributions are fixed by the manifest.
Journey query seeds a fresh isolated 500,000-record, two-tenant, 16-day dataset immediately before
query execution so the long suite cannot age its oldest records out before validation.

## Baselines and verdicts

Every materially different enabled workload gets an immediately preceding, same-shape disabled
baseline with the same commit, host, resource limits, JVM, profile, payloads, and workload hash.
Reuse beyond 60 minutes or after any identity mismatch is rejected. Three baseline repetitions must
satisfy p95/p99/CPU/heap coefficient-of-variation limits; instability produces
`INCONCLUSIVE_ENVIRONMENT_UNSTABLE` and keeps B003 open.

Business/callback continuity, duration, load, OOM, deadlock, bounded memory/queues, and disclosure
rules pass in all three repetitions. Quantitative p95, p99, normalized mean CPU, and peak heap use
the three-run median and the 1.25x worst-run guard. GC, heap plateau/slope, dependency restoration,
drop accounting, query isolation, payload omission, and profile-specific thresholds are evaluated
independently. A required unmeasured value fails; JFR allocation estimates are explicitly
informational under Q015-A.

## Evidence and safety

Detailed local evidence is retained without automatic deletion at:

```text
test/performance/evidence/<run-id>/
```

It is Git-ignored and includes profile/repetition JSON, K6 summaries, bounded resource samples,
container/JVM/GC data, diagnostics, JFR recordings, threshold verdicts, and
`aggregate-result.json`. Evidence contains no raw request/response payload, full Base64 document,
credential, token, OTP, card value, real PII, or cryptographic secret.

Only after `overallPassed=true`, `mode=full`, `springProfile=local`, all nine profiles, all three
repetitions, and all P01-P24 assertions pass does the harness create compact commit-safe JSON under:

```text
test/performance/results/<run-id>/
```

Failures retain raw evidence and exact reasons. Do not weaken a threshold, shorten a repetition,
skip a scenario, or treat a missing metric as zero. Restore the environment or fix a confirmed
defect, then rerun according to the acceptance policy.

## Current verification record

On 2026-08-30, `b003-smoke-20260830-r11` completed all nine mechanics-mode profile drivers and
materialized assertions for every P01-P24 mapping. Its synthetic journey seed sent 700 records
across two tenants with the approved 50/30/20 age mix and deliberate identifier collisions. The
aggregate intentionally records `mode=smoke`, `overallPassed=false`, and no commit-safe result;
short duration, one repetition, and non-release sampling are expected smoke disqualifiers.

The same worktree passes the clean Gradle build, profile and enterprise-naming gates, B002 local
end-to-end suite, and complete local security gate. The mandatory full command has not completed,
so there is no profile PASS/FAIL release verdict and B003 remains
`IMPLEMENTED_BUT_NOT_FULLY_VALIDATED`.
