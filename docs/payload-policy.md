# Payload Policy

## Normative rule

Only a bounded, explicitly allowlisted projection may become partner-safe derived observability. Unknown keys, types, values, encodings, aliases, and classifier failures fail closed. Raw partner exchange objects are never queued, retained for later sanitization, logged as fallback, or sent to Alloy.

## Capture policy

Capture mode is configured per market/service/partner/API/interaction-kind/leg and defaults to `METADATA_ONLY`. Legs are outbound request, outbound response, async acknowledgement, callback request, callback response, and business event. `FULL_SANITIZED` requires a reviewed field schema for that exact leg. `NO_PAYLOAD` creates no partner record for the leg. A runtime, trust, integration capability, or content policy may reduce but never increase the configured mode.

“Full” is intentionally constrained: all allowed scalar/text fields in the configured schema are preserved after masking, provided the candidate and safe result fit hard bounds. It does not promise verbatim bytes, order/whitespace, unknown fields, attachments, or an entire oversized document.

## Classification matrix

| Class | Action | Examples |
| --- | --- | --- |
| Credentials/secrets | Remove field/value completely | Password/passcode, Authorization, Bearer, Basic, OAuth/JWT, refresh/access token, session ID, client secret, API key, signing/private key, encryption key, secret answer |
| Transport security secrets | Remove field/value completely | TLS/client private key, keystore/trust-store bytes or password, key-manager material, session ticket, certificate private material, URI credentials |
| Cookies | Remove completely | `Cookie`, `Set-Cookie`, cookie values, CSRF/session tokens |
| OTP | Remove completely | OTP, one-time code/password, verification code, PIN used as authentication |
| Card data | Remove completely | PAN/card number, CVV/CVC/CID, PIN/PIN block, track data, magnetic stripe, expiry when associated with a card |
| Phone | Mask | Preserve at most final four digits: `******1234`; extension removed |
| Email | Mask | First safe character plus masked local/domain: `a***@e***.com`; invalid forms become `[MASKED_EMAIL]` |
| Bank account | Mask | Preserve final four alphanumerics: `********1234` |
| National identifier | Mask | Preserve final four alphanumerics: `******6789` |
| Address | Mask | Replace whole value/object with `[MASKED_ADDRESS]`; no partial street/postcode retention |
| Transaction IDs | Allow after typed validator | `applicationId`, `loanId`, `originalCorrelationId`, `partnerReferenceId`, `externalTransactionId`, `callbackReferenceId`, `requestId`; structured metadata, never label/auth input |
| Approved business fields | Allow | Amount, currency, tenure, SKU/product, configured status/error codes, operation/journey metadata |
| Transport security metadata | Allow only fixed enums | `TLS`, `ALB_TLS`, and the bounded TLS outcome/failure classes from `telemetry-contract.md`; never certificate/peer/exception details |
| Binary/documents | Exclude entire value/body | `byte[]`, ByteBuffer, stream, multipart file, PDF, image, signature, archive, audio/video, protobuf/octet-stream |
| Base64/encoded binary | Exclude entire value/body | Data URI, PEM, canonical padded Base64, long unpadded encoded blob |
| Unknown | Omit | Unregistered key/type/content type or ambiguous opaque value |

Removal wins over masking and allowlisting. For example, a field configured as an identifier is still removed when its name/value matches a token or card detector. An allowed amount is not captured from an unknown object path; its schema path and numeric type must be registered.

## Key and value classification

The first-stage sanitizer combines:

1. Canonical key normalization: Unicode NFKC, ASCII lower-case where possible, removal of `_`, `-`, `.`, and whitespace for alias comparison; original keys are never logged on failure.
2. A non-overridable removal alias set covering authorization/auth, credential, password/passcode, secret, token/JWT, cookie/session, API key, private/signing/encryption/client/TLS key, key manager, keystore/trust-store password or bytes, URI credential, OTP/verification/PIN, and card/CVV/track variants.
3. Non-overridable masking aliases for phone/mobile, email, account/IBAN, national/government/tax identifier, and address variants.
4. Value detectors for Bearer/Basic credentials, JWT shape, PEM/private-key/certificate-block material, key/trust-store material, data URIs, canonical Base64, and payment-card candidates passing the Luhn check.
5. A per-API path allowlist and expected type. Non-allowlisted paths are omitted even when their names appear harmless.

