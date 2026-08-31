# Prepare Target-Service OpenAPI Fixtures and Enrich Generic Test Application

## Mode

TARGET-SERVICE-AWARE IMPLEMENTATION. This prompt may implement deterministic target-contract inventory, generated synthetic fixtures, and data-driven generic test scenarios. Before any substantial generic Java capability change, show the capability gap and exact proposal, then stop for explicit human approval. Do not deploy, access AWS, contact a real partner, run Terraform, modify generated OpenAPI code, or inspect an unselected partner service.

## Objective and target contract

Run from the enterprise SureWebServices workspace. The canonical target input is:

```text
TARGET_PARTNER_SERVICE=sure-nbfc-unionbank-ph
```

The pilot value is `sure-nbfc-unionbank-ph`; later executions may name exactly one other `sure-nbfc-*` service. `SUREWEBSERVICES_ROOT` may identify the workspace root. Do not hard-code an absolute path.

Validate that `TARGET_PARTNER_SERVICE` is a single literal directory name, contains no wildcard/path traversal/list syntax, and resolves to exactly one sibling project under the explicit root or unambiguous repository parent. If it is unset, invalid, or absent, fail clearly. Never choose the first `sure-nbfc-*` directory, enumerate services to select one, combine multiple services, or fall back to `sure-partner-observability-test-app` as if target-derived preparation succeeded. Ignore every unselected `sure-nbfc-*` service.

Read the selected service and `sure-partner-observability` governing `AGENTS.md`, plans, current state, build/configuration documentation, applicable architecture/security/payload/telemetry/isolation/profile/test contracts, and relevant repository-local skills. Inspect the current target-service integration rather than assuming results from an earlier prompt.

## Fixed architecture

Preserve two distinct local validation layers:

- **GENERIC mode:** `sure-partner-observability-test-app` exercises B001, generic B002, B003, performance, queue saturation, sanitizer failure, hostile payloads, reactive stress, artificial dependency failures, large Base64/document omission, and same-identifier tenant collisions.
- **TARGET-SERVICE mode:** only `TARGET_PARTNER_SERVICE` exercises its real OpenAPI contracts, actual RestTemplate/WebClient/OkHttp instrumentation, callback architecture, correlation fields, encryption hooks, and application-to-platform integration.

Target-derived enrichment extends generic coverage; it never replaces the generic test application or its B001/B002/B003 gates. Do not introduce runtime self-modifying Java. Fixture preparation is a deterministic pre-test generation step.

The canonical Spring profiles remain exactly `local`, `dev`, `stage`, and `prod`, using `application.properties`, `application-local.properties`, `application-dev.properties`, `application-stage.properties`, and `application-prod.properties` only. Any local execution in this prompt uses `SPRING_PROFILES_ACTIVE=local`, local/mock systems, and synthetic data. It must not use AWS, DEV, STAGE, PROD, real partner endpoints, or real credentials.

Partner Observability code remains Java 17 / Spring Boot 2.7.x / Gradle Groovy, under `com.samsung.sure.partner.observability.*`, with modules named `sure-partner-observability-*` and the consumer starter `sure-partner-observability-spring-boot-starter`.

## Selected OpenAPI inventory

Locate every OpenAPI YAML/YML in only the selected service. Parse specifications structurally and record, with source file and schema references:

- synchronous request/response operations;
- async initiations, HTTP 202 acknowledgements, delayed results, polling, callbacks, webhooks, notifications, and status/result operations even when no name contains “callback”;
- operationIds, methods, templated paths, direction/ownership, request/response/error schemas and status codes;
- content types, multipart parts, documents, images, byte/binary formats, Base64/data-URI/nested or array-based binary candidates, and large normal JSON;
- security schemes and callback authentication shape without copying secret values;
- actual correlation candidates such as application, loan, correlation, request, partner-reference, callback-reference, and external-transaction identifiers only where the contract or service configuration proves their semantics;
- duplicate/retry/multi-callback and callback-error patterns;
- encryption-relevant schemas and actual serialization/encryption boundaries discovered in handwritten service code;
- generated versus handwritten source ownership.

Do not infer a semantic mapping from a field name alone. For example, map a partner application number to generic `partnerReferenceId` only when current service configuration/implementation establishes that meaning. Do not copy partner-specific business names into generic SDK or test-app Java.

