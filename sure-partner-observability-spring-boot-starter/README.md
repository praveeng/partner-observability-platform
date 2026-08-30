# Spring Boot starter

The ordinary Java 17 / Spring Boot 2.7 consumer entry point. This module contains dependency wiring only; implementation remains in core and auto-configuration.

```groovy
dependencies {
    implementation 'com.samsung.sure:sure-partner-observability-spring-boot-starter:0.1.0-SNAPSHOT'
}
```

Minimal metadata-only outbound configuration:

```properties
partner-observability.enabled=true
partner-observability.service-name=credit-service
partner-observability.service-version=1.0.0
partner-observability.market=uk
partner-observability.environment=dev
partner-observability.partners[0].key=PARTNER_A
partner-observability.partners[0].tenant-route-id=opaque-tenant-a
partner-observability.partners[0].slot=p001
partner-observability.outbound[0].name=CREDIT_SUBMISSION
partner-observability.outbound[0].origin=https://partner-a.example
partner-observability.outbound[0].path=/partner-api/applications
partner-observability.outbound[0].method=POST
partner-observability.outbound[0].partner=PARTNER_A
partner-observability.outbound[0].correlation-profile=CREDIT_ASYNC
partner-observability.outbound[0].capture-mode=METADATA_ONLY
```

`origin` is a security boundary for automatic capture: scheme, host, effective port,
method, and path must all match before a record is attributed to the configured partner.
Only HTTPS origins validate. The test-only `local-synthetic=true` exception is restricted
to `local`/`DeploymentEnvironment.LOCAL` and literal loopback HTTP; it must never appear in a
deployed partner service.

Full sanitized capture additionally requires an exact `safe-fields` allowlist. Callback entries use the same partner/profile model plus a fixed callback path and either an authenticated principal mapping or a host-provided trusted resolver. See the auto-configuration README for the capture and trust boundaries.

The starter also brings Spring Boot Actuator and the Prometheus Micrometer registry. Expose `health,prometheus` only on the private management network. Meter combinations are fixed from the configured partner/API manifest, use opaque `partner_slot`, and are rejected at startup above the 10,000-series budget; no transaction identifier becomes a metric label.

Selected existing SLF4J/Logback compatibility is optional and disabled by default. It requires exact logger/template selections, trusted scoped partner context, and configured scalar argument schemas; it never copies rendered messages or throwables and does not replace the service's existing appenders. See the auto-configuration README for configuration and safety rules.
