# Telemetry Contract

## Status and vocabulary

This revised M1 contract is normative for M2-M7. Wire schema version 2 and its async/callback core types are implemented. Schema-1 bodies remain accepted only inside schema-1 envelopes for the bounded N-1 migration window; new Spring integrations produce schema 2. A queued object is always partner-safe derived observability. Partner exchange data and internal-only information are different data classes and cannot implement `TelemetryRecord`.

All record types are immutable Java 17 records or final value classes. Collections are defensive, insertion-ordered, and bounded. Required values are non-null. Enums serialize as the exact uppercase names shown here unless a wire value is explicitly given. Time is UTC RFC 3339 with nanoseconds when available. Log durations are non-negative integer milliseconds; Micrometer durations are seconds from a monotonic clock.

An `interaction` is one observed HTTP exchange. An asynchronous `journey` can contain the original outbound exchange, an acknowledgement, one or more callback attempts, background processing, and business events. A callback attempt is one inbound HTTP delivery and has its own `interactionId` and `callbackAttemptId`.

## Core object model

```text
TelemetryRecord (sealed)
├── OutboundApiRequestRecord
├── OutboundApiResponseRecord
├── AsyncAcknowledgementRecord
├── CallbackRequestRecord
├── CallbackResponseRecord
├── CallbackProcessingEventRecord
└── PartnerBusinessEventRecord

TelemetryEnvelope<T extends TelemetryRecord>
├── schema and event identity
├── configured service identity
├── trusted PartnerContext
├── InteractionContext
├── CaptureDecision
└── T body

InteractionContext
├── interactionKind and direction
├── interactionId and eventSequence
├── optional callbackAttemptId
├── configured correlationProfileId
└── CorrelationIdentifiers

CorrelationIdentifiers
├── applicationId
├── loanId
├── originalCorrelationId
├── partnerReferenceId
├── externalTransactionId
├── callbackReferenceId
└── requestId

SafeValue (sealed and bounded)
├── SafeString
├── SafeNumber
├── SafeBoolean
├── SafeObject<String, SafeValue>
├── SafeArray<SafeValue>
└── SafeNull
```

There is no safe binary, raw JSON string, arbitrary object, throwable, stream, publisher, byte-array, request, or response subtype. `OmittedBinaryMetadata` is descriptive metadata, not a binary value.

## Common envelope

| Field | Type / bound | Loki placement | Contract |
| --- | --- | --- | --- |
| `schemaVersion` | integer, target `2` | line | Alloy drops unknown major versions |
| `eventId` | UUID | structured metadata | Generated only after a safe record exists |
| `eventType` | bounded enum | label | One of the seven record types' wire names |
| `eventDomain` | `API`, `ASYNC`, `CALLBACK`, `BUSINESS` | label | Bounded grouping, never user supplied |
| `occurredAt` | timestamp | timestamp and line | Time the fact occurred; server/application owned |
| `observedAt` | timestamp | line | Time the safe record was constructed |
| `serviceName` | configured token, 1-63 | label | Never derived from a request |
| `serviceVersion` | configured token, 1-63 | structured metadata | Build/release identity |
| `market` | configured token | label | Fixed per deployment |
| `environment` | `DEV`, `STAGE`, `PROD` | `deployment_environment` label | Fixed per ECS stack |
| `partnerKey` | configured canonical key | routing only | Removed by Alloy; never displayed or used as tenant header |
| `direction` | enum | label | `OUTBOUND_TO_PARTNER` or `INBOUND_FROM_PARTNER` |
| `interactionKind` | enum | line/metadata | `SYNC_OUTBOUND`, `ASYNC_INITIATION`, or `CALLBACK` |
| `interactionId` | UUID | structured metadata | Joins records for exactly one HTTP exchange |
| `callbackAttemptId` | UUID or absent | structured metadata | Required for every callback delivery; new for each retry/delivery |
| `correlationProfileId` | configured token, 1-63 | structured metadata | Groups compatible outbound/ack/callback APIs; never user supplied |
| `eventSequence` | integer 0..2^31-1 | line | Monotonic only inside one observation |
| `timelineStage` | bounded enum or absent | structured metadata/line | Required for async/callback timeline records |
| `captureMode` | enum | line | Effective `FULL_SANITIZED`, `METADATA_ONLY`, or `NO_PAYLOAD` |
| `payloadStatus` | enum | line | Capture result without input-bearing detail |
| `outcome` | bounded enum | label | Stable operational/business outcome |
| `severity` | `INFO`, `WARN`, `ERROR` | label | Derived from configured mapping |
| correlation identifiers | validated strings <=128 | structured metadata | All available typed identifiers; never labels |

