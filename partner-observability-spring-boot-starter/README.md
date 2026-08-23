# Spring Boot starter

The ordinary Java 17 / Spring Boot 2.7 consumer entry point. This module contains dependency wiring only; implementation remains in core and auto-configuration.

```groovy
dependencies {
    implementation 'com.partner.observability:partner-observability-spring-boot-starter:0.1.0-SNAPSHOT'
}
```

Minimal metadata-only outbound configuration:

```yaml
partner-observability:
  enabled: true
  service-name: credit-service
  service-version: 1.0.0
  market: uk
  environment: DEV
  partners:
    - key: PARTNER_A
      tenant-route-id: opaque-tenant-a
      slot: p001
  outbound:
    - name: CREDIT_SUBMISSION
      path: /partner-api/applications
      method: POST
      partner: PARTNER_A
      correlation-profile: CREDIT_ASYNC
      capture-mode: METADATA_ONLY
```

Full sanitized capture additionally requires an exact `safe-fields` allowlist. Callback entries use the same partner/profile model plus a fixed callback path and either an authenticated principal mapping or a host-provided trusted resolver. See the auto-configuration README for the capture and trust boundaries.

The starter also brings Spring Boot Actuator and the Prometheus Micrometer registry. Expose `health,prometheus` only on the private management network. Meter combinations are fixed from the configured partner/API manifest, use opaque `partner_slot`, and are rejected at startup above the 10,000-series budget; no transaction identifier becomes a metric label.

Selected existing SLF4J/Logback compatibility is optional and disabled by default. It requires exact logger/template selections, trusted scoped partner context, and configured scalar argument schemas; it never copies rendered messages or throwables and does not replace the service's existing appenders. See the auto-configuration README for configuration and safety rules.
