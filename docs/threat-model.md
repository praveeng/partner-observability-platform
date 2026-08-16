# Threat Model

## Scope and assets

Protected assets include partner separation, customer and transaction data, credentials and authentication material, service availability, telemetry integrity, cloud credentials, and operator access. The model covers application instrumentation, queues/exporters, Alloy, Loki, Prometheus, Grafana, local Docker Compose, and future AWS ECS infrastructure.

## Trust boundaries

- Untrusted inbound request/application values to the SDK.
- Business thread to the bounded telemetry subsystem.
- Application process to Alloy.
- Alloy to partner-specific Loki tenant and Prometheus.
- Operator/browser to Grafana and backend query APIs.
- Terraform runner to non-production AWS APIs.

Partner identity is trusted only after server-side authentication/authorization. Client fields remain untrusted even when their names resemble tenant identifiers.

## Principal threats and required controls

| Threat | Example | Required control | Verification target |
| --- | --- | --- | --- |
| Sensitive disclosure | Secret in JSON, header, exception, nested object | Allowlist, recursive limits, removal/masking, fail closed | Security corpus proves absence from queues and sinks |
| Binary evasion | Base64, misleading content type, byte array | Type/content/size detection before enqueue | Adversarial fixtures never reach queue |
| Cross-partner access | Forged tenant header or query variable | Authenticated server mapping and per-tenant credentials/policy | Cross-tenant writes/queries denied |
| Availability coupling | Backend stalls request threads | Non-blocking bounded queue and async timeout/circuit behavior | Backend outage leaves business outcome unchanged |
| Resource exhaustion | Oversized/deep payload, label explosion, queue flood | Size/depth/cardinality limits and drop policy | Load tests stay within agreed budgets |
| Telemetry injection | Newlines/control data or forged fields | Structured encoding, safe field names, fixed envelope | Parser/query behavior remains bounded and unambiguous |
| Credential compromise | Secrets in repository, logs, Terraform state | Secret references, redaction, ignored state, least privilege | Secret scanning and IaC checks |
| Dashboard bypass | Direct Loki access outside Grafana filters | Backend authorization and network/IAM boundaries | Direct unauthorized request rejected |

## Abuse assumptions

Attackers may control payload structure, nesting, encodings, content types, identifiers, timing, and volume. Backends may be slow, unavailable, or return malformed errors. Configuration may be incomplete. Controls must remain fail-safe under all of these conditions.

## Residual questions

Authentication source, tenant identifier mapping, encryption integration boundary, limits, retention, and ECS/IAM topology are unresolved in M0 and listed in `decisions-needed.md`. M1 must assign owners and acceptance evidence.
