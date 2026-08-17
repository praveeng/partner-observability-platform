# ADR 0009: Async/callback records and deterministic correlation

- Status: Accepted; schema-2 SDK model and Spring producer slice implemented
- Date: 2026-08-16
- Decision owners: SDK, query, and partner-integration architecture

## Context

An asynchronous partner journey is not one HTTP exchange. The original request receives an acknowledgement, the partner processes independently, and a callback may arrive minutes or hours later on another instance with another HTTP correlation ID. Retries, duplicate callbacks, missing identifiers, timeouts, and out-of-order delivery are normal. Treating callbacks as generic inbound logs, or joining solely on the original correlation ID, loses the business journey and can produce misleading timelines.

The starter must remain thin and must not add a synchronous correlation-store dependency to business traffic. Transaction identifiers also cannot become Loki or metric labels.

## Decision

Schema version 2 has seven first-class partner-safe record bodies: `OutboundApiRequestRecord`, `OutboundApiResponseRecord`, `AsyncAcknowledgementRecord`, `CallbackRequestRecord`, `CallbackResponseRecord`, `CallbackProcessingEventRecord`, and `PartnerBusinessEventRecord`. An asynchronous initiation uses the outbound request plus exactly one acknowledgement record; it does not also emit a generic response record for the same terminal result.

The callback timeline uses distinct immutable facts. `CALLBACK_RECEIVED` (or `CALLBACK_RETRY_RECEIVED`) and `CALLBACK_PROCESSED` are always separate. Supported stages include the required `ASYNC_REQUEST_SENT`, `ASYNC_ACK_RECEIVED`, `CALLBACK_RECEIVED`, `CALLBACK_AUTHENTICATED`, `CALLBACK_VALIDATED`, `CALLBACK_PROCESSING_STARTED`, `CALLBACK_PROCESSED`, `CALLBACK_PROCESSING_FAILED`, `CALLBACK_RESPONSE_SENT`, and `CALLBACK_RETRY_RECEIVED`, plus truthful terminal stages `ASYNC_ACK_NOT_RECEIVED` and `CALLBACK_RESPONSE_WRITE_FAILED`. A stage never claims an acknowledgement/response was received/sent when local transport observed failure.

Every record carries a configured `correlationProfileId` and the validated subset of `applicationId`, `loanId`, `originalCorrelationId`, `partnerReferenceId`, `externalTransactionId`, `callbackReferenceId`, and `requestId`. A profile links compatible async/callback APIs and declares stable, weak, and singleton identifier types. Identifiers are typed structured metadata, never labels or authorization inputs. An acknowledgement commonly forms the bridge between Samsung-owned identifiers and partner-issued identifiers. Each callback delivery receives a new `callbackAttemptId`; a validated callback/idempotency reference identifies possible retries. The SDK never deduplicates business callbacks and never hashes a raw body as a deduplication key.

Correlation is a deterministic read-time operation inside the datasource query gateway, after its credential has fixed one Loki tenant:

1. Validate the typed seed and a required time range no longer than the 16-day retained window.
2. Query exact structured-metadata equality inside fixed low-cardinality streams.
3. Treat typed identifiers as graph nodes and co-occurrence in a trusted record as an edge within one configured correlation profile; never merge profiles.
4. Expand at most three rounds, 32 distinct identifiers, and 500 records per round, with a 2 MiB response and 10-second gateway deadline. Never use an unbounded regex or cross-tenant query.
5. Do not bridge distinct stable branches solely through a weak ID. A second value for a profile-declared singleton type stops that ambiguous edge. Stop at a fixed point or bound and return `COMPLETE`, `PARTIAL_LIMIT`, `UNRESOLVED`, `WEAK_MATCH`, or `CONFLICT`; do not silently merge conflicting stable branches.
6. Select the display anchor deterministically from the connected component in this order: application, loan, external transaction, partner reference, callback reference, original correlation, request. Matching still uses all available identifiers.
7. Sort records by `occurredAt`, then `observedAt`, then `eventId`. Sequence is a tie-breaker only within one observation; concurrent or late facts are not rewritten into a false total order.

Application- and loan-level identifiers are stable anchors; external/partner/callback references are stable partner-protocol anchors. Original correlation and request IDs are weak transport anchors. A weak-only match is displayed as such and is never used for authentication, authorization, or business deduplication. An optional application adapter may persist and restore a generated journey reference in existing business state, but the starter does not own a durable correlation store and observability success is never required for callback processing.

## Security and availability consequences

- Late callbacks can be correlated while their bridge records remain within retention, without keeping an unbounded JVM map or adding a write dependency.
- Tenant fixation happens before identifier resolution, so identical identifiers in different partners never join.
- Identifier collision or malicious reuse can reduce correlation confidence but cannot select a tenant or change business processing.
- At-most-once telemetry and bounded query expansion mean a journey can be partial. The UI must show coverage/confidence rather than imply completeness.
- Correlation beyond the retained 16-day window is impossible in Loki unless an separately approved non-telemetry business source supplies a safe anchor; the platform does not extend retention silently.

## Alternatives considered

- Original HTTP correlation ID only: rejected because callbacks often use another request and arrive much later.
- An in-memory SDK correlation map: rejected because it is instance-local, unbounded over time unless lossy, and disappears on restart.
- A new synchronous database write from the starter: rejected because observability would enter the business success path.
- High-cardinality Loki labels: prohibited by the cardinality contract.
- Hash the whole callback body: rejected because raw secret/binary data must not be copied or retained and a hash is not a business identity.
- Pretend receipt and processing are one event: rejected because 202/asynchronous processing, retries, and failures require separate facts.

## Implementation and migration

The existing M2 schema-1 outbound model remains a safe implementation baseline but is incomplete for this expanded contract. Before M3 is accepted, core work must add schema-2 record types and correlation identifiers without weakening queue/payload invariants. Alloy must accept schema 2 and schema 1 during the bounded migration; dashboards query both until schema-1 expiry is documented. The query-gateway ECS task gains a stateless bounded journey-resolver process; it stores no partner data.

## Verification evidence required

Use two synthetic partners and colliding identifier values. Prove acknowledgement bridging, callbacks minutes/hours later, duplicates, retries, out-of-order events, original timeouts, unknown references, missing application IDs, wrong-partner attempts, conflict/limit statuses, deterministic ordering, and denial of cross-tenant expansion. Prove that query complexity and response size stay bounded.

## References and supersession

This ADR refines ADRs 0002, 0003, 0005, 0007, and 0008; it does not supersede their safety boundaries. Normative details are in `../telemetry-contract.md`, `../architecture.md`, `../partner-isolation.md`, and `../acceptance-criteria.md`.
