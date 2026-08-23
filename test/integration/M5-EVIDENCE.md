# M5 Alloy/Loki verification evidence

Scope: the isolated `LOCAL_SYNTHETIC` Docker Compose data plane. This is not Grafana, Prometheus, AWS network, production-storage, or whole-platform security evidence.

| Requirement / risk | Runtime path and fail-closed behavior | Automated evidence | Layer / case | 2026-08-23 result |
| --- | --- | --- | --- | --- |
| OTLP/HTTP and approved names | Gateway `/v1/logs` to route-specific Alloy receiver; filter drops unknown schema/type | `run-local-data-plane.sh` schema-2 loop, schema-1 loop, invalid record | Real integration; positive + negative | PASS |
| One tenant per partner | A/B/C receiver exporters carry distinct fixed opaque tenant headers | A/B/C canaries and fixed-query assertions | Real integration; positive | PASS |
| Outbound isolation | Gateway credential/route pair fixes receiver and tenant | Partner A outbound absent from B; B outbound absent from A | Security integration; negative | PASS |
| Callback isolation | Callback uses the same trusted route and fixed exporter | Partner A callback absent from B; B callback absent from A | Security integration; negative | PASS |
| Colliding identifiers | Identifiers are structured metadata inside one fixed tenant | Same application/callback reference in A/B with distinct canaries | Security integration; collision | PASS |
| Missing/conflicting routing | No default route; gateway returns 403; Alloy validates fixed `partner.key` | Missing route and B-credential/A-route cases; sink absence in A/B/C | Security integration; negative | PASS |
| Body/header spoofing | Body routing keys are deleted; ingress tenant headers are stripped | Callback `partnerId` spoof and duplicate/case tenant-header spoof | Security integration; negative | PASS |
| Payload defense in depth | Alloy filters credentials/cards/Base64, masks all required PII classes, and allowlists metadata | Credential/Base64 sink absence; phone/email/account/national-ID/address original absence and mask presence | Security integration; negative | PASS |
| Label cardinality | Loki promotes only the architecture's eight resource attributes | Loki `series` keys equal the exact allowlist | Contract integration; boundary | PASS |
| Transaction search | Loki stores typed identifiers as structured metadata | Exact searches for application, loan, legacy correlation, original correlation, request, partner/callback reference, and external transaction | Contract integration; positive | PASS |
| Correlated journey | Common structured metadata retrieves independent outbound/ack/callback records | One bounded query contains request, ack, callback request/processing/response types | End-to-end integration; positive | PASS |
| Bounded delivery/self-observation | Per-tenant exporter queue is 64, non-blocking on overflow, two-second retry ceiling | Config validation plus Alloy accepted/sent/queue capacity/size metrics | Static + real integration; boundary | PASS |
| Local retention | Loki filesystem compactor uses 24h retention and 2h delete delay | Loki container `-verify-config=true`; documented production delta | Static configuration | PASS for local scope |
| Business availability under backend outage | Application queues before transport; dispatcher exceptions/retry/saturation are contained | Core `BoundedAsyncDispatcherTest` via `test-security.sh --core` and clean repository build | Unit/concurrency/fault | PASS |

## Command record

| Command | Result |
| --- | --- |
| `git diff --check` | PASS |
| Alloy `validate`, Loki `-verify-config=true`, Nginx `-t`, Compose `config` | PASS inside the integration suite |
| `GRADLE_USER_HOME=/tmp/gradle-partner-observability ./scripts/build.sh` | PASS; 20 tasks executed |
| `GRADLE_USER_HOME=/tmp/gradle-partner-observability ./scripts/test.sh` | PASS |
| `GRADLE_USER_HOME=/tmp/gradle-partner-observability ./scripts/test-security.sh --core` | PASS |
| `./scripts/test-security.sh --data-plane` | PASS after final masking corpus change |
| `./scripts/test-security.sh` | Expected non-zero: implemented M2/M5 checks pass; M7-M9 Grafana/Prometheus/deployed-network checks report `NOT IMPLEMENTED` |
| `./scripts/verify-all.sh` | Expected non-zero: build/tests and implemented M2/M5 security pass; remaining security and exact M9 performance report `NOT IMPLEMENTED` |

## Deferred, not claimed

Grafana variable/Explore/dashboard attacks, Prometheus slot enforcement, deployed direct-endpoint/security-group attacks, production audit/rotation/revocation, S3/EFS durability, and exact performance profiles remain M6-M9 gates. Their absence does not weaken the fixed local Alloy/Loki boundary, and no whole-platform or production-readiness verdict is claimed.
