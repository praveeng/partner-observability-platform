# Synthetic test application

Java 17 / Spring Boot 2.7 compatibility fixture for SDK integration, security, failure-mode, concurrency, and performance verification. It is not a production example. The application and its dedicated mock partner server bind to loopback and use generated synthetic values only.

Its in-process HTTP mock is the `local` profile's guarded `LOCAL_SYNTHETIC` exception defined by `docs/transport-security.md`: loopback-only, ephemeral, synthetic, and not deployable as a DEV/STAGE/PROD partner endpoint. It must never be exposed through a public/container interface or configured for a real partner. ECS DEV mock partners use HTTPS; future TLS verification fixtures use synthetic certificates within this non-production boundary.

Run it with `SPRING_PROFILES_ACTIVE=local`. The module has properties-only profile files for
`local`, `dev`, `stage`, and `prod`; non-local files start observability disabled until an approved
runtime manifest is injected. Their context tests prove binding and isolation but do not make this
synthetic fixture a production application or contact AWS/partners.

## Fixture control plane

Run the application locally and trigger predefined outbound scenarios:

```text
POST /fixture/rest/{alpha|beta}/{scenario}
POST /fixture/webclient/{alpha|beta}/{scenario}
POST /fixture/okhttp/{alpha|beta}/{scenario}
POST /fixture/encrypted-rest/{alpha|beta}
POST /fixture/async/{alpha|beta}/{async-scenario}
GET  /fixture/async/runs/{runId}
GET  /fixture/async/security-counters
```

`scenario` is a kebab-case or enum-form value from `SyntheticScenario`. Responses are bounded summaries containing status, byte length, and digest; large or sensitive fixture bodies are not returned by the control plane.

The `alpha` and `beta` path segments on the control endpoints select fixed local test lanes. They deliberately do not model production authentication and must never become a production `PartnerContextResolver`. Callback ingress uses a separate fixture-only fixed route/signature adapter so tests can prove that invalid signatures and wrong-partner delivery create no trusted callback lifecycle facts.

## Mock behavior

`LocalMockPartnerServer` is a separate loopback HTTP server with a bounded executor. It provides normal JSON, success, 4xx/5xx, timeout, slow, connection failure, retry, malformed JSON, large textual JSON, generated Base64 document/image/opaque payloads, nested sensitive values, encrypted byte echo behavior, HTTP 202 acknowledgements, and delayed/retried/concurrent callbacks into the test application. No external partner endpoint is contacted.

| Scenario | Fixture behavior |
| --- | --- |
| `normal-json`, `success` | Normal synthetic DTO request and JSON success response |
| `partner-4xx`, `partner-5xx` | HTTP 422 and 503 responses with stable synthetic error codes |
| `timeout`, `slow-response`, `connection-failure`, `retry` | Deterministic transport latency/failure and two-attempt retry paths |
| `malformed-response`, `large-normal-json` | Invalid response JSON and a large but normal textual JSON document |
| `pdf-base64-5-mb`, `jpeg-base64-8-mb` | Programmatically generated exact-size binary candidates encoded as Base64 |
| `unknown-large-base64`, `base64-document-array` | Opaque Base64 under a non-obvious key and multiple encoded documents in an array |
| `nested-sensitive`, `credentials`, `otp`, `card-data`, `restricted-pii` | Nested secrets, auth/JWT/cookie/API-key values, OTP, card data, and mask-required PII |

Tests additionally execute two synthetic partners with the same application ID, bounded concurrent RestTemplate traffic, concurrent reactive WebClient traffic, and DTO -> JSON -> AES-GCM -> RestTemplate -> decrypt -> DTO behavior. The starter instruments the fixed mock paths; RestTemplate proves bounded safe JSON capture while WebClient and OkHttp prove metadata-only degradation without body re-subscription or replay.

