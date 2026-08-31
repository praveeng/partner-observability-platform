# Create rollout plan for remaining sure-nbfc-* services

## Mode

ASSESSMENT ONLY. This is the only prompt in the pack allowed to broadly discover the remaining `sure-nbfc-*` service set. Run only after the `sure-nbfc-unionbank-ph` pilot has passed its approved generic and selected-target local integration, OpenAPI coverage, security/regression, and readiness gates. Discovery is read-only: do not build, start, execute, test, or modify all services; do not commit, push, deploy, access AWS, run Terraform, or contact partners.

## Objective

In SureWebServices, turn the validated pilot into a reusable rollout plan for relevant `sure-nbfc-*` partner services and a future population of 20+ partner-facing services. Broadly inventory/classify them without executing them. Every future integration must rerun prompts 1–11 with exactly one literal `TARGET_PARTNER_SERVICE`; never use a wildcard, list, first-match selection, or multi-service test run. Prefer generic SDK capability, configuration, typed extractors, API/callback mappings, generic encryption hooks, Gradle composite conventions, and shared GHA patterns over service forks or partner-specific SDK Java.

Read the monorepo and project `AGENTS.md` files, the validated pilot evidence, current `sure-partner-observability` architecture/security/payload/profile/deployment contracts, the target-fixture and OpenAPI coverage-gate design, the starter public API, GHA integration, and central infrastructure handoff. Discover the actual `sure-nbfc-*` service set from the repository for assessment only; do not assume names or technology solely from directory prefixes and do not execute their applications or tests.

## Fixed standards

- Partner Java services use Java 17, Spring Boot 2.7.x, Gradle Groovy, and SLF4J/Logback where applicable.
- Consume `sure-partner-observability-spring-boot-starter` from source through the established Gradle composite build; no artifact repository or manual JAR copying.
- Public SDK imports use `com.samsung.sure.partner.observability.*`.
- Runnable Spring applications use exactly `local`, `dev`, `stage`, and `prod` with properties-only application configuration. LOCAL is local/mock; DEV is isolated AWS/mock; STAGE is isolated AWS/partner staging; PROD is AWS/partner production.
- Real and AWS mock partner traffic is HTTPS without weakened TLS.
- Observability is bounded, asynchronous, drop-on-saturation, and failure-contained.
- Identity is server-derived with one Loki tenant per partner; UI/client fields are not authorization.
- Remove secrets/card/OTP, mask required PII, and omit binary/document/Base64 before queueing.
- Central Terraform owns STAGE/PROD base infrastructure; GHA owns application/runtime assets after manual Terraform execution. LOCAL and DEV are not changed by that infrastructure contract.
- Preserve B001, B002, and full-duration B003.

## Per-service assessment

For each relevant service inspect:

- runnable Java/Spring modules, Java/Spring versions, Gradle topology, composite-build compatibility, and current starter use;
- `application.properties` plus all profile files, aliases/YAML, local Docker/LocalStack/Testcontainers behavior, AWS DEV mock behavior, STAGE/PROD endpoint/secret injection, and profile activation;
- all OpenAPI YAML/YML, operationIds, methods/paths, schemas, security schemes, generated versus handwritten code, outbound sync/async APIs, HTTP acknowledgements, callbacks/webhooks/notifications/status-result flows, and polling;
- actual RestTemplate, WebClient, and OkHttp construction/use, serialization, retries/timeouts, TLS and redirect ownership;
- callback authentication, route mapping, async/background processing, retries/duplicates/idempotency, and response semantics;
- encryption/decryption boundaries and whether the generic plaintext hook is needed;
- binary, multipart, document, image, signature, and Base64 fields;
- actual correlation fields such as application/loan/correlation/request/partner/callback/external transaction IDs without inventing paths;
- existing logs, payload classification, partner identity source, tests, generated code, GHA workflow, and infrastructure output dependencies.

Keep each service's findings and target-derived fixture recommendations separate. Do not aggregate OpenAPI schemas, correlation semantics, credentials, payload samples, generated manifests, or runtime configuration across services. Recommend a future command using one explicit value, for example `TARGET_PARTNER_SERVICE=sure-nbfc-partner-b`, for each service selected into a rollout wave.

Classify each service exactly as:

- `A_READY_WITH_STARTER_ONLY`
- `B_READY_WITH_CONFIGURATION`
- `C_REQUIRES_GENERIC_PLATFORM_CAPABILITY`
- `D_REQUIRES_SMALL_SERVICE_HOOK`
- `E_REQUIRES_HUMAN_REVIEW`

For `C`, define a generic gap usable by multiple services and explain why it belongs in the platform. For `D`, bound the handwritten hook and show why configuration/automatic interception cannot cover it. Never recommend editing generated OpenAPI code.

## Required output

Produce:

1. Complete service compatibility matrix with evidence and classification.
2. Shared RestTemplate patterns.
3. Shared WebClient patterns.
4. Shared OkHttp patterns.
5. Callback/webhook/authentication/lifecycle patterns.
6. Async acknowledgement and delayed-result patterns.
7. Encryption/plaintext-hook patterns.
8. Binary/Base64/document risk patterns.
9. Common four-profile properties configuration pattern.
10. Standard Gradle composite-build approach for clean local/GHA checkout.
11. Shared GHA standardization opportunities without Terraform execution.
12. Reusable OpenAPI-to-observability inventory/gate pattern.
13. Generic SDK gaps and affected services.
14. Small service-specific hooks and owners.
15. Human/security/partner decisions.
16. Rollout waves, starting with lowest-risk services and explaining dependencies.
17. Highest-risk services and containment/rollback plan.
18. Reusable onboarding checklist covering assessment, approval, configuration, build, coverage, local E2E, infrastructure handoff, readiness, security, and deployment evidence.

Stop after the assessment. Do not change any service or platform file.
