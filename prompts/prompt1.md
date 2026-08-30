# Assess sure-nbfc-unionbank-ph compatibility

## Mode

ASSESSMENT ONLY. Do not modify files, create commits, push, deploy, access AWS, run Terraform, or contact a real partner.

## Objective and workspace

Work in the enterprise SureWebServices workspace. Locate and inspect both `sure-partner-observability` and the pilot service `sure-nbfc-unionbank-ph`. Determine exactly how the pilot can consume `sure-partner-observability-spring-boot-starter` from source and which work is generic platform capability versus service integration.

Before assessing the pilot, read `sure-partner-observability/AGENTS.md`, `PLANS.md`, `.agent-state/status.json`, the applicable files under `docs/`, and the applicable repository-local skills under `.codex/skills/`. At minimum read the architecture, security invariants, transport security, telemetry contract, payload policy, partner isolation, metrics/SLI, acceptance criteria, deployment model, and enterprise infrastructure contract. Then read the pilot's repository instructions and build/configuration documentation. Repository evidence is authoritative; do not infer implementation from this prompt when source can answer it.

## Fixed enterprise constraints

- Java 17, Spring Boot 2.7.x, Gradle Groovy, SLF4J/Logback.
- Partner Observability packages use `com.samsung.sure.partner.observability.*`; active `com.partner.observability.*` code is prohibited.
- Java/Spring modules use `sure-partner-observability-*`; the consumer dependency is `sure-partner-observability-spring-boot-starter`.
- The source dependency uses the SureWebServices Gradle composite-build convention. Do not propose artifact publication or manual JAR copying.
- Runnable applications use exactly `local`, `dev`, `stage`, and `prod`, configured only through `application.properties` and `application-local.properties`, `application-dev.properties`, `application-stage.properties`, and `application-prod.properties`.
- `local` is local VM/Docker with LocalStack or Testcontainers where needed and a mock partner; `dev` is an isolated AWS DEV cluster/VPC with a mock partner; `stage` is an isolated AWS STAGE cluster/VPC with the real partner staging environment; `prod` is AWS production with the real partner production environment. DEV and STAGE may share a market AWS account but never a cluster, VPC, resources, routes, or secrets.
- All real and AWS-hosted mock partner traffic is HTTPS. Local isolated mock fixtures may use HTTP. Never weaken TLS, certificate validation, hostname verification, or redirect policy.
- Observability is non-blocking and failure-contained: bounded queues, drop on saturation, no synchronous Alloy/Loki/Prometheus/Grafana dependency, and no observability exception changing outbound or callback behavior.
- Partner identity is server-derived. One opaque Loki tenant exists per partner. Client fields, callback bodies, dashboard variables, and tenant headers are not authorization boundaries.
- Remove credentials, passwords, Authorization, tokens, cookies, API keys, OTP, card data, encryption keys, and private cryptographic material. Mask phone, email, bank account, national identifier, and address. Omit PDFs, images, documents, signatures, binary data, and Base64 before queue admission.
- Preserve B001 Grafana, B002 application-to-platform end-to-end, and B003 full-duration performance gates. Do not describe smoke evidence as B003 completion.

## Required inspection

Inventory the actual repository structure, settings and all `build.gradle` files, application resources, profile files, configuration-property classes, source generation tasks, tests, Docker/local scripts, and `.github/workflows` if present.

Locate and parse every OpenAPI `.yaml` and `.yml` file in `sure-nbfc-unionbank-ph`. OpenAPI is the external-contract source of truth. For every operation identify specification file, operationId, method, templated path, request/response schemas and media types, status codes, security schemes, generated interface/model/client/server artifacts, and handwritten implementation or caller. Do not search only for the word callback: identify webhooks, notifications, status/result delivery, delayed completion, inbound partner endpoints, asynchronous acknowledgements, and polling patterns.

Build separate inventories for:

- outbound synchronous partner APIs;
- outbound asynchronous initiations and their actual acknowledgement behavior, including HTTP 202 where applicable;
- inbound callbacks/webhooks/notifications/status-result operations;
- other async or polling operations relevant to a partner journey.

Trace each operation through real code. Determine which of RestTemplate, WebClient, or OkHttp is used; client construction and bean ownership; request serialization; response deserialization; error mapping; retry ownership and attempt visibility; timeout ownership; redirect behavior; TLS configuration; filters/interceptors; encryption/decryption order; callback authentication/signature verification; generated versus handwritten boundaries; background execution; idempotency/duplicate logic; and current logging.

Inventory actual identifiers and their precise OpenAPI/DTO paths and types, including `applicationId`, `loanId`, `correlationId`, `originalCorrelationId`, `requestId`, `partnerReferenceId`, `callbackReferenceId`, and `externalTransactionId` only where they exist. Do not invent missing fields. Explain which identifiers bridge initiation, acknowledgement, callback receipt, processing, and response.

Classify request, response, acknowledgement, and callback fields under the current platform policy. Explicitly identify credentials/secrets/card/OTP for removal, PII for masking, safe business/correlation fields, and binary/document/Base64 candidates for pre-queue omission. Inspect multipart, byte arrays, file/resource types, data URIs, encrypted ciphertext, large encoded fields, exception bodies, headers, cookies, and query parameters.

Inspect Spring profiles and prove whether the pilot already follows the four canonical profiles and properties-only convention. Describe current LOCAL behavior, LocalStack/Testcontainers use, Docker mock partner, DEV AWS/mock behavior, STAGE real partner-staging behavior, PROD real partner-production behavior, external activation, endpoint injection, callback configuration, and secret references. Flag every active alias such as `development`, `staging`, `production`, or `uat`, and every Spring application YAML file.

Assess Gradle source dependency integration from a clean SureWebServices checkout. Inspect current composite-build conventions, root settings, module inclusion, dependency substitution, version/group alignment, CI checkout layout, configuration cache implications, and the dependency direction needed for `sure-partner-observability-spring-boot-starter`. Do not require an artifact repository.

## Classification and gap IDs

Classify every OpenAPI operation and callback as exactly one of:

- `COMPATIBLE_AS_IS`
- `COMPATIBLE_WITH_CONFIGURATION`
- `REQUIRES_GENERIC_PLATFORM_CHANGE`
- `REQUIRES_SERVICE_CHANGE`
- `REQUIRES_HUMAN_DECISION`

Assign stable sequential IDs `G001`, `G002`, and so on only to genuine reusable platform gaps. Assign `S001`, `S002`, and so on to pilot-specific integration needs. Do not disguise a Union Bank-specific behavior as a generic SDK feature. State the evidence, smallest safe change, repositories/files affected, tests required, and risk for every ID.

## Required output

Return:

1. Executive summary and compatibility verdict.
2. Complete OpenAPI/API inventory.
3. Complete callback/webhook/notification inventory.
4. HTTP-client, serialization, retries, timeouts, and TLS findings.
5. Encrypted-flow inventory and required generic plaintext-hook use, if any.
6. Four-profile and properties-only findings.
7. Correlation model using only real fields.
8. Data-classification and binary/Base64 findings.
9. Generated-versus-handwritten code boundary.
10. Per-operation compatibility matrix.
11. `Gxxx` generic gaps.
12. `Sxxx` service needs.
13. Gradle composite-build recommendation.
14. Exact files proposed for later changes.
15. Files that must not change, especially generated OpenAPI code.
16. Risks, unresolved human decisions, and ordered implementation plan.

Stop after the report. Ask the user which `Gxxx` and `Sxxx` items are approved. Make no changes.
