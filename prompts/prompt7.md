# Run real sure-nbfc-unionbank-ph application-to-platform end-to-end validation

## Mode

LOCAL EXECUTION / VALIDATION. Use only synthetic data and the `local` Spring profile. Do not use AWS, centralized Terraform, real partner endpoints, real credentials, deployment workflows, or production-like secrets. Ask before any material business-service code change.

## Objective and mandatory path

In SureWebServices, prove this real local path rather than substituting direct telemetry injection:

```text
sure-nbfc-unionbank-ph
  -> sure-partner-observability-spring-boot-starter
  -> Alloy
  -> Loki and Prometheus
  -> server-side authorization/query boundary
  -> Grafana
```

Read both repositories' `AGENTS.md`, current B001/B002/B003 evidence, local runbooks, Docker Compose, profile configuration, pilot OpenAPI inventory/coverage evidence, and applicable security/payload/starter/Grafana/Loki skills. Reuse the implemented B001 Grafana and B002 application-to-platform infrastructure. Do not weaken or rewrite those gates. Direct synthetic OTLP injection is supporting diagnostics only and cannot satisfy application-originated evidence.

## Environment rules

Run the pilot with `SPRING_PROFILES_ACTIVE=local` and its local mock partner. LOCAL may use LocalStack, Testcontainers, and Docker where already designed. It must have no live AWS dependency. Do not edit or activate `dev`, `stage`, or `prod`. Spring application configuration remains properties-only. Use the composite source dependency on `sure-partner-observability-spring-boot-starter` and Samsung Sure imports.

## Required journey evidence

Drive representative operations selected from the actual pilot OpenAPI and coverage inventory:

- a real application-originated outbound synchronous request and response;
- an outbound asynchronous request and its acknowledgement, including HTTP 202 when the real contract uses it;
- a real local/mock callback HTTP request into `sure-nbfc-unionbank-ph`, not into a test-only bypass;
- callback receipt, authenticated/validated stages where implemented, processing start, processing success or failure, and callback response/write terminal;
- an applicable encrypted request/response or callback flow through the generic before-encryption/after-decryption hook, only if the pilot really encrypts payloads.

Verify through the fixed authorized query path and Grafana:

- correct event types and ordering without equating receipt, response, and processing completion;
- typed transaction search and detail for actual `applicationId`, `loanId`, `correlationId`, `partnerReferenceId`, `callbackReferenceId`, and other real identifiers where present;
- Prometheus interaction/acknowledgement/callback metrics and Grafana SLI panels;
- one Loki tenant per synthetic partner and fixed Prometheus partner slot;
- bidirectional cross-partner denial and same-identifier collision isolation;
- spoofed `partnerId`, tenant headers, callback-body identity, Grafana variables, and direct backend paths do not cross the authorization boundary.

Use unique synthetic sentinels and assert absence at application queue evidence and retained sinks. Credentials, Authorization, tokens, cookies, API keys, OTP, card data, encryption key/IV, and private material must be absent; phone/email/account/national ID/address must be masked; PDF/image/document/binary/Base64 must be omitted before queue admission. Do not print sensitive-shaped fixture values in the report.

## Availability fault matrix

Independently make Alloy, Loki, Prometheus, and Grafana unavailable using the repository's safe local fault mechanism. For each outage prove the original outbound/callback business response, exception, timing bound, and processing semantics remain unchanged; telemetry may drop. Also exercise queue saturation and sanitizer/publisher failure where supported. No business thread may wait for an observability backend.

## Execution and result

Run focused pilot tests, `test/integration/run-local-grafana.sh`, `test/integration/run-local-end-to-end.sh`, applicable security/profile/OpenAPI gates, and `scripts/verify-all.sh` from the appropriate repository where practical. Preserve exact exit codes and distinguish pre-existing B003/Q015 or environmental blockers from regressions. Do not run or claim full B003 unless its authoritative inputs are resolved and the release policy explicitly requires it; smoke/load evidence is not B003 completion.

Produce a requirement-to-evidence matrix with command, application operation, source event, sink/query, positive assertion, negative/absence assertion, and result. Report B001/B002/B003 status honestly. If validation uncovers a defect, add a failing regression only after asking before any material pilot behavior change. Do not deploy or push. A local evidence commit is allowed only if repository policy and the user explicitly authorize the resulting file changes.