Wire event types are `outbound_api_request`, `outbound_api_response`, `async_acknowledgement`, `callback_request`, `callback_response`, `callback_processing_event`, and `partner_business_event`. These values and the four event domains are fixed registries and do not create unbounded streams.

`payloadStatus` is one of `CAPTURED`, `NOT_REQUESTED`, `DISABLED`, `UNSUPPORTED_CONTENT_TYPE`, `BINARY`, `BASE64`, `OVERSIZE`, `MALFORMED`, `NOT_ALLOWLISTED`, `STREAM_NOT_CONSUMED`, or `UNSUPPORTED_INTEGRATION`. It never contains an exception, untrusted field name, raw content type, or payload fragment.

## Partner context model

`PartnerContext` is created only by a configured resolver operating on authenticated server-side state:

| Field | Classification | Rules |
| --- | --- | --- |
| `market` | trusted deployment | Must equal immutable service/stack configuration |
| `environment` | trusted deployment | Must equal the ECS cluster environment |
| `canonicalPartnerKey` | trusted routing | Configured ASCII token; never a raw partner header/body value |
| `tenantRouteId` | internal-only routing | Opaque generated value; never serialized into the partner line |
| `partnerSlot` | trusted metric dimension | `p001`-`p064`, not a raw partner identifier |
| `subjectSource` | internal enum | Resolver/adapter name, not a user or credential value |
| `trustLevel` | enum | Only `AUTHENTICATED_SERVER` may emit partner records |

Resolvers are ordered and must resolve zero or one context. Missing, unknown, untrusted, stale, disabled, wrong-route, or conflicting results produce no partner record and no fallback tenant. Outbound context comes from server-owned integration configuration plus authenticated business context where applicable. Callback context comes only from the host service's authenticated principal or verified-signature result through a configured callback resolver. The starter does not authenticate a partner, parse a tenant header, or expose a public context constructor.

When callback authentication requires reading the body, ingress time may be held as a bounded primitive, but no body, identifiers, or partner telemetry is constructed until authentication succeeds. Authentication/signature failure produces only internal bounded evidence with no input-bearing details.

## Interaction and identifier model

`InteractionContext` is immutable and copied into the already-safe envelope before queue admission. For a synchronous outbound call, request and response share one `interactionId`. For an async initiation, outbound request and acknowledgement share one `interactionId`. Every callback delivery has a new interaction and callback attempt identity; processing and response facts for that delivery reuse both values. Background work spawned after a `202` carries an explicit immutable snapshot and can emit later processing events with the same attempt identity.

The identifier fields are:

| Field | Typical owner / purpose |
| --- | --- |
| `applicationId` | Samsung/service business application anchor |
| `loanId` | Samsung/service loan anchor |
| `originalCorrelationId` | Correlation ID used on the initiating interaction |
| `partnerReferenceId` | Partner acknowledgement/reference anchor |
| `externalTransactionId` | External protocol transaction anchor |
| `callbackReferenceId` | Partner callback/idempotency reference |
| `requestId` | Individual protocol/request anchor |

All are optional because a legitimate malformed/early callback may lack some identifiers. Each is accepted only through a per-API typed extractor and validator. Default syntax is ASCII letters, digits, `.`, `_`, and `-`, length 1-128. Whitespace, control characters, URI syntax, Base64 padding, and values matching secret/card/credential detectors are rejected. Removal rules always win. The schema-1 `correlationId` and `partnerReference` names map to `originalCorrelationId` and `partnerReferenceId` only through an explicit migration adapter.

