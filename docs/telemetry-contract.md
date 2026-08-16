# Telemetry Contract

## Status and vocabulary

This M1 contract is normative for M2-M7. A queued object is always partner-safe derived observability; raw partner exchange data and internal-only information are different types and cannot implement `TelemetryRecord`.

All record types are immutable Java 17 records or final value classes. Collections are defensive, insertion-ordered, and bounded. Null is not used for required fields. Enums serialize as documented strings. Time is UTC RFC 3339 with nanoseconds when available. Durations are integer milliseconds in logs and seconds in Micrometer.

## Core object model

```text
TelemetryRecord (sealed)
├── ApiRequestRecord
├── ApiResponseRecord
└── PartnerEventRecord

TelemetryEnvelope<T extends TelemetryRecord>
├── SchemaVersion
├── EventIdentity
├── ServiceIdentity
├── PartnerContext (trusted routing; partially serialized)
├── CaptureDecision
└── T body

SafeValue (sealed, bounded)
├── SafeString
├── SafeNumber
├── SafeBoolean
├── SafeObject<String, SafeValue>
├── SafeArray<SafeValue>
└── SafeNull
```

There is deliberately no `SafeBinary`, arbitrary object, throwable, stream, publisher, or byte-array subtype.

## Common envelope

| Field | Type / bound | Loki placement | Contract |
| --- | --- | --- | --- |
| `schemaVersion` | integer, initially `1` | line | Required; unknown major version is dropped by Alloy |
| `eventId` | UUID | structured metadata | Generated after a safe record exists |
| `eventType` | enum | label | `api_request`, `api_response`, `partner_event` |
| `occurredAt` | timestamp | timestamp/line | Business observation time; never client supplied |
| `observedAt` | timestamp | line | Safe-record creation time |
| `serviceName` | configured token, 1-63 | label | Never derived from a request |
| `serviceVersion` | configured token, 1-63 | structured metadata | Build/release identity |
| `market` | configured enum/token | label | Fixed per deployment |
| `environment` | `DEV`, `STAGE`, `PROD` | label | Fixed per deployment |
| `partnerKey` | configured canonical key | routing only | Removed from line at Alloy; maps to tenant/slot |
| `direction` | enum | label | `INBOUND_FROM_PARTNER`, `OUTBOUND_TO_PARTNER` |
| `interactionId` | UUID | structured metadata | Joins request and response for one observed call |
| `eventSequence` | integer 0-2^31-1 | line | Monotonic only inside one observation, not globally |
| `captureMode` | enum | line | `FULL_SANITIZED`, `METADATA_ONLY`, `NONE` |
| `payloadStatus` | enum | line | `CAPTURED`, `NOT_REQUESTED`, or omission reason |
| `severity` | bounded enum | label | `INFO`, `WARN`, `ERROR` |
| identifiers | validated strings <=128 | structured metadata | Defined below; never Loki labels |

`payloadStatus` values are `CAPTURED`, `NOT_REQUESTED`, `DISABLED`, `UNSUPPORTED_CONTENT_TYPE`, `BINARY`, `BASE64`, `OVERSIZE`, `MALFORMED`, `NOT_ALLOWLISTED`, and `STREAM_NOT_CONSUMED`. It never contains an exception or field name from untrusted input.

## Partner context model

`PartnerContext` is created only by a configured `PartnerContextResolver` operating on authenticated server-side state:

| Field | Classification | Rules |
| --- | --- | --- |
| `market` | trusted deployment | Must equal immutable service configuration |
| `environment` | trusted deployment | Must equal the ECS cluster environment |
| `canonicalPartnerKey` | trusted routing | Configured ASCII token; no raw partner header |
| `tenantRouteId` | internal-only routing | Opaque generated value; not exposed to application callers or line payload |
| `partnerSlot` | trusted bounded metric dimension | `p001`-`p064`; not a raw partner identifier |
| `subjectSource` | internal enum | Resolver/adaptor name, not user identity |
| `trustLevel` | enum | Only `AUTHENTICATED_SERVER` may emit; other values fail closed |

