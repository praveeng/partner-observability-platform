# Telemetry Contract

## Status

M0 contract outline. Field names and limits are provisional until M1 ADR approval; disclosure and cardinality invariants are already mandatory.

## Safe event envelope

A future telemetry event should contain only an allowlisted schema such as:

- event schema version, type, outcome, and timestamp;
- stable low-cardinality service/environment attributes;
- server-derived partner routing identity handled as trusted routing context;
- bounded, sanitized structured metadata;
- numeric timing/size observations;
- high-cardinality transaction identifiers only in approved structured metadata.

The telemetry object admitted to the queue must already be safe for disclosure. Raw request/response objects, streams, byte arrays, throwable object graphs, and arbitrary `toString()` output are not valid queue values.

## Processing order

1. Establish trusted server-side context, including partner identity.
2. Enforce supported type, maximum size/depth/count, and binary/Base64 exclusion.
3. Allowlist fields and completely remove secret/card/OTP/credential classes.
4. Mask approved restricted identifiers.
5. Construct a bounded immutable event.
6. Attempt non-blocking admission to a bounded queue.
7. On saturation, drop and update a safe metric; never block business traffic.
8. Export asynchronously, containing every failure.

## Loki indexing contract

Normal labels must be low-cardinality and bounded. `applicationId`, `loanId`, `correlationId`, and `requestId` are never normal labels. When approved for capture, they belong in Loki structured metadata or an equivalent non-indexed mechanism and remain subject to masking/removal rules.

Partner identity controls the Loki tenant and must not be treated as a user-selectable label-based isolation mechanism.

## Compatibility and evolution

Events require a schema version. Additive safe fields may be introduced only with cardinality and disclosure review. Removal, semantic changes, and routing changes require an ADR and migration plan. Invalid/unknown fields are discarded, not passed through.
