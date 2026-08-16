# Payload Policy

## Default rule

Capture metadata, not arbitrary payloads. Unknown fields, values, encodings, and object types fail closed for disclosure and are omitted. The safe representation is created before queue admission.

## Classification matrix

| Data class | Policy | Examples |
| --- | --- | --- |
| Prohibited secrets | Remove completely | Passwords, credentials, API keys, tokens, private keys, session secrets, OTPs |
| Payment card data | Remove completely | PAN/card number, CVV/CVC, track data, PIN data |
| Restricted identifiers | Mask under approved rule | Phone, email, account number, national identifier, postal/street address |
| Binary/document content | Never capture | Bytes, streams, images, documents, PDFs, archives, multipart file bodies |
| Encoded binary | Never capture | Base64-like blobs, data URIs, encoded documents/images |
| High-cardinality IDs | Structured metadata only if allowed | `applicationId`, `loanId`, `correlationId`, `requestId` |
| Explicit safe metadata | Allowlist and bound | Operation name, outcome enum, status class, duration |
| Unknown | Omit | Unrecognized field/type or failed classification |

Names are not sufficient for classification: detection must account for nested values, aliases, content type, data shape, and configured allowlists. No sanitizer may log the rejected raw value when handling an error.

## Required limits

M1 must set maximum event size, string length, collection count, object depth, field count, and Base64 detection strategy. Limits must be enforced before copying data into a queued event. Truncation is permitted only for data classes already approved for disclosure; truncation cannot make prohibited content safe.

## Encrypted integrations

Instrumentation must not decrypt traffic merely for observability. If the host application already has authorized plaintext at a defined boundary, M1 must document how only allowlisted metadata is derived without extending plaintext lifetime or scope. The decision is open in `decisions-needed.md`.

## Verification

Security tests must cover case variants, nested structures, arrays, misleading names/types, Unicode, malformed serialization, large values, Base64 variants, exception messages, fallback paths, and queue contents—not only final rendered logs.
