# Performance tests

`profiles.json` is the executable Q015/Q015-A mapping of the nine mandatory rows in
`docs/acceptance-criteria.md` to P01-P24 scenario assertions. The K6 workload uses shared helpers in
`k6/`; Python helpers collect/evaluate payload-free resource and threshold evidence; and
`compose.performance.yml` applies the fixed local resource/JVM configuration without changing the
normal local topology.

Run mechanics only with `SPRING_PROFILES_ACTIVE=local PERF_MODE=smoke
./scripts/test-performance.sh`. Smoke is reduced, prints that it is not release evidence, and can
never close B003. Run the actual gate with `SPRING_PROFILES_ACTIVE=local PERF_MODE=full
./scripts/test-performance.sh`.

Detailed evidence stays untracked in `evidence/<run-id>/`. A compact payload-free subset is written
to `results/<run-id>/` only after a complete full pass. The harness never stores raw payloads or
binary fixtures, never deletes failed evidence, and fails on omitted profiles, shortened duration,
incomplete load, unavailable mandatory measurements, threshold failures, or missing scenario
assertions. See `docs/performance-validation.md` for prerequisites, phases, baselines, verdicts, and
evidence policy.
