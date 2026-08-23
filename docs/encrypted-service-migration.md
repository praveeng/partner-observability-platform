# Encrypted service telemetry migration

## When this integration is needed

Standard services that send ordinary JSON through an instrumented HTTP client make no
code changes. The automatic RestTemplate, WebClient, or OkHttp integration remains the
normal path.

Use the explicit hook only when business code serializes and encrypts a logical DTO
before the instrumented client can see it, or decrypts a response after the client has
returned ciphertext. The hook expresses a logical partner API exchange; it has no Loki,
Alloy, Grafana, tenant-header, encryption, or exporter API.

The feature is off by default. Enabling it does not select any API or field by itself.

## Minimum migration

### 1. Enable explicit observations and configure the existing outbound API

The API name, route, method, and partner are server-controlled configuration. Business
code never supplies a partner identity or tenant route.

The required HTTPS `origin` binds automatic transport joining to the intended partner
host. Explicit logical observation by API name does not authorize a different transport
origin; the host service remains responsible for calling only its reviewed endpoint.

```yaml
partner-observability:
  enabled: true
  events-enabled: true
  payloads-enabled: true
  explicit-observations-enabled: true
  outbound:
    - name: PARTNER_ALPHA_ENCRYPTED
      origin: https://partner-alpha.example
      path: /partner/encrypted
      method: POST
      partner: partner-alpha
      correlation-profile: ENCRYPTED_SUBMISSION
      capture-mode: FULL_SANITIZED
      safe-fields: [amount, product, fixtureClassification]
```

`METADATA_ONLY` is supported, but contains no logical payload values.
`NO_PAYLOAD`, disabled events, disabled explicit observations, and an unknown API name
produce an inert scope.

### 2. Register typed, reflection-free schemas

Register one request schema and one response schema. A schema can only narrow the
configured `safe-fields` list; it cannot expand it.

```java
@Bean
PartnerPlaintextSchema<PartnerRequest> encryptedRequestSchema() {
    return PartnerPlaintextSchema
            .request("PARTNER_ALPHA_ENCRYPTED", PartnerRequest.class)
            .allowNumber("amount", PartnerRequest::amount)
            .allowString("product", PartnerRequest::product)
            .build();
}

@Bean
PartnerPlaintextSchema<PartnerResponse> encryptedResponseSchema() {
    return PartnerPlaintextSchema
            .response("PARTNER_ALPHA_ENCRYPTED", PartnerResponse.class)
            .allowString("decision", PartnerResponse::decision)
            .build();
}
```

Do not add encryption keys, IVs, credentials, algorithms, cipher parameters, or
ciphertext to `safe-fields`. The smallest schema simply does not extract them. If a
legacy DTO requires defense-in-depth verification of one of those fields, the typed
schema may extract it with `PayloadFieldPolicy.REMOVE`; removal happens during
synchronous projection before queue admission, and a remove-only path does not widen
the configured disclosure allowlist. Binary arrays, buffers, streams, throwables, keys,
and cryptographic parameter objects are rejected as schema source types. Base64,
binary/document-shaped field values, unsupported types, oversize values, and unknown
fields remain subject to the normal fail-closed payload policy.

### 3. Add two capture calls around existing cryptography

```java
try (PartnerObservation observation =
        partnerObservations.begin("PARTNER_ALPHA_ENCRYPTED")) {
    observation.captureRequest(requestDto);       // immediately before serialization/encryption

    byte[] ciphertext = encrypt(json.write(requestDto));
    byte[] encryptedResponse = restTemplate.postForObject(uri, ciphertext, byte[].class);
    PartnerResponse responseDto = json.read(decrypt(encryptedResponse));

    observation.captureResponse(responseDto);     // immediately after successful decryption
    return responseDto;
}
```

For RestTemplate and other supported automatic clients, the active scope joins the
existing interceptor. The interceptor supplies transport status and duration but
discards request and response wire bodies for that exchange. It never duplicates the
ciphertext into telemetry. Request and response use one interaction identifier and the
same configured trusted partner context.

For an equivalent transport with no automatic integration, call
`observation.succeed(httpStatus)` after transport success or
`observation.failed()` after transport failure. These methods express only bounded
business telemetry semantics and accept no exception, key, IV, credential, or
cryptographic implementation detail.

## Safety and failure behavior

- DTO extraction and sanitization finish synchronously at each capture call. Only the
  bounded sanitized projection can reach the shared bounded asynchronous dispatcher.
- No plaintext DTO, ciphertext, key, IV, credential, or Throwable is retained by the
  observation scope.
- Duplicate or missing schemas, unknown APIs, schema/config allowlist disagreement,
  extractor failures, and sanitizer rejection fail closed for disclosure.
- Schema discovery is capped at three logical legs for each of the 64 configured APIs;
  excess configuration disables plaintext projection rather than creating an unbounded registry.
- All hook methods and scope close are fail-open for business processing.
- Queue saturation and publisher/backend failure drop telemetry and never block the
  encryption or HTTP transaction.
- Setting `partner-observability.enabled=false` supplies the same inert
  `PartnerObservations` bean, so encrypted traffic needs no conditional business code.