Configuration can add removal/masking field-name aliases or remove/mask a reviewed nested path. A field-name rule cannot create a global allow rule, expected DTO types can only narrow capture, and no configuration can downgrade built-in removal/masking rules.

## Binary and Base64 exclusion before queue admission

The candidate is rejected or its field omitted before a safe event is constructed when any condition is true:

- Java type is bytes, buffers, streams/readers, file/resource, multipart, image/document type, publisher of bytes, or an unsupported object requiring arbitrary `toString()`/serialization.
- Content type is `application/octet-stream`, PDF, multipart, image, audio, video, archive/compressed, protobuf, font, or another non-allowlisted media type.
- Leading bytes match common PDF/image/archive/document signatures, contain NUL, invalid UTF-8, or an excessive control-character ratio.
- A string starts with a data-URI/PEM marker or is canonical padded Base64 of 16+ characters.
- An unpadded 32+ character string uses only a Base64 alphabet and successfully decodes; it is omitted even from an otherwise allowed identifier field.
- The candidate is a document/signature field regardless of apparent text encoding.

Detection inspects at most the raw candidate limit and never decodes a large string into another array. Ambiguity results in omission. Metadata may record `payloadStatus=BASE64` or `BINARY`; it never records the offending name/value.

When policy permits a per-field omission marker, its exact representation is `{"omitted":true,"category":"BINARY|DOCUMENT|BASE64","declaredSizeBytes":n?,"sha256":"lowercase-hex"?}` as an `OmittedBinaryMetadata` value outside the safe payload tree. `declaredSizeBytes` is included only when already known without reading. The marker has no filename, MIME parameter, source path, field value, prefix, sample, or decoded length. Aggregate/body-level omission uses only the envelope `payloadStatus` unless there is exactly one policy-approved binary field.

Optional SHA-256 omission metadata is disabled by default. When explicitly enabled, it is computed only over an already-materialized `byte[]` or a read-only view of a `ByteBuffer` no larger than the raw candidate limit. It is skipped for Base64/text, streams, encrypted values, oversized inputs, removed secret fields, and aggregate metadata covering multiple binary candidates. Hashing never makes a candidate capturable and never permits a source reference or decoded copy to enter a queue.

## Hard limits

| Limit | Value | Behavior when exceeded |
| --- | ---: | --- |
| Raw candidate body | 64 KiB | Omit entire payload; do not capture a prefix |
| Safe payload per event | 32 KiB UTF-8 | Omit payload and emit metadata with `OVERSIZE` |
| Serialized envelope | 64 KiB UTF-8 | Drop event before queue |
| String | 2,048 UTF-8 bytes | Truncate only an already-allowed safe string with `…`; prohibited values are removed, not truncated |
| Number | 128 digits; decimal scale -128 to 128 | Omit values whose canonical decimal form could cause unreasonable allocation |
| Object depth | 8 | Omit deeper subtree |
| Total fields/nodes | 128 | Omit payload as `OVERSIZE` to avoid a misleading partial object |
| Array elements | 64 | Omit array as `OVERSIZE`; no first-N capture |
| Headers | 32 entries, 512 bytes/value | Only allowlisted names; otherwise omit payload/header set |
| Query fields | 32 entries, 512 bytes/value | Only allowlisted names; raw query string forbidden |
| Structured metadata | 32 entries, 8 KiB | Drop optional metadata in fixed priority; required overflow drops event |

Limits are computed while streaming/traversing with counters; the sanitizer does not first materialize an unbounded tree. Truncation is allowed only for individually approved non-identifier display text. IDs are accepted whole or omitted.

## Header and query policy

Authorization, Proxy-Authorization, Cookie, Set-Cookie, API-key variants, signature headers, and vendor token headers are always removed. Safe defaults permit only normalized `Content-Type`, `Accept`, and configured non-sensitive protocol/version headers. User-Agent is reduced to a configured client family/version, not retained verbatim.

Query values are absent in metadata-only mode. Full mode accepts only registered parameter names and safe scalar types; URL/path/query strings are never parsed opportunistically. Route templates replace raw paths.

## First-stage application sanitization

The application stage is authoritative and runs before queue admission. It performs type/content/size rejection, path allowlisting, removal, masking, value detection, output bounding, canonical serialization, and final self-scan for built-in secret/binary patterns. Any exception discards payload/event and increments only a reason enum. It never calls arbitrary `toString()`, reflects into unregistered classes, logs raw input, or retains a source reference.

