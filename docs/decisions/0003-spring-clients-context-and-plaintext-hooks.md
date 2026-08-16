# ADR 0003: Spring clients, context propagation, and plaintext hooks

- Status: Accepted for M3/M4 implementation; callback ingress refined by ADR 0010
- Date: 2026-08-16
- Decision owners: SDK and application integration architecture

## Context

Services use RestTemplate, WebClient, OkHttp, and inbound MVC/WebFlux callbacks across servlet, executor, and Reactor flows. Bodies may be streaming, one-shot, reactive, encrypted, signed, binary, or never consumed. ThreadLocal/MDC propagation can leak tenant context on pooled threads. Automatic interceptors may not see authorized plaintext or semantic callback completion.

## Decision

Provide conditional RestTemplate, WebClient, and OkHttp integrations. All capture business I/O exactly once, emit metadata by default, tee supported response bytes only as the application consumes them, and never eagerly buffer unlimited bodies. RestTemplate wraps response streams; WebClient decorates DataBuffer publishers without changing demand; OkHttp never invokes `RequestBody.writeTo` for observation. Streaming/multipart/duplex/unknown bodies are metadata-only.

Use immutable `PartnerContext`. Servlet scopes use a library ThreadLocal with `finally` restoration; configured executors use an opt-in `TaskDecorator`; Reactor Context is authoritative for reactive work and MDC is bridged per signal with restoration. `InheritableThreadLocal` and singleton mutable context are forbidden. Custom async work uses explicit snapshot wrappers.

Provide a scoped `PartnerObservation` API for pre-encryption/post-decryption points where business code already has plaintext. It immediately builds a safe projection and never decrypts, retains domain objects, accepts bytes/streams, or propagates errors. Automatic interceptors reuse its interaction ID and avoid duplicate payload capture.

ADR 0010 adds configured post-authentication MVC/WebFlux callback transport interception and an explicit callback observation API for authenticated/validated/processing/response facts. Callback route/body/header claims never establish partner context.

## Security and availability consequences

- Interceptors preserve body/backpressure/exception behavior and contain all observation failures.
- Full reactive/body capture is opt-in because decoration adds complexity and bounded local CPU/memory.
- Explicit hooks require application changes but avoid unsafe reflection/decryption and precisely locate plaintext authority.
- Context restoration and immutable per-event routing reduce cross-partner leakage.

## Alternatives considered

- Global body buffering: rejected for memory/latency/streaming semantics.
- Re-serialize OkHttp request bodies: rejected for one-shot/duplex/side-effect risk.
- Global Reactor hook plus plain ThreadLocal: rejected as difficult to scope and prone to leakage.
- SDK decryption: prohibited.
- Capture ciphertext: rejected as binary/Base64 and operationally useless.

## Implementation and migration

M3 implements metadata interceptors/context and opt-in body teeing. M4 adds safe extractors/explicit hooks. Existing services inventory interceptor/encryption ordering before enablement. Missing optional libraries do not prevent Spring context startup.

## Verification evidence required

Byte-for-byte and behavior contract tests for status/body/errors/cancel/backpressure/one-shot/duplex; randomized concurrent context tests; DataBuffer leak checks; ciphertext/key sentinels; exactly-once transport and terminal-event assertions.

## References and supersession

Normative details: `../architecture.md`, `../telemetry-contract.md`, `../payload-policy.md`, and ADR 0010. No ADR is superseded.
