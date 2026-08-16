---
name: performance-validation
description: Run and assess the initial liberal performance, saturation, soak, and reactive safety gates for the SDK. Use for dispatcher, sanitizer, interception, buffering, concurrency, release, or performance-sensitive changes and before performance milestone acceptance.
---

# Performance Validation

Enforce every initial liberal limit in the performance section of `docs/acceptance-criteria.md`; none is optional.

## Load authoritative requirements

Read `AGENTS.md`, `.agent-state/status.json`, `docs/acceptance-criteria.md`, `docs/architecture.md`, `docs/telemetry-contract.md`, `docs/payload-policy.md`, `docs/metrics-sli.md`, ADR 0002, and relevant unresolved decisions. Extract the current workload, duration, payload, concurrency, percentile, CPU, allocation, heap, error, drop-accounting, and regression thresholds directly from the acceptance table.

Before running tests, inspect `git diff -- docs/acceptance-criteria.md`. Treat any threshold relaxation, shorter duration, smaller payload/load, removed profile, or broader tolerance in the change under review as `FAIL` unless the user explicitly requested a requirements change and an approved ADR explains it.

## Required profiles

Run all six acceptance profiles defined by the authoritative table:

1. disabled-mode overhead;
2. metadata-mode sustained throughput;
3. full-sanitized payload throughput;
4. backend-blackhole saturation;
5. mixed-mode soak;
6. reactive concurrent stream/cancellation safety.

Do not substitute a smoke test for a duration gate. Measure producer-path latency separately from end-to-end business latency. Preserve the specified payload distribution, rate, duration, concurrency, backend condition, and warmup.

## Deterministic procedure

1. Record commit, JVM, CPU quota, memory limit, collector, machine/container image, test configuration, warmup, and baseline.
2. Pin equivalent resources for baseline and instrumented runs; avoid unrelated load.
3. Run `./scripts/test-performance.sh` exactly as the repository defines it. Run focused Gradle/JMH/Gatling tasks in addition when configured.
4. Require machine-readable raw results and a per-profile threshold comparison. Repeat noisy latency profiles at least three times and fail if any required run exceeds a hard limit; do not average away a bad p99.
5. For saturation, prove bounded queue/heap behavior, exact accepted/dropped accounting, no business errors, and non-blocking offers while the backend remains unavailable.
6. For soak/reactive profiles, inspect heap trend, threads, buffers, cancellations, context isolation, deadlocks, and terminal signals.

Run:

```bash
git diff --check
git diff -- docs/acceptance-criteria.md
rg -n "Disabled|Metadata|Full sanitized|Saturation|Mixed soak|Reactive" docs/acceptance-criteria.md test scripts partner-observability-*
./scripts/test-performance.sh
./scripts/test.sh
./scripts/verify-all.sh
```

A missing harness, missing baseline, missing raw result, shortened profile, non-zero exit, or `NOT IMPLEMENTED` is `FAIL`, never a skipped pass.

## PASS/FAIL criteria

`PASS` requires every initial acceptance-table row and every listed limit within that row to pass under its exact workload, with reproducible environment evidence and no functional/security regression. The backend-blackhole and reactive profiles must also prove the qualitative safety assertions in the table.

`FAIL` if any limit is exceeded, any profile is omitted/altered, samples are too incomplete to decide, business errors occur, queues/heap grow beyond the documented bounds, drop accounting is inexact, context leaks, or reactive demand/terminal semantics change.

Never weaken, delete, skip, quarantine, downscale, shorten, or rebaseline tests merely to obtain `PASS`. Optimize the implementation or record the failed limit and evidence.

## Record valid findings

Set `.agent-state/status.json` to `VERIFYING` before the run, then `READY_FOR_REVIEW` for a scoped pass or `IN_PROGRESS` for failure. Use existing fields to record environment/result location in `summary` and failed profiles in `nextActions`; use `blockers` only when constitution criteria are met. Update `PLANS.md` with milestone evidence. Put genuine threshold ambiguities in `docs/decisions-needed.md`, not ad hoc test configuration.