Resolvers are ordered and must produce zero or one result. Missing, unknown, untrusted, or conflicting results cause `NO_TRUSTED_CONTEXT` drop. An inbound header may be an input to the host service's authentication, but the starter may consume only the authenticated principal/claim produced by that service. A caller cannot set tenant, slot, or context through a public SDK setter.

## Identifier model

`applicationId`, `loanId`, `correlationId`, `requestId`, and `partnerReference` are allowed only when their per-API validator accepts them. Defaults allow ASCII letters, digits, `.`, `_`, and `-`, length 1-128; whitespace, control characters, URI syntax, Base64 padding, and values matching secret/card detectors are rejected. They are partner-safe transaction references, not authorization credentials.

These values are duplicated neither into labels nor metric tags. They are Loki structured metadata and safe JSON fields to support bounded time-range search. `eventId` and `interactionId` follow the same placement.

## API request record

`ApiRequestRecord` fields:

| Field | Type / source | Notes |
| --- | --- | --- |
| `apiId` | configured token | Stable identifier, never raw URI |
| `routeTemplate` | configured template | For example `/applications/{id}`; no path value |
| `method` | bounded enum | Standard HTTP methods plus `OTHER` |
| `attempt` | integer 1-10 | Optional retry attempt from trusted client integration |
| `contentType` | normalized allowlist | Media type only; parameters dropped except safe charset |
| `declaredSizeBytes` | integer or absent | Header/object size; never causes body reading |
| `headers` | `SafeObject` | Only per-API allowlisted headers after policy |
| `query` | `SafeObject` | Only named allowlisted query fields after policy; never raw query string |
| `payload` | `SafeObject`/`SafeArray` or absent | Present only for `FULL_SANITIZED` and `CAPTURED` |
| transaction identifiers | validated identifiers | Structured metadata placement |

The request record is emitted just before the underlying call for outbound clients and after trusted context resolution for inbound requests. Its existence never promises that a response follows.

## API response record

`ApiResponseRecord` fields:

| Field | Type / source | Notes |
| --- | --- | --- |
| `apiId` | configured token | Must match the request observation |
| `httpStatus` | integer 100-599 or absent | Allowed operational metadata |
| `statusClass` | enum | `1XX`, `2XX`, `3XX`, `4XX`, `5XX`, `IO_ERROR`, `CANCELLED`, `UNKNOWN` |
| `outcome` | enum | `SUCCESS`, `BUSINESS_REJECTED`, `TECHNICAL_FAILURE`, `CANCELLED`, `UNKNOWN` |
| `durationMs` | integer >=0 | Monotonic clock delta; wall clock is not used |
| `errorCode` | configured/validated token <=64 | Never exception text or remote message |
| `contentType` / `declaredSizeBytes` | bounded metadata | Same rules as request |
| `headers` | `SafeObject` | Response header allowlist; `Set-Cookie` always removed |
| `payload` | safe value or absent | Same capture modes/policy as request |
| transaction identifiers | validated identifiers | Structured metadata placement |

Outcome mapping is configuration driven per `apiId`; defaults treat 2xx as success, configured 4xx codes as business rejection, and other 4xx/5xx/I/O as technical failure. An observability failure never changes the returned/raised business result.

## Partner-facing event record

`PartnerEventRecord` models an application/journey fact not adequately represented as HTTP request/response:

| Field | Type / source | Notes |
| --- | --- | --- |
| `eventName` | configured allowlist token | Maximum 64 event names per service |
| `journeyStage` | configured enum/token | Stable stage, not free text |
| `outcome` | bounded enum | Same safe outcome vocabulary or configured enum |
| `errorCode` | safe token | Optional |
| `amount` | decimal string | Optional; canonical plain decimal, no currency symbol |
| `currency` | ISO 4217 token | Required when amount exists |
| `tenure` | integer plus configured unit | Optional allowed business field |
| `sku` / `product` | configured validated tokens | Optional |
| `attributes` | `SafeObject` | Per-event allowlist and payload limits |
| transaction identifiers | validated identifiers | Used for journey correlation/search |

