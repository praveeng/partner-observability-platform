# Independent security and regression review

## Mode

ADVERSARIAL REVIEW. Review and test the integrated pilot independently. For confirmed defects, first create a failing regression and then make the smallest clearly in-scope fix. Ask before material business redesign, OpenAPI change, generated-code change, infrastructure redesign, or deployment. Do not access AWS, run Terraform apply, deploy, push, or use real partner/customer data.

## Scope and standards

Set `TARGET_PARTNER_SERVICE=sure-nbfc-unionbank-ph` for the pilot. Resolve that exact service through optional `SUREWEBSERVICES_ROOT` and reject wildcard, list, inferred, or absent targets. Inspect current SureWebServices shared configuration, only the selected target, `sure-partner-observability`, GHA integration, and centralized Terraform code/evidence. Do not enumerate or inspect unrelated `sure-nbfc-*` services. Read all governing `AGENTS.md` files and applicable security, payload, starter, isolation, architecture, Terraform/ECS, and test-adequacy skills. Use Java 17, Spring Boot 2.7.x, Gradle Groovy, the composite source starter, Samsung Sure packages, and synthetic fixtures.

The canonical profiles are `local`, `dev`, `stage`, and `prod`, configured through `.properties` only. LOCAL is local/mock; DEV is isolated AWS/mock; STAGE is isolated AWS/real partner staging; PROD is AWS/real partner production. DEV/STAGE sharing an account never permits shared cluster/VPC/resources. Enterprise Terraform is separate/manual, applies only to STAGE/PROD for this integration, and is never run by GHA.

## Contract and behavior regression

Prove the OpenAPI external contract and generated artifacts are unchanged unless a specific approved change exists. Compare operationIds, paths/methods, schemas, security schemes, status codes, callbacks/webhooks, generated source, and business behavior. Prove request serialization, response deserialization, retries/timeouts, errors, callbacks, background processing, idempotency, and encryption/decryption remain semantically unchanged when observability is disabled and enabled.

Prove Gradle composite resolution works from a clean checkout with `sure-partner-observability-spring-boot-starter`, no artifact repository/JAR copy, and only `com.samsung.sure.partner.observability.*` imports. Prove the OpenAPI-to-observability gate detects uncovered new APIs and callbacks.

Review the generic `sure-partner-observability-test-app` layer and the selected real-service layer separately. Prove generic B001/B002/B003, saturation, hostile payload, artificial-failure, and performance responsibilities remain in the generic layer. Separately prove the selected target's real OpenAPI, HTTP clients, callbacks, correlation, encryption hooks, generated fixtures, and local application-to-platform path. Inspect the change set and repository history to prove no unselected `sure-nbfc-*` service was accidentally modified.

## Mandatory adversarial matrix

Verify with positive and negative/absence assertions:

- no trust-all TrustManager, permissive/Noop HostnameVerifier, certificate/hostname bypass, HTTP downgrade/fallback, or SDK mutation of RestTemplate/WebClient/OkHttp TLS/client settings;
- real/AWS mock traffic is HTTPS; local HTTP is isolated to LOCAL synthetic fixtures;
- Authorization, credentials, passwords, cookies, tokens/JWTs, API keys, OTP, card data, encryption keys/IVs/private material are removed completely;
- phone, email, bank account, national identifier, and address are masked;
- PDF/image/document/signature/audio/video/binary/Base64 is excluded before queue insertion and absent from wire, Alloy, Loki, metrics, Grafana, and diagnostics;
- encrypted flows capture only sanitized authorized logical plaintext before encryption/after decryption, never ciphertext or crypto material;
- partner identity is server-derived and immutable; spoofed partnerId in headers/query/body/MDC/dashboard variables is denied;
- spoofed/duplicated/case-varied `X-Scope-OrgID`, callback identity, routes, forwarding headers, and source credentials cannot select another tenant;
- one Loki tenant and fixed Prometheus slot/Grafana organization per partner; partner A cannot read partner B logs/events/callbacks/metrics/metadata;
- identical `applicationId`, `loanId`, `partnerReferenceId`, and `callbackReferenceId` values across partners remain isolated;
- callback receipt, authentication/validation, processing start/success/failure, and response/write facts remain distinct; spoof, replay, duplicate, malformed, wrong-partner, async-202, background, and write-failure paths are safe;
- Alloy, Loki, Prometheus, and Grafana outages do not change business requests or callbacks;
- sanitizer/extractor/publisher failure, dispatcher failure, queue count/byte/rate saturation, shutdown, and reactive cancellation do not propagate or block business threads;
- queues/retries/batches/buffers/context/cardinality remain bounded and context is cleared across servlet, executor, Reactor, retry, and callback flows;
- direct backend/Grafana bypass and partner query manipulation are denied;
- GHA contains no Terraform execution and handles only approved application assets after base infrastructure;
- centralized Terraform preserves LOCAL/DEV, private tasks, exact SG paths, least-privilege IAM, encrypted storage, HTTPS ALB/ACM/WAF, secret references, and dashboard/alert GHA ownership.

## Profiles, release, and performance

Validate all four Spring contexts with safe overrides, no Spring application YAML, external activation, no LocalStack/Testcontainers leakage outside local, no mock partner leakage to stage/prod, and no stage/prod endpoint crossover. Confirm local and DEV behavior remain unchanged and STAGE/PROD readiness evidence is honest.

Run appropriate unit, integration, security, exact-target OpenAPI fixture/coverage, composite build, generic local E2E, selected-target local E2E, configuration, workflow, Terraform static/validate, and performance-regression comparison checks. Both local E2E layers must use `local`; no real-service local test may access AWS or a real partner. Do not run Terraform apply. Do not claim B003 full-performance closure from smoke, shortened, or partial runs. Preserve the full-duration B003 acceptance requirement and report missing inputs/evidence as failure or blocker.

## Defect workflow and output

For a confirmed in-scope defect, retain a failing regression first, implement the smallest safe fix, rerun relevant and aggregate checks, and create a focused local commit if repository policy allows. Do not weaken tests or requirements. Ask before a material service, contract, security, or infrastructure redesign.

Return a PASS/FAIL matrix for every item above, exact evidence, regressions/fixes, residual risks, B001/B002/B003 state, STAGE/PROD impact, commands/results, and commit hashes if fixes were made. A missing mandatory boundary is FAIL, not an inferred pass.