The manifest defines a `correlationProfile` that links compatible initiation, acknowledgement, callback, processing, and business-event API definitions. It fixes the enabled identifier types, stable versus weak classification, normalizer/validator, and identifier types that must be singleton within one journey. The safe default marks application and loan identifiers singleton and original-correlation/request identifiers weak; partner-specific changes require reviewed synthetic collision tests. A record cannot select or invent its profile at runtime.

Identifiers are observability correlation hints, never authentication, tenant routing, callback authorization, signature verification, or business-idempotency inputs. The application supplies all identifiers it knows at each fact. In particular, the async acknowledgement should include both original Samsung identifiers and newly issued partner/external references so it becomes a correlation bridge.

## Deterministic asynchronous correlation

No in-process or durable observability correlation store exists on the business path. The tenant-fixed query gateway resolves a journey as a bounded graph of typed identifiers:

1. Authenticate the datasource credential and fix exactly one tenant before accepting a search value.
2. Require a typed, syntax-valid seed and a time range no wider than 16 days. An optional configured profile narrows the search; without it, return separate candidates for at most eight matching profiles and never merge profiles.
3. Query exact structured-metadata equality inside one or more fixed low-cardinality stream selectors and one configured correlation profile.
4. Create an edge among all typed identifiers co-occurring in each trusted record.
5. Do not bridge two stable branches solely through a weak ID. If a component would contain multiple values for a profile-declared singleton identifier type, stop that ambiguous edge and mark `CONFLICT`.
6. Expand at most three rounds, 32 unique identifiers, and 500 records per round; stop on a fixed point, 2 MiB response projection, or 10-second gateway deadline.
7. Never expand across a partner tenant/profile, never use a user-selected tenant, and never use unbounded regular expressions.
8. Return correlation status `COMPLETE`, `PARTIAL_LIMIT`, `UNRESOLVED`, `WEAK_MATCH`, or `CONFLICT`.

Application, loan, external transaction, partner reference, and callback reference are stable anchors. Original correlation and request IDs are weak transport anchors; a journey found only through them is marked `WEAK_MATCH`. If one typed stable identifier connects incompatible stable branches, the resolver returns `CONFLICT` and does not silently merge them. The display anchor is the first available value in the deterministic order application, loan, external transaction, partner reference, callback reference, original correlation, request. This ordering is presentation only; all keys participate in matching.

Records are sorted by `occurredAt`, then `observedAt`, then `eventId`. Late and out-of-order callbacks therefore appear at their actual times. `eventSequence` orders facts only within an observation and never invents global causality. Loss from at-most-once telemetry is shown as partial coverage, not reconstructed as a fact.

## Outbound API request record

`OutboundApiRequestRecord` represents a configured call from a Samsung/service application to a partner:

| Field | Type / source | Notes |
| --- | --- | --- |
| `apiId` | configured token | Stable ID, never a raw URL |
| `routeTemplate` | configured template | No path parameter values |
| `exchangeMode` | `SYNC` or `ASYNC_INITIATION` | Determines the valid terminal record |
| `method` | bounded HTTP enum | Standard methods plus `OTHER` |
| `attempt` | integer 1-10 | Trusted client retry attempt when available |
| `contentType` | normalized allowlist | Media type only; unsafe parameters removed |
| `declaredSizeBytes` | non-negative integer or absent | Does not cause body reading |
| `headers` / `query` | safe object or absent | Full-sanitized allowlists only; never raw strings |
| `payload` | bounded safe value or absent | Full-sanitized only |
| `transportState` | `DELEGATED` | Means the call was handed to the business client, not partner receipt |

For `ASYNC_INITIATION`, `timelineStage` is `ASYNC_REQUEST_SENT`. This wording means delegated to the configured HTTP client; the later acknowledgement record tells whether an acknowledgement was observed. The record is built before the client call from an already-safe projection and its existence does not promise a response.

