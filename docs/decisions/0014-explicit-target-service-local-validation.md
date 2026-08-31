# ADR 0014: Explicit target-service local validation

- Status: Accepted
- Date: 2026-08-31
- Decision owners: Partner Observability platform and partner-service integration owners

## Context

The controlled synthetic applications are necessary for hostile payloads, failure injection,
reactive stress, tenant collisions, B001, B002, and B003. They cannot by themselves prove that a
real OpenAPI-generated partner service has the correct clients, callbacks, profiles, correlation,
and composite source dependency. SureWebServices can contain many `sure-nbfc-*` services, so an
automatic first-match or all-service runner would create uncontrolled scope and cross-service
contract mixing.

## Decision

Keep generic validation as the standalone default. Add a separate `TARGET_SERVICE` local layer that
requires one exact `TARGET_PARTNER_SERVICE`. Resolve it only as a direct child of explicit
`SUREWEBSERVICES_ROOT`, or of the platform repository's parent. Missing, conflicting, wildcard, path,
and escaping values fail closed; directory enumeration never chooses an execution target.

OpenAPI preparation recurses only inside that target. It writes deterministic structural JSON and
does not retain examples, defaults, server URLs, payloads, or credentials. Candidate interaction
patterns never become semantic correlation/capture mappings automatically. A reviewed mapping
must classify every operation; `NOT_COVERED` fails readiness. Generated OpenAPI source remains
read-only.

The selected service is built with Gradle composite `--include-build` against this source tree. A
reviewed target-owned adapter supplies service-specific mock/start/journey mechanics and emits a
closed machine-readable result. The platform supplies an optional local-only fixed-route transport
on the starter's bounded publisher thread and an ephemeral fixed gateway route. That helper is a
development/local dependency, not production transport and not a second callback architecture.

## Security and availability consequences

- One exact target prevents unintended builds, execution, and contract mixing.
- Server-configured partner identity and fixed gateway routing remain the tenant boundary; OpenAPI
  fields, callback bodies, and target names never select a Loki tenant.
- Only synthetic local data is allowed. Local credentials are generated ephemerally and are not
  written to generated contract fixtures.
- Export still occurs after bounded queue admission. Gateway/backend failure remains telemetry loss
  and cannot add a synchronous business-thread dependency.
- The mode cannot access AWS or real partner endpoints and does not change DEV/STAGE/PROD TLS.

## Alternatives considered

- Replacing the synthetic app with the pilot was rejected because it would weaken hostile and
  deterministic coverage and make standalone development depend on SureWebServices.
- Scanning every `sure-nbfc-*` service was rejected because it violates explicit scope and can mix
  partner semantics.
- Runtime Java self-modification and partner-specific SDK code were rejected in favor of reviewed
  data-driven fixtures and a narrow target-owned adapter.
- Publishing/copying starter JARs was rejected because the enterprise checkout supports source
  dependency through Gradle composite builds.

## Implementation and verification

`scripts/test-target-service-local.sh` verifies selection rejection, exact sibling resolution,
foreign-service isolation, fail-closed coverage, redaction, and fixed route rendering using only
synthetic workspaces. `test/integration/run-local-service-end-to-end.sh` is the real selected-service
entry point. The current standalone checkout lacks `sure-nbfc-unionbank-ph`, so pilot execution is a
required SureWebServices follow-up and is not represented as passed evidence.
