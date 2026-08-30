# Integrate Partner Observability starter through Gradle composite build

## Mode

APPROVAL-GATED IMPLEMENTATION. No artifact publishing, JAR copying, deployment, AWS access, Terraform work, or generated OpenAPI edits.

## Objective

In the SureWebServices enterprise workspace, integrate `sure-nbfc-unionbank-ph` with the source module `sure-partner-observability-spring-boot-starter` through the monorepo's established Gradle composite-build/source-dependency pattern. The result must work from a clean local and GitHub Actions checkout without Artifactory, Nexus, GitHub Packages, or copied binaries.

Read the root and project `AGENTS.md` files, all `settings.gradle`/`settings.gradle.kts`, `build.gradle` files, Gradle properties, version catalogs, convention plugins, wrapper settings, CI checkout scripts, and existing composite builds/source substitutions in SureWebServices. Read the starter's current build metadata and consumer documentation. Use the real enterprise convention; do not invent a parallel build framework.

## Fixed constraints

- Java 17, Spring Boot 2.7.x, Gradle Groovy compatibility is mandatory.
- Partner Observability Gradle group is `com.samsung.sure`; Java imports are `com.samsung.sure.partner.observability.*`.
- Module and artifact names use `sure-partner-observability-*`.
- The ordinary service dependency is `sure-partner-observability-spring-boot-starter`; consumers must not depend directly on implementation internals merely to make composite resolution work.
- Preserve dependency direction: starter to autoconfigure to core; core has no Spring dependency.
- Preserve exactly `local`, `dev`, `stage`, and `prod` and properties-only application configuration.
- No TLS client construction or security behavior may change.
- Do not modify generated OpenAPI source.
- Keep service-code changes minimal. Prefer auto-configuration and configuration over manual bean wiring.

The integration must retain conditional automatic support for the clients actually present: RestTemplate, WebClient, and/or OkHttp. Do not add unused clients just to exercise the starter. Automatic interceptors must reuse service-owned clients/connectors, execute business transport exactly once, and preserve response/stream/reactive semantics. Use generic callback lifecycle integration and the generic pre-encryption/post-decryption hook only where the inspected service genuinely needs them.

## Approval checkpoint

Before changes, report:

1. Current SureWebServices Gradle topology and comparable composite/source dependency examples.
2. Exact clean-checkout path and dependency-substitution mechanism.
3. Exact root/settings/build/service files proposed.
4. Resolved group, module, project, and version coordinates.
5. Dependency/classpath effects and optional-client activation.
6. Minimal handwritten service changes, if any.
7. Local/GHA checkout assumptions, risks, tests, and rollback.

Stop and ask for explicit approval. Do not edit first.

## Implementation and validation after approval

Implement the smallest established composite-build change. Avoid absolute paths and developer-machine state. Ensure build ordering, test tasks, IDE import, dependency locking, configuration cache, and GHA checkout layout remain compatible where those mechanisms exist.

Validate from clean build state:

- Sure Partner Observability builds and its starter consumer tests pass.
- The pilot resolves the starter from source without a published artifact or copied JAR.
- The pilot compiles using only Samsung Sure imports.
- Its Spring context starts with each canonical profile using safe test overrides.
- Actual RestTemplate/WebClient/OkHttp beans receive intended automatic instrumentation exactly once.
- Callback and encryption hooks compile and execute only where required.
- Disabled observability leaves service behavior unchanged.
- Generated OpenAPI files and TLS/client settings remain unchanged.
- Applicable unit, integration, security, OpenAPI coverage, and Gradle dependency checks pass.

Create one coherent local commit for the approved build integration. Do not push or deploy. Report exact files, source-resolution evidence, clean-checkout evidence, test results, and commit hash.