## Outbound API response record

`OutboundApiResponseRecord` is valid only for `SYNC_OUTBOUND` and shares the request `interactionId`:

| Field | Type / source | Notes |
| --- | --- | --- |
| `apiId` | configured token | Matches the request observation |
| `httpStatus` | 100-599 or absent | Operational metadata |
| `statusClass` | bounded enum | `1XX`..`5XX`, `IO_ERROR`, `CANCELLED`, `UNKNOWN` |
| `outcome` | bounded enum | `SUCCESS`, `BUSINESS_REJECTED`, `TECHNICAL_FAILURE`, `CANCELLED`, `UNKNOWN` |
| `durationMs` | non-negative integer | Monotonic elapsed time |
| `errorCode` | configured/validated token <=64 | Never exception text or remote message |
| content/header/payload fields | same policy as request | `Set-Cookie` always removed |

Outcome mapping is configured per API. Defaults treat 2xx as success, explicitly mapped 4xx codes as business rejection, and other 4xx/5xx/I/O as technical failure. Observation never changes the business result.

## Async acknowledgement record

`AsyncAcknowledgementRecord` is the sole terminal HTTP record for an `ASYNC_INITIATION` interaction. It uses `ASYNC_ACK_RECEIVED` only when an HTTP acknowledgement/rejection was actually observed. Timeout, transport failure, and cancellation use `ASYNC_ACK_NOT_RECEIVED`; the outcome provides the exact bounded reason. A stage never claims receipt that did not occur.

| Field | Type / source | Notes |
| --- | --- | --- |
| `apiId` | configured token | Matches initiating request |
| `httpStatus` / `statusClass` | bounded metadata | Absent/IO status on transport failure |
| `ackOutcome` | enum | `ACCEPTED`, `REJECTED`, `NO_ACK_TIMEOUT`, `TRANSPORT_FAILURE`, `CANCELLED`, `UNKNOWN` |
| `outcome` | bounded common outcome | Used consistently by labels/metrics |
| `durationMs` | non-negative integer | Request-to-terminal acknowledgement observation |
| `processingDisposition` | enum | `PARTNER_PROCESSING_EXPECTED`, `TERMINAL_REJECTION`, `UNKNOWN` |
| `errorCode` | safe token or absent | Never free text |
| acknowledgement payload | safe value or absent | Separate `ASYNC_ACK` leg policy |
| correlation identifiers | all newly available | Must bridge original and partner IDs when present |

The record is emitted for HTTP rejection, timeout, cancellation, and I/O failure as well as accepted acknowledgements. It is not evidence that partner asynchronous processing completed.

## Callback request record

`CallbackRequestRecord` is emitted only after trusted callback partner context is established, but its server-owned `occurredAt` is the original ingress time. It represents transport receipt, not business completion.

| Field | Type / source | Notes |
| --- | --- | --- |
| `callbackApiId` / `routeTemplate` | configured | Callback-specific registry, no raw URI |
| `method` | bounded enum | Normally POST but not assumed |
| `deliveryClassification` | enum | `INITIAL`, `RETRY`, `DUPLICATE`, `UNKNOWN`; trusted business/auth adapter only |
| `httpProtocolMetadata` | bounded | Content type, declared size, approved headers only |
| `payload` | safe value or absent | Captured after auth/decryption and before business processing |
| `parsingStatus` | `PARSED`, `MALFORMED`, `NOT_ATTEMPTED` | No parser message |
| `receivedAt` | server timestamp | Equal to the ingress fact time |

Initial delivery uses `CALLBACK_RECEIVED`; a business-idempotency/auth adapter-confirmed retry or duplicate uses `CALLBACK_RETRY_RECEIVED`. Automatic interception does not infer retry merely because an in-memory event was seen before. A malformed authenticated callback remains a metadata-only request fact with safe identifiers if available.

## Callback processing result/event record

`CallbackProcessingEventRecord` represents a semantic fact the HTTP layer cannot infer. Its `timelineStage` must be one of:

