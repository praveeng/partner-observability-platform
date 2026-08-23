# Grafana Alloy local data plane

`local-config.alloy` receives OTLP/HTTP logs from the trusted local gateway on three route-specific receivers. The gateway maps an authenticated synthetic SDK identity plus its configured partner route to exactly one receiver; each receiver validates the same fixed partner key and exports with a hard-coded opaque Loki tenant ID. There is no default receiver or payload-derived tenant.

Alloy is defense in depth after the SDK sanitizer. It accepts schema 2 and N-1 schema 1, enforces the architecture record-type and routing-context allowlists, bounds bodies and identifier syntax, drops credential/card/Base64-shaped records, masks phone/email/account/national-ID/address values again, removes routing and unknown metadata, and retains only the eight architecture-approved Loki labels plus the structured-metadata allowlist. Export queues are bounded at 64 requests per tenant, do not block on overflow, and retry for at most two seconds.

Architecture naming maps the requested lifecycle vocabulary as follows:

| Requested concept | Wire representation |
| --- | --- |
| `PARTNER_API_REQUEST` | schema-1 `partner_api_request`; schema-2 `outbound_api_request` |
| `PARTNER_API_RESPONSE` | schema-1 `partner_api_response`; schema-2 `outbound_api_response` |
| `ASYNC_ACK` | `async_acknowledgement` |
| `PARTNER_EVENT` | schema-1 `partner_event`; schema-2 `partner_business_event` |
| `CALLBACK_RECEIVED`, `CALLBACK_REQUEST` | `callback_request` with `timeline_stage=CALLBACK_RECEIVED` |
| `CALLBACK_PROCESSING_RESULT` | `callback_processing_event` with its terminal timeline stage/outcome |
| `CALLBACK_RESPONSE` | `callback_response` |
| `CALLBACK_RETRY` | a distinct `callback_request` attempt with `timeline_stage=CALLBACK_RETRY_RECEIVED` |

Alloy's loopback-only HTTP endpoint exposes its native receiver, exporter, drop, retry, and bounded-queue self-metrics on port `12345` by default. The local gateway uses HTTP only inside the isolated `LOCAL_SYNTHETIC` Docker networks; production transport remains private TLS as required by the architecture.