For domain objects, a registered extractor selects named scalar paths with explicit expected types into a bounded projection and immediately invokes the same sanitizer. Payload extractors are not invoked in `METADATA_ONLY` or `NO_PAYLOAD`; separately registered typed transaction-identifier extractors may run in metadata-only mode and remain subject to removal/value/length validators. Extractor failure rejects the projection without an input-bearing diagnostic. General-purpose whole-object Jackson serialization is not a permitted capture mechanism. Pre-parsed JSON maps/lists use normalized dot paths plus `[]` array-element paths; future raw JSON parsers must enforce depth/token/size constraints before applying the same schema.

## Second-stage Alloy sanitization

Alloy is defense in depth, not a substitute for application safety. For each fixed schema-version pipeline it:

- parses JSON/OTLP attributes and drops malformed or unknown versions;
- keeps only the documented label/metadata/line field allowlists;
- deletes routing, credentials, cookies, key/token/OTP/card aliases recursively where supported;
- replaces known restricted identifier aliases with the same masking constants/patterns;
- removes strings matching Bearer/JWT/PEM/data-URI/card/Base64 safety patterns;
- enforces line and structured-metadata limits;
- drops the entire record if required validation or tenant mapping fails;
- emits internal-only counters by bounded reason and never logs the rejected record.

Alloy never forwards an unparsed original line on processor error. Security tests independently inspect the application queue and Loki, proving that stage two is not required for baseline safety.

## Encryption boundaries

The platform never decrypts for telemetry. Ciphertext is metadata-only because it is opaque/Base64/binary. Pre-encryption and post-decryption capture is permitted only where the host application already holds authorized plaintext, through the explicit scoped APIs described in `architecture.md`. Callback request capture occurs after existing authentication/decryption and before business processing; response capture occurs after processing and before existing encryption/serialization. The API sanitizes immediately and does not extend plaintext lifetime. Keys, IVs/nonces, ciphertext, algorithms tied to secrets, signature material, and crypto exceptions are removed.

No unauthenticated callback body is classified as partner-safe. If signature verification needs the body, the host security component owns its bounded handling and the starter observes only the verified result and later authorized plaintext. An expected route or partner-looking identifier is not enough to choose a tenant or payload policy.

## TLS and certificate boundaries

TLS configuration and certificate handling are not payload capture sources. The starter never reads an SSL session, trust store, key store, key/trust manager, certificate chain, or client key to enrich a payload. Private keys, client keys, key/trust-store bytes/passwords/paths, URI credentials, session/signature material, and PEM content are removed completely.

Certificate subject/issuer/SAN/serial/fingerprint/chain, peer URL/host/address, negotiated-session debug dumps, and TLS exception messages/stacks are internal-only and omitted from partner payloads. The only allowed partner-safe projection is the exact bounded `transportSecurity`/`transportFailureClass` enum contract plus already-approved operational metadata. Classification from an exception uses known types only and never its message. A TLS classifier failure omits the specific class; it does not serialize a throwable or fallback string.

## Logging and diagnostics

Sanitizer diagnostics may contain service/API configured IDs, policy version, data-class enum, and reason enum. They may not contain raw keys, paths supplied by a partner, values, payload snippets, exception messages that embed inputs, or serialized source objects. Test sentinels must be synthetic and assertion output must avoid echoing the sentinel.

## Required security corpus

Tests cover case/separator/Unicode aliases, nested maps/lists, duplicate JSON keys, type confusion, cyclic objects, malformed UTF-8/JSON, compression/archive signatures, MIME mismatch, NUL/control data, padded/unpadded/URL-safe Base64, data URIs, PEM/JWT/Bearer, Luhn-valid cards, secrets in exceptions, very deep/wide/large inputs, reactive chunks, cancellation, and both sanitization stages. The mandatory corpus includes a 10 MB Base64 document, nested and non-obvious Base64, normal large non-Base64 text, malformed content, very deep JSON, huge arrays, nested/case-varied sensitive fields, Authorization variants, JWT-like values, accidental secret-like values, and binary callback bodies. Assertions examine pre-queue records and retained references/serialized bytes, wire batches, Alloy rejected paths, Loki results, metrics, and internal diagnostics to prove binary content was not copied into the asynchronous queue.
