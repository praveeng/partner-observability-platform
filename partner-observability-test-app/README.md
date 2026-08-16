# Synthetic test application

Java 17 / Spring Boot 2.7 compatibility fixture for SDK integration, security, failure-mode, concurrency, and performance verification. It is not a production example. The application and its dedicated mock partner server bind to loopback and use generated synthetic values only.

## Fixture control plane

Run the application locally and trigger predefined outbound scenarios:

```text
POST /fixture/rest/{alpha|beta}/{scenario}
POST /fixture/webclient/{alpha|beta}/{scenario}
POST /fixture/okhttp/{alpha|beta}/{scenario}
POST /fixture/encrypted-rest/{alpha|beta}
```

`scenario` is a kebab-case or enum-form value from `SyntheticScenario`. Responses are bounded summaries containing status, byte length, and digest; large or sensitive fixture bodies are not returned by the control plane.

The `alpha` and `beta` path segments select fixed local test lanes. They deliberately do not model authentication and must never become a production `PartnerContextResolver`. Future SDK integration must install partner context from a server-trusted fixture adapter.

## Mock behavior

`LocalMockPartnerServer` is a separate loopback HTTP server with a bounded executor. It provides normal JSON, success, 4xx/5xx, timeout, slow, connection failure, retry, malformed JSON, large textual JSON, generated Base64 document/image/opaque payloads, nested sensitive values, and encrypted byte echo behavior. No external partner endpoint is contacted.

| Scenario | Fixture behavior |
| --- | --- |
| `normal-json`, `success` | Normal synthetic DTO request and JSON success response |
| `partner-4xx`, `partner-5xx` | HTTP 422 and 503 responses with stable synthetic error codes |
| `timeout`, `slow-response`, `connection-failure`, `retry` | Deterministic transport latency/failure and two-attempt retry paths |
| `malformed-response`, `large-normal-json` | Invalid response JSON and a large but normal textual JSON document |
| `pdf-base64-5-mb`, `jpeg-base64-8-mb` | Programmatically generated exact-size binary candidates encoded as Base64 |
| `unknown-large-base64`, `base64-document-array` | Opaque Base64 under a non-obvious key and multiple encoded documents in an array |
| `nested-sensitive`, `credentials`, `otp`, `card-data`, `restricted-pii` | Nested secrets, auth/JWT/cookie/API-key values, OTP, card data, and mask-required PII |

Tests additionally execute two synthetic partners with the same application ID, bounded concurrent RestTemplate traffic, concurrent reactive WebClient traffic, and DTO -> JSON -> AES-GCM -> RestTemplate -> decrypt -> DTO behavior.

The encryption flow has an intentionally fixture-local `FixturePlaintextObservationPort`. It marks the exact pre-encryption and post-decryption seams that a future production SDK observation API must integrate with; its default implementation is a no-op and no production SDK behavior is implemented here.

Run the module tests with:

```bash
./gradlew :partner-observability-test-app:test
```
