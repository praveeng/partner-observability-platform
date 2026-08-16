# Product Requirements

## Problem

Partner-facing Spring Boot services need useful operational telemetry for synchronous outbound calls and asynchronous acknowledgement/callback journeys without allowing observability backends, unsafe payload capture, or tenant confusion to affect business availability or disclose partner data.

## Intended users

- Application teams integrating observability into Java 17 / Spring Boot 2.7 services.
- Platform operators running a shared AWS ECS observability platform.
- Security and support personnel investigating synthetic or authorized partner-scoped telemetry.

## Required outcomes

1. An application team can integrate through one starter dependency plus configuration.
2. Request processing never synchronously depends on Alloy, Loki, Prometheus, or Grafana.
3. All telemetry paths are bounded, shed load under saturation, and contain their own failures.
4. Payload handling removes prohibited data, masks restricted identifiers, rejects binary/Base64 content before queue admission, and fails closed for unknown unsafe content.
5. Trusted server-side partner identity selects exactly one Loki tenant per partner.
6. Operators can observe platform health and defined SLIs without high-cardinality metric or Loki label explosions.
7. The system can be exercised locally with Docker Compose and provisioned for non-production AWS ECS with Terraform.
8. Async acknowledgements and callbacks/webhooks are first-class interactions with distinct receipt, authentication/validation, processing, and response facts.
9. A callback arriving minutes or hours later can be correlated through all available validated business/protocol identifiers without relying solely on the original HTTP correlation ID.

## Non-goals

- Business analytics, audit-ledger guarantees, distributed tracing in the initial scope, or guaranteed delivery of telemetry.
- Storing full request/response payloads, binary attachments, documents, images, PDFs, card data, credentials, secrets, or OTPs.
- Kubernetes or Helm support.
- Production deployment from this repository or autonomous-agent access to production credentials.

## Capture products

- Metadata-only is the safe default for a newly enabled API.
- No-payload mode emits no partner record for APIs where even metadata is inappropriate.
- Full sanitized mode is an explicitly reviewed, bounded safe projection of configured textual/scalar fields; it is never a verbatim payload copy and never includes prohibited, unknown, binary/Base64, document, or oversized content.
- Existing arbitrary application logs remain internal-only. Partner-facing logs/events are created through interceptors, explicit observation APIs, or the marked structured safe logger.
- Callback request capture is after trusted authentication/decryption and before business processing; callback response capture is after processing. Failed authentication never creates a partner-tenant fallback record.

## Success measures

Quantitative M1 thresholds are defined in `metrics-sli.md` and `acceptance-criteria.md`. Success requires provable business-path isolation, zero prohibited-field disclosures in adversarial fixtures, server-side tenant/query/correlation isolation, bounded resource/cardinality use, faithful callback lifecycle semantics, 16-day Loki retention, and reproducible one-starter integration.

## Scope discipline

This document expresses requirements, not an implemented feature claim. M1 choices are recorded in ADRs; unresolved organizational inputs remain in `decisions-needed.md`. Later changes require tests and, for architecture-significant choices, an ADR.
