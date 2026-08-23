# M6 Prometheus metrics evidence

Date: 2026-08-23
Scope: Micrometer/Spring Boot SDK metrics, Alloy scrape controls, local Prometheus, retention, and recording rules.

## Requirement traceability

| Requirement / risk | Automated evidence | Result |
| --- | --- | --- |
| Outbound success, 4xx, 5xx, timeout, connection failure, retry, slow response | `MicrometerObservationMetricsTest.recordsOutboundSuccessHttpErrorsTimeoutRetryConnectionFailureAndSlowLatency` | PASS |
| Async acknowledgement outcome and latency | `MicrometerObservationMetricsTest.recordsAsyncAcknowledgementOutcomeAndLatency` | PASS |
| Callback receipt, success, processing failure, validation rejection, authentication denial, retry, duplicate, 2xx/4xx/5xx response | `MicrometerObservationMetricsTest.recordsCallbackSuccessFailureRejectionRetryDuplicateAndResponseClasses` | PASS |
| High-volume callback updates remain in-process and do not create series | 100,000 updates under a five-second upper test bound with unchanged meter count | PASS |
| No transaction identifier metric labels | Registry tag-key assertion, Prometheus scrape sentinel assertion, deterministic source scan, and real Alloy removal of synthetic `applicationId` | PASS |
| Exact fixed histogram and series budget | Prometheus scrape sample-line count equals the calculated 389-series manifest; oversized 128-definition fixture is rejected above 10,000 | PASS |
| Scrape rather than business-thread remote call | Static SDK dependency/source scan plus real `metrics-fixture -> Alloy scrape -> relabel -> remote write -> Prometheus` test | PASS |
| Trusted deployment and partner dimensions | Compose test proves market/environment/service overwrite, accepts only configured `p001`/`p002`/`p003`, and drops `p999` | PASS |
| Recording rules for required outbound/callback views | `promtool check config` loads 22 rules; API check finds both outbound and callback p99 rules | PASS |
| Local retention and restricted backend controls | Runtime flags prove `16d`, `1GiB`, admin false, lifecycle false; network/port assertions prove private backend and loopback-only local query | PASS |
| No invented contractual SLA | No alert rule is loaded; `thresholds.example.yml` contains placeholders only and requires approved environment-owned values | PASS |
| M5 Loki/Alloy behavior survives shared Compose/Alloy change | `scripts/test-security.sh --data-plane` real A/B/C isolation suite | PASS |

The optional async-request-to-callback completion histogram is intentionally not implemented. The current SDK has no reliable durable original-send timestamp adapter, and ADR 0009 prohibits an SDK transaction map. Loki event-time correlation remains the documented best-effort diagnostic.

## Commands run in this worktree

| Command | Result |
| --- | --- |
| `./gradlew --no-daemon check` with isolated Gradle home | PASS |
| `./scripts/build.sh` with isolated Gradle home | PASS |
| `./scripts/test.sh` with isolated Gradle home | PASS |
| `./scripts/test-security.sh --core` with isolated Gradle home | PASS |
| `./scripts/test-security.sh --data-plane` with isolated Gradle home | PASS |
| `./scripts/test-security.sh --metrics-plane` with isolated Gradle home | PASS |
| `bash -n scripts/*.sh test/integration/*.sh` | PASS |
| `jq empty .agent-state/status.json` | PASS |
| deterministic forbidden-label, raw-partner-tag, tenant-header, and synchronous-network source scans | PASS (no SDK findings; the downstream synthetic `applicationId` sentinel is intentional and proven removed) |
| `./scripts/test-performance.sh` | EXPECTED NON-ZERO: exact full-duration M9 profiles remain `NOT IMPLEMENTED` |
| `./scripts/verify-all.sh` with isolated Gradle home | EXPECTED NON-ZERO: build/tests plus M2/M5/M6 gates pass; M7-M9 partner query/deployed-network and performance gates remain explicit |

## Architecture-review skill verdict

Verdict: **ACCEPT for the M6 scope**.

- Dependency direction remains valid: the framework-independent core gains only a bounded result enum; Micrometer/Spring code stays in auto-configuration; the starter contains dependency wiring.
- Business and callback threads perform fixed in-process counter/gauge/timer updates only. Scrape and remote write are owned by Alloy, and metrics remain available when event/payload capture is disabled without creating a partner record.
- Identity is architecture-approved: trusted manifest `partner_slot` is the only partner dimension; market/environment/service are overwritten at Alloy; unknown slots and unapproved labels fail closed.
- Cardinality is configuration-fixed and empirically matched to the Prometheus exposition. No transaction IDs, raw partner IDs, exception classes/messages, URLs, headers, or payload values are meter inputs.
- Prometheus is private, remote-write-only from Alloy in the local topology, and its admin/lifecycle APIs remain disabled. M7 query-proxy enforcement remains explicitly outside this slice.

No acceptance-blocking architecture finding remains in the declared M6 scope.

## Test-adequacy skill verdict

Verdict: **ADEQUATE for M6 acceptance**.

The suite covers every requested outbound and callback class, negative label/slot/metric cases, exact series accounting and rejection, high-volume stable registration, recording-rule loading, retention flags, and a real cross-component scrape/write path. Assertions inspect metric values and absence conditions rather than only HTTP success or process liveness. The M5 downstream regression is also rerun because the shared Alloy/Compose configuration changed.

Deferred coverage is not hidden: Grafana/prom-label-proxy partner query isolation is M7, deployed network controls are M8/M9, and the exact long-duration performance profiles are M9. Those gaps keep aggregate verification non-zero but do not invalidate the focused M6 evidence.