- `CALLBACK_AUTHENTICATED`: host authentication/signature verification succeeded.
- `CALLBACK_VALIDATED`: configured schema/business validation succeeded where applicable.
- `CALLBACK_PROCESSING_STARTED`: synchronous or background business processing began.
- `CALLBACK_PROCESSED`: business processing reached its documented successful terminal point.
- `CALLBACK_PROCESSING_FAILED`: parsing, validation, or business processing reached a documented failure point.

Fields are `callbackApiId`, `processingMode` (`INLINE` or `BACKGROUND`), `processingPhase`, bounded `outcome`, safe `errorCode`, optional monotonic `durationMs`, optional configured `acceptedBeforeCompletion`, all correlation identifiers, and a bounded allowlisted `attributes` object. Free-text messages, exception classes/messages, stack traces, retry payload hashes, and arbitrary stages are prohibited.

`CALLBACK_RECEIVED` and `CALLBACK_PROCESSED` are always separate events. For a `202 Accepted` flow, `CALLBACK_RESPONSE_SENT` may occur before `CALLBACK_PROCESSING_STARTED`/terminal processing. For parsing or validation failure, the processing failure record names only the bounded phase and error code. Authentication failure without trusted context is internal-only and never becomes this record.

## Callback response record

`CallbackResponseRecord` represents the observed server response after callback handling. It uses `CALLBACK_RESPONSE_SENT` only for local `WRITE_COMPLETED`; write failure/cancellation uses `CALLBACK_RESPONSE_WRITE_FAILED`:

| Field | Type / source | Notes |
| --- | --- | --- |
| `callbackApiId` | configured token | Matches callback attempt |
| `httpStatus` / `statusClass` | bounded | Status selected by business framework |
| `outcome` | bounded | Response/business mapping, separately from processing result |
| `durationMs` | non-negative | Ingress-to-response terminal observation |
| `transportOutcome` | enum | `WRITE_COMPLETED`, `WRITE_FAILED`, `CANCELLED`, `UNKNOWN` |
| `errorCode` | safe token or absent | No exception text |
| response content/payload | safe value or absent | Captured after processing and before serialization/encryption |

`WRITE_COMPLETED` means the framework completed the local write publisher/stream; it is not proof the partner received the response. Business processing may succeed while response transmission fails; that is represented by a successful `CALLBACK_PROCESSED` event and a separate `CALLBACK_RESPONSE_WRITE_FAILED` response with `WRITE_FAILED`. Automatic and explicit integrations must share IDs and emit at most one response record.

## Partner-facing business event record

`PartnerBusinessEventRecord` models an allowlisted business/journey fact not adequately represented by an HTTP or callback lifecycle record:

| Field | Type / source | Notes |
| --- | --- | --- |
| `eventName` | configured token | At most 64 per service |
| `journeyStage` | configured token | Not free text; cannot use reserved callback stages inconsistently |
| `outcome` / `errorCode` | bounded | Same safe vocabularies |
| `amount` / `currency` | canonical decimal / ISO 4217 | Optional approved business fields |
| `tenure` | integer plus configured unit | Optional |
| `sku` / `product` | configured validated tokens | Optional |
| `attributes` | safe object | Per-event allowlist and payload limits |
| correlation identifiers | validated subset | All identifiers known at the event boundary |

Callers select a registered definition; they cannot invent event names or fields at runtime. Partner-safe events are operational telemetry, not business commands or an audit ledger.

## Callback edge-case representation