Event names and attributes come from a registered `PartnerEventDefinition`; callers cannot invent arbitrary fields at runtime. Amount, tenure, SKU/product, partner references, API status/error codes, and operational metadata are allowed by policy but only inside the correct partner tenant.

## SLI metric model

SLIs are Micrometer instruments, not queued `TelemetryRecord` objects. `SliObservation` is an ephemeral builder containing `apiId`, direction, partner slot, configured outcome, status class, and monotonic duration. It updates pre-registered meters and is discarded.

Meter creation is permitted only from a startup registry generated from the market/service manifest. A runtime value cannot create a new meter/tag combination. Exact metrics, buckets, formulas, and cardinality budgets are normative in `metrics-sli.md`.

## Capture modes

| Mode | Record behavior |
| --- | --- |
| `FULL_SANITIZED` | Metadata plus complete safe projection of allowed textual/scalar payload fields within hard limits |
| `METADATA_ONLY` | Method/API/status/duration/content type/size bucket/outcome/error code/allowed identifiers; no headers, query values, or body |
| `NONE` | No request, response, or event record; only aggregate SDK health counters may change |

The effective mode is the minimum privilege of global, partner, API, direction, content-type, runtime kill switch, and interceptor capability. `NONE < METADATA_ONLY < FULL_SANITIZED`; no lower layer may increase it.

## Serialization and processing order

1. Establish trusted context and configured API/event definition.
2. Apply kill switch, capture mode, rate limit, and deterministic sampling.
3. Reject unsupported Java types/content types, binary signatures, Base64, and oversized candidates before queue insertion.
4. Traverse only configured fields with depth/count/string budgets.
5. Apply removal aliases/value detectors, then deterministic masking.
6. Construct a bounded safe tree and immutable record.
7. Serialize to canonical UTF-8 JSON and enforce the 64 KiB envelope cap.
8. Reserve queue bytes and make one non-blocking offer.
9. Export a bounded batch to Alloy; do not retry beyond the single holding slot.
10. Alloy rejects unknown schema/fields, sanitizes again, sets approved labels/structured metadata, and routes by its trusted fixed pipeline.

If any step is uncertain, the payload or event is omitted and a bounded reason counter changes. Diagnostic messages contain only enum reason and configured identifiers.

## Wire contract

The application dispatcher uses OTLP/HTTP logs over TLS to the market Alloy ingress. Each safe envelope is the body of one OTLP log record. Fixed resource attributes contain service, market, and environment; fixed log attributes contain schema/event fields. A mixed queue drain is partitioned into per-partner sub-batches, so every HTTP request supplies exactly one canonical partner key plus its source credential and cannot supply a Loki tenant header.

The exporter uses gzip only on the dispatcher thread. Batches are at-most-once; the single bounded retry may duplicate a batch if the response is lost, so `eventId` supports human/query deduplication but Loki storage is not required to deduplicate.

## Loki representation

- Labels: exactly the allowlist in `architecture.md`/ADR 0005, maximum eight.
- Structured metadata: identifiers, API ID, service version, status/error/product fields when helpful, within 32 entries and 8 KiB per record.
- JSON line: bounded display metadata and sanitized payload; maximum 64 KiB.
- Tenant: fixed by Alloy/gateway; not taken from the line.

Alloy drops a record if parsing, schema validation, tenant mapping, second-stage sanitization, metadata limits, or serialization fails. It never forwards the original malformed line as fallback.

## Schema evolution

Schema version is an integer major version. Alloy supports current N and previous N-1 during rolling SDK upgrades. Additive fields default to dropped until the Alloy allowlist is deployed first. Renaming or removal requires a new version or a time-bounded dual-safe-field migration. Routing, classification, or masking semantic changes require an ADR and security regression corpus update.
