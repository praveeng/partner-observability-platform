# Acceptance Criteria

## Cross-cutting release gates

- Business requests remain successful and within the approved latency budget when queues are full and every observability backend is slow, unavailable, or malformed.
- All queues/executors are demonstrably bounded; saturation drops telemetry without blocking.
- No observability exception reaches business logic.
- Security fixtures find zero credentials, secrets, OTPs, card data, binary/documents/images/PDFs/Base64, or unmasked restricted identifiers in queued events, logs, metrics, backend storage, or fallback output.
- Partner isolation is enforced at server boundaries with one Loki tenant per partner; cross-partner write/query tests are denied.
- Loki labels and metric dimensions satisfy documented cardinality budgets; high-cardinality transaction IDs are not normal labels.
- A Java 17 / Spring Boot 2.7 consumer integrates with one starter dependency plus configuration.
- Docker Compose exercises the local stack; Terraform passes format, validate, and approved security/static checks for the AWS ECS model.
- The repository contains no Kubernetes, Helm, production credentials, Terraform state, or real customer data.

## M0 foundation evidence

- Required directories and documents exist.
- `settings.gradle` includes all four modules and builds use Groovy DSL.
- Java 17 and Spring Boot 2.7.x are pinned in build metadata.
- `.agent-state/status.json` parses and declares all lifecycle states.
- Shell entry points are executable and syntax-valid.
- Unimplemented security/performance/full verification exits non-zero and says `NOT IMPLEMENTED`.
- A clean local commit exists and is not pushed.

## Thresholds pending M1/M9

Numeric queue sizes, overhead/latency, throughput, memory/CPU ceilings, drop objectives, export latency, retention, recovery, and test duration/load profiles are intentionally unresolved. They must be approved before corresponding implementations can be accepted; placeholder numbers are not test evidence.
