# ADR 0010: Callback ingress trust and capture boundaries

- Status: Accepted for revised M1 design; implementation pending
- Date: 2026-08-16
- Decision owners: Application security and Spring integration architecture

## Context

Callbacks/webhooks are controlled by an external partner, may carry signatures or encrypted bodies, and can be retried or delivered before downstream work completes. Payload capture is useful before business processing, but a body-derived partner claim is untrusted until the host service authenticates it. Servlet/reactive interception must not consume a stream, disturb backpressure, invalidate signature verification, or emit an unauthenticated body to a partner tenant.

## Decision

Callbacks are a first-class configured interaction kind, not generic inbound HTTP logging. A callback route is enabled only when its manifest entry names a server-owned route template, API ID, supported Spring stack, authentication/trust adapter, payload policy for each leg, and identifier extractors.

The starter does not implement partner authentication or decryption. It consumes only the host service's authenticated principal/verified-signature result through a `CallbackPartnerContextResolver`. Until that resolver returns one `AUTHENTICATED_SERVER` context consistent with the configured route and partner allowlist, no partner `TelemetryRecord` is constructed. Failed/unknown/conflicting authentication produces only bounded internal security counters/evidence without request headers, identifiers, body, exception text, or a fallback tenant. A server-owned ingress timestamp may be retained locally and becomes `occurredAt` only after trust succeeds.

For Spring MVC, a configured filter records transport timing, a handler interceptor resolves the server-owned route, `RequestBodyAdvice` sanitizes a registered DTO immediately after successful decoding and before controller invocation, and `ResponseBodyAdvice` sanitizes a registered response after business processing and before serialization. A bounded tee wrapper is allowed only for supported textual bodies and may inspect bytes only as the application consumes them. Parsing failures produce metadata-only trusted records after authentication. The final filter records committed status and bounded transport outcome.

For WebFlux, a post-authentication `WebFilter` writes the immutable context to Reactor Context, decorates request/response `DataBuffer` publishers only for explicitly enabled textual routes, copies at most the raw candidate cap, preserves demand/cancellation/release semantics, and uses a single terminal guard. Route matching comes from the server-owned manifest, not a raw user path. Typed/functional handlers and encrypted/signature filters use an explicit adapter when automatic decoration cannot prove ordering or plaintext authority.

The explicit callback observation API supplies the semantic facts automatic HTTP interception cannot know: authenticated/validated, retry classification from the business idempotency mechanism, processing started, processed/failed, and accepted-before-completion. Request plaintext is captured immediately after existing authentication/decryption and before business processing. Response plaintext is captured after processing and before existing encryption/serialization. Automatic and explicit integrations share `interactionId` and `callbackAttemptId` and suppress duplicate payload capture.

`CALLBACK_RESPONSE_SENT` means the local response write completed, not guaranteed remote receipt. A successful business result followed by a write failure is represented by `CALLBACK_PROCESSED` with success and a separate `CALLBACK_RESPONSE_WRITE_FAILED` response record. A `202 Accepted` response may precede background processing events; timestamps preserve that fact. Context snapshots for background work are immutable, explicitly wrapped, and never rely on `InheritableThreadLocal`.

## Security and availability consequences

- Unauthenticated or wrong-partner callback data cannot enter a partner tenant.
- Automatic capture cannot break signature verification, body consumption, or reactive behavior; unsupported ordering reduces to metadata-only or off.
- The platform observes host authentication outcomes but cannot make authentication or business-idempotency decisions.
- Semantic lifecycle accuracy requires a small explicit API at application-owned boundaries; automatic interception alone is intentionally insufficient.
- Every local candidate, safe tree, response wrapper, and context snapshot remains bounded and observability errors never affect callback status or processing.

## Alternatives considered

- Derive partner from route/body/header: rejected because those are attacker-controlled without authenticated server state.
- Record unauthenticated callbacks in the expected partner tenant: rejected because route expectation is not proof of identity and could expose attacker input.
- Globally buffer callback bodies: rejected for memory, streaming, signature, and reactive semantics.
- Let the starter validate every partner signature format: rejected because authentication is business/security configuration and would expand SDK authority.
- Infer `CALLBACK_PROCESSED` from HTTP 2xx: rejected because callbacks may return 202 before processing or write failure may follow successful processing.

## Implementation and migration

M3 adds conditional MVC/WebFlux transport and context integrations with metadata-only defaults. M4 connects registered safe extractors and explicit callback observations. Onboarding remains disabled until the service owner specifies filter/security/decryption ordering and tests it in DEV mocks and STAGE. Unsupported callback stacks use only the explicit API.

## Verification evidence required

Tests cover authentication/signature failure, wrong partner, malformed body, typed capture before controller work, response capture after processing, duplicate/retry classification, accepted-before-complete ordering, business success plus write failure, MVC async dispatch, WebFlux cancellation/backpressure/DataBuffer release, context restoration, and prohibited data absence before queue admission.

## References and supersession

This ADR refines ADR 0003 and the ingress portions of ADR 0004. It does not authorize partner authentication from telemetry input. Normative details are in `../architecture.md`, `../security-invariants.md`, `../telemetry-contract.md`, and `../payload-policy.md`.
