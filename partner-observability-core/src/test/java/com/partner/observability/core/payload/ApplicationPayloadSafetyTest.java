package com.partner.observability.core.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.partner.observability.core.policy.PayloadCaptureMode;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ApplicationPayloadSafetyTest {

    private final FailClosedPayloadSanitizer sanitizer = new FailClosedPayloadSanitizer();

    @Test
    void excludesTenMegabyteBase64FromANonObviousNestedFieldWithoutDecodingIt() {
        String tenMegabytes = "A".repeat(10 * 1024 * 1024);
        PayloadSchema schema = PayloadSchema.builder().allow("outer.payloadValue").build();

        SanitizationResult result = sanitizer.sanitize(
                PayloadInput.of(Map.of("outer", Map.of("payloadValue", tenMegabytes))),
                schema,
                PayloadCaptureMode.FULL_SANITIZED);

        assertEquals(SanitizationDisposition.OMITTED, result.disposition());
        assertEquals(PayloadStatus.BASE64, result.status());
        assertTrue(result.payload().isEmpty());
        assertEquals(BinaryKind.UNKNOWN_ENCODED, result.omittedBinary().orElseThrow().kind());
        assertTrue(result.omittedBinary().orElseThrow().sha256().isEmpty());
    }

    @Test
    void truncatesApprovedLargeTextOnlyAfterItPassesContentClassification() {
        String narrative = "Synthetic narrative with spaces. ".repeat(1024);
        PayloadSchema schema = PayloadSchema.builder()
                .allow("narrative", PayloadValueType.STRING)
                .build();

        SanitizationResult result = sanitize(Map.of("narrative", narrative), schema);
        String safe = (String) safeMap(result).get("narrative");

        assertTrue(safe.endsWith("…"));
        assertTrue(safe.getBytes(StandardCharsets.UTF_8).length <= PayloadLimits.HARD_MAX_STRING_BYTES);
        assertFalse(safe.equals(narrative));
    }

    @Test
    void excludesUrlSafePaddedAndMimeWrappedBase64Variants() {
        byte[] urlBytes = new byte[10];
        java.util.Arrays.fill(urlBytes, (byte) 0xff);
        String urlSafe = Base64.getUrlEncoder().encodeToString(urlBytes);
        String mime = Base64.getMimeEncoder(16, new byte[] {'\n'}).encodeToString(new byte[96]);
        PayloadSchema schema = PayloadSchema.builder().allow("encoded").build();

        SanitizationResult urlResult = sanitize(Map.of("encoded", urlSafe), schema);
        SanitizationResult mimeResult = sanitize(Map.of("encoded", mime), schema);

        assertEquals(PayloadStatus.BASE64, urlResult.status());
        assertEquals(PayloadStatus.BASE64, mimeResult.status());
        assertTrue(urlResult.payload().isEmpty());
        assertTrue(mimeResult.payload().isEmpty());
    }

    @Test
    void veryDeepObjectsAndHugeArraysFailClosedAtConfiguredStructuralLimits() {
        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        StringBuilder registeredPath = new StringBuilder();
        for (int depth = 0; depth < 200; depth++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cursor.put("x", child);
            cursor = child;
            if (!registeredPath.isEmpty()) {
                registeredPath.append('.');
            }
            registeredPath.append('x');
        }
        cursor.put("value", "SAFE");
        registeredPath.append(".value");
        PayloadSchema deepSchema = PayloadSchema.builder().allow(registeredPath.toString()).build();

        SanitizationResult deepResult = sanitize(deep, deepSchema);
        SanitizationResult arrayResult = sanitize(
                Map.of("values", Collections.nCopies(100_000, "SAFE")),
                PayloadSchema.builder().allow("values[]").build());
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int field = 0; field < 1_000; field++) {
            wide.put("unknown" + field, "SAFE");
        }
        SanitizationResult wideResult = sanitize(wide, PayloadSchema.builder().allow("known").build());

        assertEquals(PayloadStatus.NOT_ALLOWLISTED, deepResult.status());
        assertTrue(deepResult.payload().isEmpty());
        assertEquals(PayloadStatus.OVERSIZE, arrayResult.status());
        assertTrue(arrayResult.payload().isEmpty());
        assertEquals(PayloadStatus.OVERSIZE, wideResult.status());
        assertTrue(wideResult.payload().isEmpty());
    }

    @Test
    void nestedSensitiveAliasesAndAuthorizationVariantsOverrideExplicitAllowRules() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("aUtHoRiZaTiOn", "Bearer SYNTHETIC-NOT-A-CREDENTIAL");
        nested.put("Proxy_Authorization", "Basic SYNTHETIC-NOT-A-CREDENTIAL");
        nested.put("X-API-Key", "SYNTHETIC-NOT-A-KEY");
        nested.put("CLIENT-SECRET", "SYNTHETIC-NOT-A-SECRET");
        nested.put("eMaIl-AdDrEsS", "person@example.test");
        nested.put("Telephone_Number", "+44 7700 900123");
        nested.put("National-ID", "NAT-12345678");
        nested.put("Postal_Address", Map.of("line", "10 Synthetic Street"));
        nested.put("status", "ACCEPTED");
        PayloadSchema.Builder schema = PayloadSchema.builder();
        nested.keySet().forEach(key -> schema.allow("customer." + key));

        SanitizationResult result = sanitize(Map.of("customer", nested), schema.build());
        Map<?, ?> customer = (Map<?, ?>) safeMap(result).get("customer");

        assertEquals("ACCEPTED", customer.get("status"));
        assertEquals("p***@e***.test", customer.get("emailaddress"));
        assertEquals("******0123", customer.get("telephonenumber"));
        assertEquals("******5678", customer.get("nationalid"));
        assertEquals("[MASKED_ADDRESS]", customer.get("postaladdress"));
        assertFalse(customer.containsKey("authorization"));
        assertFalse(customer.containsKey("proxyauthorization"));
        assertFalse(customer.containsKey("xapikey"));
        assertFalse(customer.containsKey("clientsecret"));
    }

    @Test
    void removesJwtAuthorizationAndAccidentalKnownSecretShapesFromAllowedBusinessFields() {
        Map<String, Object> candidate = Map.of(
                "description", "Bearer SYNTHETIC-NOT-A-CREDENTIAL",
                "opaqueReference", "aaaaaaaa.bbbbbbbb.cccccccc",
                "externalReference", "AKIA0000000SYNTHETIC",
                "safeStatus", "APPROVED");
        PayloadSchema schema = PayloadSchema.builder()
                .allow("description")
                .allow("opaqueReference")
                .allow("externalReference")
                .allow("safeStatus")
                .build();

        SanitizationResult result = sanitize(candidate, schema);

        assertEquals(Map.of("safestatus", "APPROVED"), safeMap(result));
        assertEquals(3, result.removedValues());
    }

    @Test
    void supportsNestedPathAndFieldNamePoliciesWithoutCreatingAGlobalAllowRule() {
        PayloadSchema schema = PayloadSchema.builder()
                .allow("result.status", PayloadValueType.STRING)
                .allow("result.vendorProof", PayloadValueType.STRING)
                .allow("result.contact", PayloadValueType.STRING)
                .field("result.email", PayloadFieldPolicy.REMOVE, PayloadValueType.STRING)
                .fieldName("vendorProof", PayloadFieldPolicy.REMOVE)
                .fieldName("contact", PayloadFieldPolicy.MASK_PHONE)
                .build();

        SanitizationResult result = sanitize(
                Map.of("result", Map.of(
                        "status", "READY",
                        "vendorProof", "SYNTHETIC-NOT-A-PROOF",
                        "contact", "+1 202 555 0123",
                        "email", "person@example.test",
                        "unknown", "OMITTED")),
                schema);
        Map<?, ?> safe = (Map<?, ?>) safeMap(result).get("result");

        assertEquals(Map.of("status", "READY", "contact", "******0123"), safe);
        assertFalse(safe.containsKey("unknown"));
    }

    @Test
    void registeredDtoExtractionIsTypeAwareImmediateAndDoesNotCallToString() {
        SyntheticDto dto = new SyntheticDto(
                "APPROVED", "100.00", "person@example.test", new byte[] {1, 2, 3});
        PayloadObjectSchema<SyntheticDto> schema = PayloadObjectSchema.builder(SyntheticDto.class)
                .allowString("business.status", SyntheticDto::status)
                .allowNumber("business.amount", SyntheticDto::amount)
                .field("customer.email", PayloadFieldPolicy.MASK_EMAIL, PayloadValueType.STRING, SyntheticDto::email)
                .field("attachment", PayloadFieldPolicy.ALLOW, PayloadValueType.STRING, SyntheticDto::attachment)
                .build();

        SanitizationResult result = sanitizer.sanitizeObject(dto, schema, PayloadCaptureMode.FULL_SANITIZED);
        Map<?, ?> safe = safeMap(result);
        Map<?, ?> business = (Map<?, ?>) safe.get("business");
        Map<?, ?> customer = (Map<?, ?>) safe.get("customer");

        assertEquals(Map.of("status", "APPROVED"), business);
        assertEquals("p***@e***.test", customer.get("email"));
        assertFalse(safe.containsKey("attachment"));
        assertEquals(BinaryKind.DOCUMENT, result.omittedBinary().orElseThrow().kind());
    }

    @Test
    void metadataAndNoPayloadModesNeverInvokeDtoExtractors() {
        AtomicInteger invocations = new AtomicInteger();
        PayloadObjectSchema<SyntheticDto> schema = PayloadObjectSchema.builder(SyntheticDto.class)
                .allowString("status", dto -> {
                    invocations.incrementAndGet();
                    return dto.status();
                })
                .build();
        SyntheticDto dto = new SyntheticDto("SAFE", BigDecimal.ONE, "person@example.test", new byte[0]);

        assertEquals(PayloadStatus.NOT_REQUESTED,
                sanitizer.sanitizeObject(dto, schema, PayloadCaptureMode.METADATA_ONLY).status());
        assertEquals(PayloadStatus.NOT_REQUESTED,
                sanitizer.sanitizeObject(dto, schema, PayloadCaptureMode.NO_PAYLOAD).status());
        assertEquals(0, invocations.get());
    }

    @Test
    void optionalSha256HashesOnlySmallAlreadyMaterializedBinaryWithoutMovingBuffers() throws Exception {
        FailClosedPayloadSanitizer hashing = new FailClosedPayloadSanitizer(
                PayloadLimits.defaults(), BinaryDigestMode.SAFE_SHA_256);
        byte[] small = "SYNTHETIC-BINARY".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.wrap(small);
        int originalPosition = buffer.position();
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(small));

        SanitizationResult bytesResult = hashing.sanitize(
                PayloadInput.of(small), PayloadSchema.builder().allow("content").build(),
                PayloadCaptureMode.FULL_SANITIZED);
        SanitizationResult bufferResult = hashing.sanitize(
                PayloadInput.of(buffer), PayloadSchema.builder().allow("content").build(),
                PayloadCaptureMode.FULL_SANITIZED);
        SanitizationResult largeResult = hashing.sanitize(
                PayloadInput.of(new byte[PayloadLimits.HARD_MAX_RAW_CANDIDATE_BYTES + 1]),
                PayloadSchema.builder().allow("content").build(), PayloadCaptureMode.FULL_SANITIZED);
        SanitizationResult removedSecretResult = hashing.sanitize(
                PayloadInput.of(Map.of("password", small)),
                PayloadSchema.builder().allow("password").build(), PayloadCaptureMode.FULL_SANITIZED);

        assertEquals(expected, bytesResult.omittedBinary().orElseThrow().sha256().orElseThrow());
        assertEquals(expected, bufferResult.omittedBinary().orElseThrow().sha256().orElseThrow());
        assertEquals(originalPosition, buffer.position());
        assertTrue(largeResult.omittedBinary().orElseThrow().sha256().isEmpty());
        assertTrue(removedSecretResult.omittedBinary().isEmpty());
        assertTrue(removedSecretResult.payload().isEmpty());
    }

    @Test
    void malformedCandidatesAndExtractorFailuresReturnOnlyBoundedReasons() {
        PayloadSchema schema = PayloadSchema.builder().allow("status").build();
        SanitizationResult malformed = sanitizer.sanitize(
                PayloadInput.malformed("application/json", 41), schema, PayloadCaptureMode.FULL_SANITIZED);
        PayloadObjectSchema<SyntheticDto> throwing = PayloadObjectSchema.builder(SyntheticDto.class)
                .allowString("status", ignored -> {
                    throw new IllegalArgumentException("synthetic extractor failure");
                })
                .build();

        SanitizationResult extractorFailure = sanitizer.sanitizeObject(
                new SyntheticDto("SAFE", BigDecimal.ONE, "person@example.test", new byte[0]),
                throwing,
                PayloadCaptureMode.FULL_SANITIZED);

        assertEquals(PayloadStatus.MALFORMED, malformed.status());
        assertEquals(PayloadStatus.MALFORMED, extractorFailure.status());
        assertTrue(malformed.payload().isEmpty());
        assertTrue(extractorFailure.payload().isEmpty());
    }

    @Test
    void adversarialNumbersHugeMasksAndOverlappingDtoPathsStayBounded() {
        SanitizationResult number = sanitize(
                Map.of("amount", new BigDecimal("1E+1000000")),
                PayloadSchema.builder().allow("amount", PayloadValueType.NUMBER).build());
        SanitizationResult mask = sanitize(
                Map.of("phone", ".".repeat(10 * 1024 * 1024)),
                PayloadSchema.builder().allow("phone").build());

        assertEquals(PayloadStatus.NOT_ALLOWLISTED, number.status());
        assertTrue(number.payload().isEmpty());
        assertEquals("******", safeMap(mask).get("phone"));
        assertThrows(IllegalArgumentException.class, () -> PayloadObjectSchema.builder(SyntheticDto.class)
                .allowString("business", SyntheticDto::status)
                .allowString("business.status", SyntheticDto::status));
    }

    private SanitizationResult sanitize(Object candidate, PayloadSchema schema) {
        return sanitizer.sanitize(PayloadInput.of(candidate), schema, PayloadCaptureMode.FULL_SANITIZED);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(SanitizationResult result) {
        assertEquals(SanitizationDisposition.CAPTURED, result.disposition());
        return (Map<String, Object>) result.payload().orElseThrow().value().toJavaValue();
    }

    private static final class SyntheticDto {
        private final String status;
        private final Object amount;
        private final String email;
        private final byte[] attachment;

        private SyntheticDto(String status, Object amount, String email, byte[] attachment) {
            this.status = status;
            this.amount = amount;
            this.email = email;
            this.attachment = attachment;
        }

        String status() {
            return status;
        }

        Object amount() {
            return amount;
        }

        String email() {
            return email;
        }

        byte[] attachment() {
            return attachment;
        }

        @Override
        public String toString() {
            throw new AssertionError("sanitizer must not call DTO toString");
        }
    }
}
