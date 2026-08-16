# Architecture

## Status

M0 architectural baseline. Component choices are constrained; exact protocols, queue limits, and AWS topology are pending M1 decisions.

## Logical flow

```text
business request
    |
    +-- Spring integration -> classify/sanitize -> bounded in-process queue
                                                    | saturation: drop + count
                                                    v
                                             async exporter boundary
                                                    |
                                                    v
                                              Grafana Alloy
                                              /            \
                                      tenant-routed logs   health metrics
                                            |                  |
                                           Loki            Prometheus
                                              \              /
                                                   Grafana
```

No arrow from an observability component returns a success prerequisite to the business request. Failure, slowness, or absence of every downstream component must leave business behavior unchanged except for the loss of telemetry.

## Module boundaries

- Core owns framework-independent models, policies, sanitization, bounded buffering, failure containment, and emission interfaces.
- Auto-configuration owns Spring Boot 2.7 conditions, interceptors, lifecycle wiring, and configuration validation.
- Starter provides the normal one-dependency integration surface.
- Test app contains synthetic endpoints and fixtures solely for verification.

Core cannot depend on Spring. Backend client details must remain behind asynchronous exporter boundaries. SLF4J is the logging facade; Logback is the supported Spring Boot runtime backend.

## Data and trust boundaries

1. Untrusted application/request data enters classification.
2. Binary/type/size/Base64 rejection occurs before queue admission.
3. Removal, masking, and allowlisting produce the telemetry-safe representation.
4. A bounded queue separates business execution from export.
5. Trusted server-side authentication supplies partner identity; the exporter maps it to a Loki tenant.
6. Alloy and backends run outside the application process and cannot participate synchronously in request success.

## Deployment baseline

Docker Compose is the local integration environment. Terraform provisions the selected non-production AWS ECS topology. Kubernetes and Helm are out of scope. See `deployment-model.md` for boundaries and `decisions-needed.md` for unresolved topology.

## Architecture validation

Later milestones must demonstrate queue boundedness, saturation drops, exception containment, backend outage independence, sanitization before admission, tenant separation, bounded cardinality, and compatibility with Java 17 / Spring Boot 2.7.