| Situation | Required representation |
| --- | --- |
| Duplicate or partner retry | New attempt/interaction; `CALLBACK_RETRY_RECEIVED`; stable callback reference if validated; business dedup result as bounded processing attribute |
| Out-of-order callback | Preserve real timestamps; query resolver sorts without rewriting causality |
| Callback after original timeout | Original `NO_ACK_TIMEOUT` remains; callback is a later attempt joined by any bridge identifiers |
| Unknown callback reference | Trusted callback records with `UNRESOLVED` query result; never fallback to another tenant |
| Missing `applicationId` | Record other validated identifiers; do not reject solely for missing application ID |
| Callback for wrong partner | No partner record; internal bounded denial only |
| Authentication/signature failure | Internal-only security fact/counter; no body/identifier/tenant fallback |
| Parsing failure | Trusted metadata-only callback request plus processing failure phase `PARSING` |
| Processing failure | `CALLBACK_PROCESSING_STARTED`, then `CALLBACK_PROCESSING_FAILED`, then observed response if any |
| Processing succeeds, response write fails | `CALLBACK_PROCESSED` success plus `CALLBACK_RESPONSE_WRITE_FAILED` and `transportOutcome=WRITE_FAILED` |
| Accepted before processing completes | Response `202` may precede explicit background processing events; immutable context snapshot links them |

## Callback lifecycle transition contract

The explicit callback observation enforces facts, not business control flow. It allocates the next `eventSequence` from a per-observation bounded counter at safe-record construction; the counter is never global and is not used for cross-service ordering.

| Flow | Permitted stage order |
| --- | --- |
| Inline success | `CALLBACK_RECEIVED|CALLBACK_RETRY_RECEIVED -> CALLBACK_AUTHENTICATED -> CALLBACK_VALIDATED? -> CALLBACK_PROCESSING_STARTED -> CALLBACK_PROCESSED -> CALLBACK_RESPONSE_SENT` |
| Inline parse/validation/business failure | `CALLBACK_RECEIVED|CALLBACK_RETRY_RECEIVED -> CALLBACK_AUTHENTICATED -> CALLBACK_VALIDATED? -> CALLBACK_PROCESSING_STARTED? -> CALLBACK_PROCESSING_FAILED -> CALLBACK_RESPONSE_SENT?` |
| Accepted/background | `CALLBACK_RECEIVED|CALLBACK_RETRY_RECEIVED -> CALLBACK_AUTHENTICATED -> CALLBACK_VALIDATED? -> CALLBACK_RESPONSE_SENT(202)` and `CALLBACK_PROCESSING_STARTED -> CALLBACK_PROCESSED|CALLBACK_PROCESSING_FAILED` may occur before or after the response |
| Local response write failure | Any valid processing path followed by `CALLBACK_RESPONSE_WRITE_FAILED`; it never rewrites the processing terminal |
| Authentication/signature failure | No partner callback stages; internal-only bounded denial |

`CALLBACK_AUTHENTICATED` is required when the configured host exposes a separate authentication result. `CALLBACK_VALIDATED` is optional only when the manifest declares no distinct validation phase. `CALLBACK_PROCESSING_STARTED` may have exactly one terminal `CALLBACK_PROCESSED` or `CALLBACK_PROCESSING_FAILED`; double/contradictory terminals are suppressed to an internal reason counter. Exactly one response terminal (`CALLBACK_RESPONSE_SENT` or `CALLBACK_RESPONSE_WRITE_FAILED`) is permitted. Receipt is at most once per attempt, while retry creates a new attempt. Missing telemetry due to a drop does not make a later fact illegal and never causes the SDK to block or synthesize the missing fact.

## SLI metric model

SLIs are Micrometer instruments, not queued telemetry records. `SliObservation` is an ephemeral bounded builder containing a manifest-defined API/interaction kind, direction, partner slot, outcome/status/stage, and monotonic duration. It updates only pre-registered meters and is discarded. It cannot contain identifiers or payloads. Exact metrics, formulas, series budgets, and callback/async semantics are normative in `metrics-sli.md`.

## Capture modes and leg policy

Capture policy is resolved independently for the configured leg: `OUTBOUND_REQUEST`, `OUTBOUND_RESPONSE`, `ASYNC_ACK`, `CALLBACK_REQUEST`, `CALLBACK_RESPONSE`, or `BUSINESS_EVENT`.

