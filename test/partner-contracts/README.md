# Target-service contract fixtures

This directory supports the optional `TARGET_SERVICE` local validation layer. The default local
layer remains the repository's MVC/reactive synthetic applications and does not inspect any
`sure-nbfc-*` repository.

`scripts/prepare-target-service-test-fixtures.sh` requires one exact `TARGET_PARTNER_SERVICE`. It
resolves only that direct child of `SUREWEBSERVICES_ROOT` (or the platform repository's parent),
parses OpenAPI documents only below that target, and writes structural JSON to
`generated/<target>/`. It never chooses a target from directory enumeration.

Generated inventories intentionally omit OpenAPI examples, defaults, server URLs, request bodies,
credentials, and certificate material. They contain operation/schema structure, source hashes,
generic pattern candidates/capability gaps, data-driven synthetic value recipes, and coverage
decisions. Fixture recipes contain strategies rather than payload values: binary values are made
only at execution time and are asserted absent before queue admission. A checked-in reviewer-owned mapping may be
placed at `mappings/<target>/coverage.json`. Every operation key in that mapping must exactly match
the generated inventory. `NOT_COVERED` fails preparation; unreviewed operations default to that
status.

The mapping format is:

```json
{
  "schemaVersion": 1,
  "service": "sure-nbfc-example",
  "operations": {
    "openapi/api.yaml#POST#/applications": {
      "direction": "OUTBOUND",
      "interactionPattern": "SYNC_JSON",
      "observabilityMechanism": "STARTER_RESTTEMPLATE",
      "testScenario": "target-owned-local-journey-id",
      "status": "COVERED_BY_GENERIC_FIXTURE",
      "correlationMappings": {
        "applicationId": "$.applicationId"
      },
      "justification": "Confirmed against the selected service configuration and handwritten client."
    }
  }
}
```

Allowed statuses are `COVERED_BY_GENERIC_FIXTURE`, `COVERED_BY_GENERATED_FIXTURE`,
`REQUIRES_GENERIC_CAPABILITY`, `EXPLICITLY_EXCLUDED`, and `NOT_COVERED`. Semantic mappings such as a
partner application number to `partnerReferenceId` must be confirmed in this reviewed file; the
generator does not invent them.

## Local integration adapter

`mappings/<target>/local-integration.json` is a separate reviewed execution contract. Start from
`local-integration-contract.example.json`. The platform runner builds the exact target with
`--include-build <sure-partner-observability>` and checks runtime dependency evidence for
`sure-partner-observability-spring-boot-starter`; it never copies a JAR or publishes an artifact.

The target-owned adapter named by `adapterScript` is the narrow boundary for service-specific local
startup and journeys. It must use the supplied `SPRING_PROFILES_ACTIVE=local`, local gateway file,
ephemeral synthetic SDK credential, and exact contract directory. It starts/reuses the local
Alloy/Loki/Prometheus/Grafana topology, starts only the selected service and its local mock partner,
drives the reviewed operation scenarios, and writes the requested `PARTNER_OBSERVABILITY_RESULT_FILE`.
That result must prove build/startup, outbound and callback journeys, telemetry/metrics/Grafana,
query authorization, PII masking, binary omission, and absence of external partner traffic. The
adapter remains in the selected service because only that repository knows its actual generated
code boundary, authentication fixtures, endpoints, and startup tasks.

The selected service should place
`com.samsung.sure:sure-partner-observability-local-test-support` on its existing Gradle
development/local runtime configuration when it has no equivalent reviewed local publisher. The
helper transitively exposes the starter for that local runtime but is not packaged as production
transport. Its `partner-observability.local-test-transport.*` properties are runtime-injected by
the adapter and accept only the reviewed fixed partner key and isolated gateway.
