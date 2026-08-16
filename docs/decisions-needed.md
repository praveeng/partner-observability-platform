# Decisions Needed

These are explicit inputs for M1. Security-critical items block product implementation in their affected area. Resolve each through an ADR in `docs/decisions/`, update the relevant contract, and mark the item resolved with a link.

| ID | Decision | Why it matters | Initial status |
| --- | --- | --- | --- |
| D001 | Trusted partner identity source and canonical mapping | Defines the server-side isolation root | Open — security critical |
| D002 | Loki tenant naming, credentials, rotation, and operator access | Prevents cross-partner write/query access | Open — security critical |
| D003 | Safe event schema, versioning, and allowlisted fields | Defines what may enter the telemetry queue | Open — security critical |
| D004 | Removal/masking algorithms and key alias taxonomy | Makes disclosure behavior deterministic and testable | Open — security critical |
| D005 | Binary/Base64 detection, size/depth/count/string limits | Prevents capture evasion and resource exhaustion | Open — security critical |
| D006 | Bounded queue implementation, capacity, drop policy, and shutdown behavior | Protects business availability and resource bounds | Open — availability critical |
| D007 | Application-to-Alloy transport, batching, timeouts, retry, and backoff | Maintains the asynchronous failure boundary | Open — availability critical |
| D008 | Encrypted integration observation boundary | Avoids expanding plaintext exposure | Open — security critical |
| D009 | Loki labels versus structured metadata schema and cardinality budget | Protects backend stability and queryability | Open |
| D010 | Prometheus metric names, labels, buckets, SLOs, and series budget | Enables bounded operational measurement | Open |
| D011 | Grafana authentication and server-side data-source authorization | Prevents dashboard/filter bypass | Open — security critical |
| D012 | AWS ECS topology, networking, service discovery, IAM, and secrets service | Defines Terraform module boundaries and blast radius | Open |
| D013 | Loki/Prometheus storage, retention, encryption, HA, backup, and recovery targets | Defines cost, durability, and compliance posture | Open |
| D014 | Supported Spring MVC/WebFlux/client integrations and async context propagation | Defines interception scope and leakage tests | Open |
| D015 | Performance budgets and reproducible M9 load profile | Makes non-interference objectively testable | Open |

No decision in this list authorizes production deployment or weakens `AGENTS.md` invariants.
