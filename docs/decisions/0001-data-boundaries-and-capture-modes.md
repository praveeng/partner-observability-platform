# ADR 0001: Data boundaries and capture modes

- Status: Accepted for M2 implementation
- Date: 2026-08-16
- Decision owners: Platform architecture and security review

## Context

The platform must provide useful partner request/response detail while never capturing credentials, secrets, card/OTP data, restricted identifiers unmasked, or binary/documents/Base64. The phrase “full payload” conflicts with those exclusions if interpreted as a verbatim copy. Arbitrary existing logs also cannot be proven safe after values have been formatted into a string.

## Decision

Use three explicit data classes: raw partner exchange data, partner-safe derived observability, and internal-only information. Only the second can implement `TelemetryRecord` or enter partner Loki.

Support `NO_PAYLOAD`, `METADATA_ONLY`, and `FULL_SANITIZED` independently per outbound request/response, async acknowledgement, callback request/response, and business-event leg. Full sanitized is a complete safe projection of registered textual/scalar paths within hard limits, not a copy of the original body. Removal and binary/document exclusion always win. Unknown content fails closed. Callback data cannot be classified as partner-safe until authenticated server context exists.

First-stage application sanitization is authoritative and occurs before queue insertion. Alloy repeats allowlisting/removal/masking/limits as defense in depth and drops on uncertainty. Internal raw SLF4J logs remain in internal logging; partner-safe log capture requires a dedicated marker/logger and structured fields passed through the same sanitizer.

## Security and availability consequences

- Raw payloads, binaries, throwables, and arbitrary logs never exist in asynchronous queues.
- A compromised/misconfigured Alloy cannot disclose data the application never emitted.
- Safe projection requires local bounded CPU work; kill switches, metadata mode, strict limits, and rate control bound it.
- Some useful unknown/oversized data is intentionally lost.

## Alternatives considered

- Capture raw then sanitize in Alloy: rejected because unsafe data crosses the queue/network and stage-two failure leaks it.
- Regex-redact rendered log/payload strings: rejected because nesting, aliases, encodings, and formatting make completeness unprovable.
- Ban all payload capture: safe but fails the approved partner detail requirement.
- Treat “full” as verbatim with a denylist: rejected as incompatible with fail-closed disclosure.

## Implementation and migration

M2 implements safe values/classifier. M3 integrations default metadata-only. M4 implements reviewed field schemas and explicit plaintext hooks. Existing services enable modes per API after inventory/security approval; a global full-payload switch is prohibited.

## Verification evidence required

Property/fuzz corpus must prove prohibited data absent at safe-tree, queue, wire, Alloy, Loki, metrics, dashboard, and diagnostics. Full-mode tests must also prove every configured safe field within limits survives.

## References and supersession

Normative details: `../payload-policy.md`, `../telemetry-contract.md`, and `../security-invariants.md`. No ADR is superseded.
