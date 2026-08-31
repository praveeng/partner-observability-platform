# Create OpenAPI-to-Observability coverage gate

## Mode

IMPLEMENTATION / QUALITY GATE. This prompt may implement the deterministic coverage gate. Stop for human approval if the real contracts expose ambiguous operation ownership, callback semantics, generated-code boundaries, or security-sensitive field classifications. Do not deploy, access AWS, run Terraform, contact a real partner, or modify generated OpenAPI code.

## Objective

In SureWebServices, set the pilot target explicitly:

```text
TARGET_PARTNER_SERVICE=sure-nbfc-unionbank-ph
```

Resolve it through optional `SUREWEBSERVICES_ROOT` or the unambiguous workspace parent and fail for an unset, invalid, wildcard, multiple, or absent target. Create a permanent machine-verifiable gate proving that every external operation in every OpenAPI YAML/YML belonging to only `TARGET_PARTNER_SERVICE` has an explicit Partner Observability decision and a tested implementation/configuration path. The gate must detect newly added outbound APIs, async operations, webhooks, notifications, status/result deliveries, and callbacks even when their names do not contain “callback.” Never inspect or aggregate OpenAPI from another `sure-nbfc-*` service.

Read the applicable `AGENTS.md` files and current architecture, payload, isolation, transport, telemetry, profile, build, and test contracts in `sure-partner-observability`; then inspect the selected target's OpenAPI generation and verification tasks. Locate and validate the deterministic target-derived `contract-inventory.json`, `pattern-manifest.json`, `scenario-manifest.json`, `schema-classification.json`, and `coverage.json` produced by the target fixture preparation process. If they are missing or stale, run the repository's exact-target preparation command for `TARGET_PARTNER_SERVICE` or stop with a clear prerequisite; never substitute a scan of all services. Use Java 17, Spring Boot 2.7.x, Gradle Groovy, the source starter `sure-partner-observability-spring-boot-starter`, and imports under `com.samsung.sure.partner.observability.*`.

## Required design

Parse every OpenAPI `.yaml` and `.yml` structurally rather than with regular expressions. Inventory each operation's source file, operationId, method, templated path, direction/ownership, request and response schemas/media types, status codes, security schemes, and generated/handwritten implementation mapping. Determine sync versus async from the real contract and code, including HTTP 202 or delayed-result behavior. Detect callback-like inbound operations through OpenAPI webhooks and service patterns such as notification, result, update, status delivery, webhook, and delayed completion.

Maintain a reviewed machine-readable operation inventory in the selected target repository, linked to the target-generated fixture manifests without duplicating or losing traceability. Every operation must have exactly one coverage classification:

- `OBSERVED_AUTOMATICALLY`
- `OBSERVED_WITH_CONFIG`
- `OBSERVED_WITH_GENERIC_HOOK`
- `EXPLICITLY_EXCLUDED_WITH_JUSTIFICATION`
- `NOT_COVERED`

`NOT_COVERED` must fail. An exclusion must contain a bounded reason, owner, and evidence that it is not a partner-observability operation or that observing it would violate policy; blank or generic exclusions fail. Generated code location alone is not an exclusion.

For observed operations record the stable API/callback ID, interaction kind, actual client or server stack, operationId, method/route, configuration mapping, target-derived generic pattern/scenario ID, capture mode per leg, correlation identifiers and exact schema paths, and test evidence. Validate configured paths against the referenced OpenAPI schemas, including arrays and composed schemas. Reject nonexistent paths, ambiguous schemas, generated raw URLs, insecure non-local HTTP origins, duplicate mappings, overlapping callback routes, missing trusted callback adapters, free-form/invented correlation fields, and any operation absent from the target fixture manifests.

The gate must preserve the properties-only four-profile model: `application.properties`, `application-local.properties`, `application-dev.properties`, `application-stage.properties`, and `application-prod.properties`. It must not create Spring application YAML. LOCAL uses local mocks; DEV uses an AWS HTTPS mock partner; STAGE uses real partner staging; PROD uses real production. It must not require network or AWS access.

## Required test evidence

Use safe local/mock representative execution to prove:

- every automatic RestTemplate, WebClient, or OkHttp mapping emits the correct request and terminal response/async acknowledgement once;
- async initiation distinguishes `ASYNC_REQUEST_SENT` and an observed or missing acknowledgement;
- callbacks emit distinct receipt, authentication/validation where implemented, processing start/terminal, and response/write terminal facts;
- configured identifiers, including `callbackReferenceId` where present, bridge only within trusted tenant context;
- encryption uses the generic authorized plaintext hook only when necessary and never captures ciphertext/key/IV;
- secret removal, PII masking, and Base64/binary/document omission occur before queueing;
- unsupported/generated/streaming behavior safely reduces to metadata or justified exclusion;
- a newly added OpenAPI operation or callback with no inventory decision fails the gate;
- a removed/renamed operation leaves no stale successful mapping;
- generated OpenAPI source remains unchanged.

Integrate the gate into the pilot's normal Gradle or repository quality checks and the appropriate SureWebServices GHA-consumable command. Emit deterministic machine-readable coverage evidence with schema/version, source spec digest, operation counts by classification, uncovered list, and test command/result. Do not include secrets or payload samples.

Run focused parser/validator tests, mutation fixtures for new API/new callback/bad schema path, representative local application tests, the pilot build, and applicable composite/security/profile gates. Create one coherent local commit only after checks pass. Do not push or deploy. Report coverage counts, exclusions, test evidence, exact gate command, and commit hash.
