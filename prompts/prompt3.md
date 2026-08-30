# Configure sure-nbfc-unionbank-ph for Partner Observability

## Mode

APPROVAL-GATED IMPLEMENTATION. Do not deploy, access AWS, contact a real partner, modify centralized Terraform, or edit generated OpenAPI code.

## Objective

Configure the actual `sure-nbfc-unionbank-ph` Spring Boot service to use the generic Sure Partner Observability contracts. Work in SureWebServices and inspect the current pilot and `sure-partner-observability` state; do not assume an earlier assessment is still current. This prompt owns service configuration and minimal handwritten adapters only. It does not own the Gradle composite-build wiring performed separately.

Read both repositories' `AGENTS.md` files and relevant plans/docs. Parse every pilot OpenAPI YAML/YML and trace each operation to generated and handwritten code. OpenAPI is the external-contract source of truth. Discover callbacks through webhooks, notifications, status/result delivery, delayed completion, and inbound partner operations rather than a callback-word search.

## Enterprise standards

- Java 17, Spring Boot 2.7.x, Gradle Groovy, SLF4J/Logback.
- Public SDK imports use `com.samsung.sure.partner.observability.*`; never add `com.partner.observability.*`.
- The starter module is `sure-partner-observability-spring-boot-starter`.
- Spring configuration is properties-only. A runnable application must use `application.properties`, `application-local.properties`, `application-dev.properties`, `application-stage.properties`, and `application-prod.properties`; do not create Spring application YAML.
- Activate profiles externally. Do not package a default STAGE/PROD profile.
- LOCAL is local VM/Docker with LocalStack/Testcontainers where needed and a local mock partner. DEV is a dedicated AWS DEV cluster/VPC with a mock partner. STAGE is a dedicated AWS STAGE cluster/VPC using the real partner staging environment. PROD is AWS production using the real production partner. DEV and STAGE may share the PH account but not clusters, VPCs, resources, configuration, endpoints, tenants, or secrets.

## Required configuration design

Use existing `partner-observability` property names and binding structures. Do not invent properties or JSON paths that source/configuration metadata does not support. Put environment-neutral enablement defaults, service name, fixed capture/sanitization policy, API definitions, and callback definitions in the appropriate common/application-owned configuration only when they are truly common. Put partner endpoints, callback origins, environment identity, market/partner mapping, tenant references, and runtime secret/config references in their proper profile or external environment source.

Configure from actual OpenAPI and service code:

- trusted canonical partner identity and market `PH` using the enterprise naming discovered in the workspace;
- service name and canonical environment identity;
- every outbound sync and async API mapping with operationId-derived stable API ID, exact HTTPS origin, method, route template, and interaction kind;
- every callback/webhook/notification mapping with exact method/route template and named server-owned authentication/context adapter;
- per-leg capture modes, starting metadata-only unless a reviewed field schema supports `FULL_SANITIZED`;
- actual correlation extraction for available `applicationId`, `loanId`, `correlationId`, `originalCorrelationId`, `requestId`, `partnerReferenceId`, `callbackReferenceId`, and `externalTransactionId` fields;
- callback receipt, authentication/validation, processing start/success/failure, and response semantics without inferring business completion from HTTP status;
- exact safe header/query allowlists, removal rules, PII masking, Base64/binary/document omission, size bounds, queue behavior, and safe outcome/error mappings;
- generic plaintext capture hooks only if real encryption makes authorized logical DTOs invisible to automatic instrumentation.

Do not change OpenAPI contracts, generated clients/controllers/models, callback API behavior, retry/idempotency behavior, encryption/decryption, normal Logback appenders, or TLS configuration. Never derive partner identity from a request field, callback body, arbitrary header, MDC, or observability setting. Do not commit passwords, tokens, API keys, partner credentials, certificates, keys, secret values, or real customer data.

Profile rules are mandatory:

- `local`: local mock endpoints and local observability stack only; no AWS or real partner credential.
- `dev`: AWS DEV and an HTTPS mock partner; never a partner staging or production endpoint.
- `stage`: runtime-injected HTTPS partner-staging API/callback settings and STAGE observability references.
- `prod`: runtime-injected HTTPS partner-production API/callback settings and PROD observability references.

## Approval checkpoint

Before editing, present:

1. Exact OpenAPI operations/callbacks and actual field paths being mapped.
2. Exact existing and proposed files.
3. A common-versus-profile-specific property matrix with safe example values/placeholders expressed as environment-variable references, not secrets.
4. Capture and correlation mapping per operation.
5. Generated files that will remain untouched.
6. Minimal handwritten adapters/hooks required and why automatic configuration is insufficient.
7. Tests, risks, and rollback.

Stop and ask for explicit approval. After approval, implement only that plan.

## Verification and commit

Add configuration-binding and Spring-context tests for all four profiles using safe overrides and no network/AWS dependency. Add OpenAPI-path mapping, callback lifecycle, trusted-context denial, payload-removal/masking/Base64 omission, HTTPS/profile crossover, disabled-mode, and failure-containment tests. Run the pilot's local build/test/static/security commands and applicable `sure-partner-observability` consumer/profile gates. Preserve existing local and DEV behavior.

Create one coherent local commit for the approved service configuration scope. Do not push or deploy. Report files, per-profile behavior, tests, generated-code status, secret/TLS checks, and commit hash.