## Generic pattern and fixture model

Normalize selected-service behavior into partner-neutral interaction patterns such as synchronous JSON, async request plus later result, status callback, partner-reference-only callback, multiple callbacks, duplicate/retry callback, multipart/document upload, nested binary omission, large JSON, encrypted logical payload, and unusual content type. Preserve traceability back to the exact target operation without hard-coding Union Bank semantics into generic runtime code.

Compare each pattern with capabilities already supported by `sure-partner-observability-test-app`. Prefer data-driven enrichment: reuse a generic capability with generated synthetic schema/fixture/configuration when possible. All generated values must be synthetic and unmistakably non-production.

Create deterministic machine-readable outputs under the repository's established target-contract fixture location or, when none exists, under:

```text
sure-partner-observability/test/partner-contracts/generated/<target-service>/
  contract-inventory.json
  pattern-manifest.json
  scenario-manifest.json
  schema-classification.json
  coverage.json
```

Record source spec digests, generator/schema version, target service, operations, generic patterns, scenario IDs, correlation decisions, security/data classifications, generated fixture references, and coverage status. Use only structural data and synthetic fixtures. Do not write real URLs when sensitive, credentials, Authorization values, tokens, API keys, OTP, card data, PII, customer data, certificate/private-key material, ciphertext secrets, or full document/image/Base64 bodies.

Use these coverage statuses:

- `COVERED_BY_GENERIC_FIXTURE`
- `COVERED_BY_GENERATED_FIXTURE`
- `REQUIRES_GENERIC_CAPABILITY`
- `EXPLICITLY_EXCLUDED`
- `NOT_COVERED`

`NOT_COVERED` fails readiness. An exclusion requires a precise reviewed justification. Every operation must map to an interaction pattern, Partner Observability mechanism, test scenario, and status.

## Generic capability gaps and approval boundary

When an actual selected-service interaction cannot be represented by current generic data-driven fixtures, report `GENERIC_TEST_APP_CAPABILITY_GAP` with:

1. exact selected OpenAPI operation and evidence;
2. partner-neutral interaction pattern;
3. why current generic fixtures cannot represent it;
4. smallest reusable enhancement for future `sure-nbfc-*` services;
5. exact files, tests, public/configuration effects, and security/availability risks.

Before adding a substantial generic Java capability—such as a multipart, multi-callback, partner-reference-only, async-202, encrypted logical payload, or nested-binary fixture—stop and ask for explicit approval. After approval, keep names and behavior partner-neutral, add dedicated positive/negative tests, and preserve bounded asynchronous behavior. Do not add a Union Bank DTO, endpoint, operation name, or business workflow to generic SDK/test-app Java.

## Safety and validation

Prove the generator inspects only the exact target and is deterministic for unchanged inputs. Add tests for unset/missing/invalid/wildcard/multiple target input, path traversal, unrelated sibling exclusion, schema references/composition, callback discovery without keyword dependence, actual-field correlation, generated-code preservation, and sensitive/binary fixture exclusion. A workspace containing multiple `sure-nbfc-*` directories must yield no reads, builds, generated records, or merged semantics from unselected services.

Preserve business availability, trusted server-side partner identity, one Loki tenant per partner, secret removal, PII masking, and pre-queue Base64/binary omission. Do not weaken HTTPS/TLS or modify DEV/STAGE/PROD behavior. Preserve `sure-partner-observability-test-app`, B001, B002, and full-duration B003; generated target fixtures do not substitute for those gates.

Run focused generator/parser/schema tests, deterministic-regeneration checks, target-isolation checks, the generic test-app tests, profile/naming/security gates, and applicable builds. Do not run the next E2E prompt here. Create one coherent local commit only after the approved implementation and relevant checks pass. Do not push or deploy.

## Required report

Report the resolved target and workspace root mechanism, OpenAPI files inspected, proof that no other service was inspected, operation/pattern/scenario counts, generated files, coverage matrix, classifications, generic capabilities reused, every `GENERIC_TEST_APP_CAPABILITY_GAP`, approval decisions, tests/results, preserved B001/B002/B003 status, and local commit hash.
