# Implement approved generic Partner Observability gaps

## Mode

APPROVAL-GATED IMPLEMENTATION.

APPROVED_GENERIC_GAPS:
<INSERT APPROVED Gxxx IDS>

## Objective and scope

Work in the enterprise SureWebServices workspace, but modify only `sure-partner-observability` for the explicitly listed generic gap IDs. Do not implement unapproved gaps or service-specific `Sxxx` work. Do not modify `sure-nbfc-unionbank-ph` or any other `sure-nbfc-*` service, Gradle monorepo integration, GitHub Actions, centralized Terraform, or any deployed environment in this prompt. This generic-platform prompt does not authorize broad service discovery; use only approved gap evidence and current platform source.

Read `sure-partner-observability/AGENTS.md`, `PLANS.md`, `.agent-state/status.json`, applicable architecture/security/payload/telemetry/isolation/deployment documentation, relevant ADRs, and applicable `.codex/skills/` instructions. Inspect current implementation and tests before relying on an earlier assessment.

## Fixed standards

- Java 17, Spring Boot 2.7.x, Gradle Groovy.
- Packages must remain under `com.samsung.sure.partner.observability.*` and modules under `sure-partner-observability-*`.
- Ordinary services consume `sure-partner-observability-spring-boot-starter` through one dependency plus configuration.
- No Union Bank or other target-service name, endpoint, DTO, field assumption, or partner-specific framework fork may enter generic SDK or generic test-app Java.
- Prefer configuration, typed correlation extractors, API/callback registries, framework-neutral extension points, and generic pre-encryption/post-decryption or callback lifecycle hooks.
- Preserve automatic RestTemplate, WebClient, and OkHttp behavior and optional-classpath activation.
- Preserve bounded non-blocking queues, drop-on-saturation, exception containment, disabled-mode equivalence, trusted server-side partner identity, one Loki tenant per partner, low-cardinality metrics/log labels, and pre-queue sanitization.
- Remove secrets/card/OTP, mask required PII, and reject binary/document/Base64 before queue admission.
- Never mutate SSLContext, TrustManager, HostnameVerifier, certificates, redirect policy, client connectors, or host TLS behavior.
- Preserve the canonical `local`, `dev`, `stage`, and `prod` model and properties-only Spring application configuration. Do not add Spring application YAML.
- LOCAL remains self-contained with local Docker/LocalStack/Testcontainers/mock behavior. DEV remains AWS with a mock partner. STAGE and PROD remain AWS with their respective real partner environments.
- Preserve B001, B002, and B003. Do not weaken tests, thresholds, or the Q015/B003 guard.

## Mandatory approval checkpoint

Before changing files:

1. Resolve each approved `Gxxx` against current source and state whether it is still valid.
2. Show the exact files and public API/configuration changes proposed.
3. Explain why every change is reusable across 12+ `sure-nbfc-*` services rather than pilot-specific.
4. Trace module ownership and dependency direction.
5. List compatibility, disclosure, tenant-isolation, business-availability, TLS, reactive, callback, and performance risks.
6. List exact tests and validation commands.
7. Identify any public contract or security decision requiring an ADR.

Stop and ask for explicit approval of that concrete change set. Do not edit before approval.

## Implementation after approval

Implement the smallest coherent generic capability for only the approved IDs. Keep APIs typed and bounded; never accept free-form class names, JSON paths, partner IDs, tenant IDs, labels, stages, or unbounded maps where a validated registry or enum is required. Automatic instrumentation must invoke the business transport exactly once and must not consume/replay/retain streams or source DTOs. Plaintext hooks may operate only where business code already owns authorized plaintext and must sanitize immediately.

Add focused unit, integration, negative security, failure-injection, concurrency/reactive, configuration-binding, and consumer tests appropriate to each gap. Prove disabled mode and backend/publisher/sanitizer failure cannot change business behavior. Prove prohibited data is absent before queue admission and from downstream fixtures where applicable. Prove context cleanup and exact terminal emission for async/callback paths.

Run the narrowest module tests first, then the applicable repository build, security, local integration, naming, profile, documentation, and aggregate verification gates. Do not claim B003 from shortened performance evidence. Distinguish pre-existing blockers from regressions.

Update public documentation, configuration metadata, ADRs, `PLANS.md`, and agent state only when the approved generic change requires it. Create one coherent local commit containing only this approved scope. Report the commit hash, validation results, preserved B001/B002/B003 status, and any unresolved decisions. Do not push or deploy.