| Mode | Record behavior |
| --- | --- |
| `FULL_SANITIZED` | Metadata plus complete safe projection of explicitly allowed textual/scalar fields within hard limits |
| `METADATA_ONLY` | Configured API/status/duration/size bucket/outcome/error code and validated transaction identifiers; no header/query/body values |
| `NO_PAYLOAD` | No partner record for that leg; aggregate fixed-dimension SDK health may change |

Effective mode is the minimum privilege of global, partner, API, interaction kind, leg/direction, content type, runtime switch, and interceptor capability. `NO_PAYLOAD < METADATA_ONLY < FULL_SANITIZED`; no lower layer may increase it. Callback request and response legs default to metadata-only. Model construction rejects a `NO_PAYLOAD` envelope and rejects captured header/query/body values in metadata-only.

Pre-encryption capture occurs immediately before existing business encryption. Post-decryption capture occurs immediately after existing business authentication/decryption. Callback request capture occurs after trusted partner resolution and before business processing; callback response capture occurs after processing and before existing serialization/encryption. If the automatic interceptor cannot see authorized plaintext without changing semantics, it emits metadata only and the application uses the explicit scoped observation API.

## Serialization and processing order

1. Establish trusted partner context and a configured record definition.
2. Apply kill switches, capture mode, rate limit, and deterministic sampling. Stop without traversal when disabled/unsampled.
3. Extract only configured identifiers and metadata with validators.
4. Reject unsupported types/content types, binary signatures, Base64, and oversized candidates before a safe record or queue reservation exists.
5. Traverse only configured field/path/type schemas within depth/node/collection/string/output budgets.
6. Apply non-overridable removal, masking, value detectors, and final safe-tree self-scan.
7. Drop all references to source objects/buffers and construct the immutable bounded record.
8. Serialize canonical UTF-8 JSON and enforce the 64 KiB envelope maximum.
9. Reserve queue bytes and make one non-blocking offer.
10. Export a bounded partner-pure batch to Alloy; use no more than the single retry holding slot.
11. Alloy validates schema/fields again, sanitizes again, fixes labels/structured metadata/tenant, and drops on uncertainty.

If any step is uncertain, the payload or record is omitted and only a bounded reason counter changes. Diagnostics contain configured identifiers and reason enums only, never raw input.

## Wire and Loki contract

The application dispatcher uses OTLP/HTTP logs over private TLS to the market Alloy ingress. Each safe envelope is one OTLP log record. Fixed resource attributes contain service, market, and environment. A mixed drain is partitioned into partner-pure sub-batches. Every request carries one canonical partner routing key plus source authentication; it cannot supply `X-Scope-OrgID`.

Gzip runs only on the dispatcher thread. Delivery is best-effort at-most-once; the bounded retry can duplicate a batch, so `eventId` supports query/UI deduplication but Loki need not deduplicate.

- Labels are exactly `service_name`, `deployment_environment`, `market`, `event_domain`, `event_type`, `direction`, `outcome`, and `severity`.
- Structured metadata includes correlation profile plus the seven identifiers, event/interaction/callback-attempt identity, timeline stage, API ID, service version, and selected bounded status/error/product fields, within 32 entries and 8 KiB.
- The safe JSON line holds display metadata and sanitized payload, at most 64 KiB.
- Partner identity is the gateway-fixed tenant, never a label or trusted line field.

Alloy drops a record if parsing, version/type/stage validation, source-partner mapping, second-stage sanitization, metadata/line bounds, or serialization fails. It never forwards an original malformed line as fallback.

## Schema evolution

Schema 2 is a deliberate breaking expansion from the schema-1 request/response/event model. During implementation and rolling rollout, Alloy and dashboards support schema N and N-1. Schema-1 `PartnerApiRequest`, `PartnerApiResponse`, and `PartnerEvent` map only to their corresponding schema-2 outbound/business views; they cannot be reinterpreted as callback lifecycle facts. Producers never dual-write payload bodies. Additive safe fields are dropped until Alloy allowlists deploy first. Rename/removal or routing/classification/masking semantic changes require an ADR, migration expiry, and security corpus update.
