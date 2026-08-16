---
name: payload-safety
description: Verify fail-closed telemetry payload classification, removal, masking, binary exclusion, limits, encrypted-flow hooks, and defense in depth. Use for sanitizer changes, capture modes, fixtures, payload schemas, interceptors, Alloy transforms, or any feature that could disclose partner data.
---

# Payload Safety

Treat disclosure safety as a pre-queue security boundary. Review only unless implementation is explicitly requested.

## Load authoritative requirements

Read `AGENTS.md`, `.agent-state/status.json`, `docs/payload-policy.md`, `docs/telemetry-contract.md`, `docs/security-invariants.md`, `docs/threat-model.md`, `docs/acceptance-criteria.md`, ADR 0001, ADR 0002, and ADR 0003. Derive current masks, size limits, allowed fields, capture modes, and fail-closed behavior directly from those sources.

## Build the mandatory corpus

Use unique synthetic sentinels that cannot be mistaken for real data. Cover every item below in top-level fields, headers where relevant, nested objects, and arrays where relevant:

- removal: `Authorization`, bearer JWT, cookies, API keys, passwords, client secrets, OTP, and card information;
- masking: phone, email, bank account, national identifiers, and address;
- exclusion: PDF Base64, JPEG Base64, PNG Base64, nested Base64, unknown very large strings, and arrays of documents;
- parser and mode boundaries: malformed JSON, encrypted payloads, and oversized payloads.

For removed data, assert the key and value are absent, not replaced with a revealing hash or partial. For masked data, assert the repository-defined mask and assert the original sentinel is absent. For binary/document/Base64 data, assert exclusion occurs before queue insertion. For malformed, encrypted, unknown, or oversized content, assert metadata-only or omission according to the authoritative policy; never emit raw fallback text or ciphertext.

## Verify every boundary

1. Exercise full-sanitized, metadata-only, and no-payload modes.
2. Exercise explicit pre-encryption and post-decryption APIs without logging ciphertext or retaining caller buffers.
3. Capture evidence at sanitizer output, queue insertion, encoded event, Alloy output, and Loki query result. Also inspect SDK self-diagnostics and metric labels for sentinel leakage.
4. Prove first-stage application sanitization is sufficient by itself. Then prove Alloy independently removes a deliberately injected unsafe canary.
5. Prove all structural and byte limits from `docs/payload-policy.md`, including nesting, node/array counts, per-string, header/query, metadata, payload, envelope, and decompressed-size limits.
6. Prove sanitizer/parser/detector exceptions are contained and do not alter business behavior.

## Commands

Run the repository-provided suites and focused tests when present:

```bash
git diff --check
rg -n -i "authorization|jwt|cookie|api.?key|password|client.?secret|otp|card|phone|email|bank.?account|national|address|base64|pdf|jpeg|png|malformed|encrypt|oversiz" partner-observability-* alloy test
./scripts/test-security.sh
./scripts/test.sh
./scripts/verify-all.sh
```

Use `rg` only to locate evidence; do not print fixture secret values in reports or shell history. Missing executable coverage, a non-zero check, or `NOT IMPLEMENTED` is `FAIL` for a claimed payload-safety milestone.

## PASS/FAIL criteria

`PASS` requires every mandatory corpus case to have an automated assertion at the pre-queue boundary and at least one end-to-end assertion after Alloy/Loki, with no raw sentinel at any inspected boundary. It also requires all current limits and all three capture modes to be tested, exception containment to pass, and second-stage defense to work independently.

`FAIL` on any raw removed value, reversibly transformed secret, insufficient masking, binary/document content reaching the queue, unsafe parser fallback, ciphertext capture, unbounded decoding, missing corpus case, missing boundary evidence, or a test that merely checks status without checking absence.

Never weaken detectors, masks, fixtures, assertions, size/load limits, or test scope merely to obtain `PASS`. False positives or integration incompatibilities must be fixed safely or recorded as failures; unknown unsafe content remains fail-closed.

## Record valid findings

Set `.agent-state/status.json` to `VERIFYING` while testing. On pass, use `READY_FOR_REVIEW`; on failure, use `IN_PROGRESS`. Summarize tested boundaries in `summary` and list precise gaps in `nextActions`; use `blockers` only for genuine blockers under the constitution. Put unresolved classification decisions in `docs/decisions-needed.md` and milestone impacts in `PLANS.md`. Use only the existing status schema and never mark the whole product `COMPLETE` for this scoped gate.
