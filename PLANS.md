# Delivery Plan

## Plan rules

Milestones are ordered; later exploratory work must not redefine an earlier security or availability contract without an ADR. A milestone can be marked complete only when its acceptance evidence is recorded and `.agent-state/status.json` is updated. `NOT IMPLEMENTED` checks are expected during foundation work and are not evidence of success.

## Milestones

### M0 — Repository foundation (ready for review)

- Establish the constitution, lifecycle state, documentation map, Gradle multi-module skeleton, infrastructure/test directories, and honest command entry points.
- Pin Java 17 and Spring Boot 2.7.x compatibility expectations.
- Acceptance: repository structure exists, documentation links resolve, state JSON parses, shell scripts pass syntax checks, Gradle discovers all modules, and the foundation is committed locally.

### M1 — Architecture and specification

- Resolve the open decisions in `docs/decisions-needed.md`.
- Convert requirements, threat model, telemetry schema, payload classifications, isolation boundaries, SLIs, and deployment assumptions into reviewed contracts and ADRs.
- Acceptance: no security-critical TBD remains; architecture and acceptance criteria are internally consistent.

### M2 — Core SDK

- Implement framework-independent event models, allowlisting/sanitization, bounded queues, drop behavior, safe failure containment, and exporter abstractions.
- Acceptance: unit and concurrency tests prove boundedness, non-blocking saturation, sanitization, and exception isolation.

### M3 — Spring Boot auto-configuration and interceptors

- Implement conditional Spring Boot 2.7 auto-configuration and supported HTTP/client interception with one-starter integration.
- Acceptance: opt-in configuration, compatibility, context startup, request-path isolation, and disabled-mode tests pass.

### M4 — Payload safety and encrypted integration support

- Implement fail-closed payload classification and the approved design for observing supported encrypted integrations without weakening application encryption.
- Acceptance: prohibited classes are absent before queue admission and from every sink, including error paths and malformed inputs.

### M5 — Alloy and Loki

- Define local Alloy/Loki configuration, per-partner Loki tenant routing, retention, limits, and safe structured metadata handling.
- Acceptance: backend outage cannot affect business results; tenant-crossing and cardinality tests fail closed.

### M6 — Prometheus metrics

- Implement SDK/collector/backend health and SLI metrics with bounded dimensions and documented recording/alerting rules.
- Acceptance: metric contract tests, cardinality budgets, and scrape integration checks pass.

### M7 — Grafana

- Provision data sources, dashboards, and server-enforced partner access patterns without client-side isolation assumptions.
- Acceptance: partner access tests and dashboard/query validation pass using synthetic tenants.

### M8 — Terraform and AWS ECS

- Build reusable Terraform modules and non-production examples for the approved AWS ECS topology, networking, identity, encryption, and secret references.
- Acceptance: formatting, validation, static security checks, and non-production plan review pass. No deployment is performed by default.

### M9 — Security, performance, and end-to-end verification

- Complete adversarial disclosure, tenant isolation, backend failure, saturation, throughput, latency, and Docker Compose end-to-end suites.
- Acceptance: explicit thresholds in `docs/acceptance-criteria.md` pass with retained test evidence and no real data.

### M10 — Release documentation and package readiness

- Finalize consumer guides, configuration reference, compatibility matrix, upgrade notes, artifacts, provenance, and release checklist.
- Acceptance: reproducible clean build, package smoke test, documentation review, dependency/license review, and human release approval.

## Current focus

M0 is implemented and awaiting review. M1 is next. Product implementation must not begin until the M1 security-critical decisions are resolved or explicitly approved through ADRs.