The encryption flow uses the production `PartnerObservations` scope immediately before
serialization/encryption and `PartnerObservation.captureResponse` immediately after
decryption/deserialization. Typed `PartnerPlaintextSchema` beans expose only reviewed business
fields, and the automatic RestTemplate interceptor joins the same observation for transport
status/duration without capturing either ciphertext body. Tests prove sanitized logical request
and response visibility, two-partner isolation, key/IV/credential and large-Base64 exclusion,
publisher/hook failure containment, and successful encrypted traffic when observability is
disabled.

## Async and callback scenarios

The async control endpoint accepts these kebab-case values from `SyntheticAsyncScenario`:

| Scenario | Deterministic behavior |
| --- | --- |
| `acknowledgement-only` | Mock accepts the initiation with HTTP 202 and no callback |
| `ack-with-partner-reference` | HTTP 202 acknowledgement bridges a partner reference and external transaction ID |
| `callback-with-application-id` | Callback carries only `applicationId` as its correlation field |
| `callback-with-partner-reference-only` | Callback carries only `partnerReferenceId` |
| `callback-with-callback-reference` | Callback carries `callbackReferenceId` |
| `callback-success` | Callback processing succeeds and returns 200 |
| `callback-processing-failure` | Processing fails and returns 500 |
| `callback-retry` | First delivery returns 500; the mock retries and receives 200 |
| `duplicate-callback` | The same callback reference is delivered twice after a successful first delivery |
| `callback-out-of-order` | Logical callback sequence 2 arrives before sequence 1 |
| `callback-after-outbound-timeout` | Initiation acknowledgement times out, then a valid callback arrives |
| `unknown-partner-reference` | Authenticated callback carries an unknown synthetic partner reference |
| `wrong-partner` | Callback is authenticated for the other lane and is denied without trusted callback facts |
| `authentication-failure` | Invalid fixed signature returns 401 without reading/capturing the body as a trusted callback |
| `malformed-callback` | Authenticated malformed JSON returns 400 and records parsing failure metadata only |
| `callback-pdf-base64-5-mb` | Callback contains a generated Base64-encoded 5 MiB PDF candidate |
| `callback-image-base64` | Callback contains a generated Base64-encoded 8 MiB JPEG candidate |
| `callback-sensitive-pii` | Callback contains synthetic phone, email, account, national ID, and address values |
| `callback-credentials` | Callback body/header contains synthetic credentials and Authorization-like content |
| `accepted-then-downstream-failure` | Callback returns 202 before bounded background processing fails |
| `response-transmission-failure` | Processing succeeds and the response body write is deliberately failed |
| `cross-partner-callback-reference` | Both lanes may use the same callback reference without sharing lifecycle state |
| `high-concurrency-callbacks` | One initiation produces 32 concurrent callback deliveries through bounded executors |
| `multiple-callbacks` | One initiation produces three separately identified callbacks |

The mock initiation request, acknowledgement, and callback projection collectively exercise `applicationId`, `loanId`, `originalCorrelationId`, `partnerReferenceId`, `callbackReferenceId`, and `externalTransactionId`. `GET /fixture/async/runs/{runId}` returns only identifiers, sizes, fixed categories, statuses, and lifecycle outcomes; callback bodies and headers are never retained in that ledger.

Fixture state is deliberately bounded to 256 journeys, 256 lifecycle events per journey, 128 callback attempts and deliveries per journey, a 64-entry mock work queue, and a 16 MiB callback request cap. Eldest journey eviction and fixed-limit failure are test-control behavior, not production SDK semantics.

Run the module tests with:

```bash
./gradlew :sure-partner-observability-test-app:test
```

The module suite includes lifecycle assertions for all 24 async/callback scenarios plus enabled/disabled starter behavior, all three outbound adapters, retry attempts, async acknowledgement bridging, callback request/processing/response facts, duplicate/retry attempts, large-document omission, cross-partner denial, publisher failure, and queue saturation. These remain compatibility/integration evidence and do not replace Alloy/Loki defense-in-depth or the full-duration M9 performance profiles.
