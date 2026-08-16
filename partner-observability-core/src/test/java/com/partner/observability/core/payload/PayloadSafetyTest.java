package com.partner.observability.core.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.partner.observability.core.policy.PayloadCaptureMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayloadSafetyTest {

    private final FailClosedPayloadSanitizer sanitizer = new FailClosedPayloadSanitizer();

    @Test
    void removesEveryCredentialOtpAndCardClassEvenWhenExplicitlyAllowlisted() {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("Authorization", "Bearer top-secret");
        candidate.put("jwt", "aaaaaaaa.bbbbbbbb.cccccccc");
        candidate.put("cookie", "SESSION=secret");
        candidate.put("api_key", "key-secret");
        candidate.put("password", "password-secret");
        candidate.put("client_secret", "client-secret");
        candidate.put("otp", "123456");
        candidate.put("card_number", "4111111111111111");
        candidate.put("harmless", "4111111111111111");
        candidate.put("status", "ACCEPTED");
        PayloadSchema.Builder schema = PayloadSchema.builder();
        candidate.keySet().forEach(schema::allow);

        SanitizationResult result = sanitize(candidate, schema.build());
        Map<?, ?> safe = safeMap(result);

        assertEquals("ACCEPTED", safe.get("status"));
        assertFalse(safe.containsKey("authorization"));
        assertFalse(safe.containsKey("jwt"));
        assertFalse(safe.containsKey("cookie"));
        assertFalse(safe.containsKey("apikey"));
        assertFalse(safe.containsKey("password"));
        assertFalse(safe.containsKey("clientsecret"));
        assertFalse(safe.containsKey("otp"));
        assertFalse(safe.containsKey("cardnumber"));
        assertFalse(safe.containsKey("harmless"));
        assertEquals(9, result.removedValues());
    }

    @Test
    void masksPhoneEmailBankAccountNationalIdentifierAndAddress() {
        Map<String, Object> candidate = Map.of(
                "phone", "+44 7700 900123",
                "email", "alice@example.test",
                "bank_account", "GB82 WEST 1234 5698 7654 32",
                "national_id", "NAT-12345678",
                "address", "10 Synthetic Street");
        PayloadSchema schema = PayloadSchema.builder()
                .allow("phone").allow("email").allow("bank_account").allow("national_id").allow("address")
                .build();

        SanitizationResult result = sanitize(candidate, schema);
        Map<?, ?> safe = safeMap(result);

        assertEquals("******0123", safe.get("phone"));
        assertEquals("a***@e***.test", safe.get("email"));
        assertEquals("********5432", safe.get("bankaccount"));
        assertEquals("******5678", safe.get("nationalid"));
        assertEquals("[MASKED_ADDRESS]", safe.get("address"));
        assertEquals(5, result.maskedValues());
        assertFalse(safe.toString().contains("Synthetic Street"));
    }

    @Test
    void excludesPdfJpegPngNestedAndUnknownBase64BeforeARecordCanBeBuilt() {
        String pdf = Base64.getEncoder().encodeToString("%PDF-1.7 synthetic".getBytes(StandardCharsets.US_ASCII));
        String jpeg = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3, 4, 5, 6});
        String png = Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
        String unknown = Base64.getEncoder().encodeToString(new byte[4096]);
        Map<String, Object> candidate = Map.of(
                "pdfBlob", pdf,
                "jpegBlob", jpeg,
                "pngBlob", png,
                "opaqueField", unknown,
                "nested", Map.of("blob", pdf),
                "status", "SAFE");
        PayloadSchema schema = PayloadSchema.builder()
                .allow("pdfBlob").allow("jpegBlob").allow("pngBlob").allow("opaqueField")
                .allow("nested.blob").allow("status").build();

        SanitizationResult result = sanitize(candidate, schema);
        Map<?, ?> safe = safeMap(result);

        assertEquals(Map.of("status", "SAFE"), safe);
        assertTrue(result.omittedBinary().isPresent());
        assertEquals(5, result.omittedBinary().orElseThrow().candidatesOmitted());
        assertNotEquals(0, result.omittedValues());
    }

    @Test
    void excludesArraysOfDocumentsAndNeverTraversesTheirContents() {
        List<Map<String, Object>> documents = List.of(
                Map.of("content", Base64.getEncoder().encodeToString("%PDF-A".getBytes(StandardCharsets.US_ASCII))),
                Map.of("content", Base64.getEncoder().encodeToString("%PDF-B".getBytes(StandardCharsets.US_ASCII))));
        Map<String, Object> candidate = Map.of("documents", documents, "status", "SAFE");
        PayloadSchema schema = PayloadSchema.builder().allow("documents[].content").allow("status").build();

        SanitizationResult result = sanitize(candidate, schema);

        assertEquals(Map.of("status", "SAFE"), safeMap(result));
        assertEquals(BinaryKind.DOCUMENT, result.omittedBinary().orElseThrow().kind());
    }

    @Test
    void rejectsMalformedAndCyclicPayloadsAndOmitsEncryptedOrOversizedPayloads() {
        PayloadSchema schema = PayloadSchema.builder().allow("value").build();
        SanitizationResult malformed = sanitizer.sanitize(
                PayloadInput.malformed("application/json", 17), schema, PayloadCaptureMode.FULL_SANITIZED);
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        SanitizationResult cycle = sanitizer.sanitize(
                PayloadInput.of(Map.of("value", cyclic)), schema, PayloadCaptureMode.FULL_SANITIZED);
        SanitizationResult encrypted = sanitizer.sanitize(
                PayloadInput.encrypted("application/octet-stream", 2048), schema, PayloadCaptureMode.FULL_SANITIZED);
        SanitizationResult oversized = sanitizer.sanitize(
                PayloadInput.of(Map.of("value", "safe"), "application/json", 5L * 1024 * 1024),
                schema,
                PayloadCaptureMode.FULL_SANITIZED);

        assertEquals(SanitizationDisposition.REJECTED, malformed.disposition());
        assertEquals(PayloadStatus.MALFORMED, malformed.status());
        assertEquals(SanitizationDisposition.REJECTED, cycle.disposition());
        assertEquals(BinaryKind.ENCRYPTED, encrypted.omittedBinary().orElseThrow().kind());
        assertEquals(PayloadStatus.BASE64, encrypted.status());
        assertEquals(PayloadStatus.OVERSIZE, oversized.status());
        assertTrue(malformed.payload().isEmpty());
        assertTrue(cycle.payload().isEmpty());
        assertTrue(encrypted.payload().isEmpty());
        assertTrue(oversized.payload().isEmpty());
    }

    @Test
    void excludesBinaryTypesAndDeclaredFiveAndEightMegabyteFixturesWithoutReadingThem() {
        PayloadSchema schema = PayloadSchema.builder().allow("content").build();
        SanitizationResult bytes = sanitizer.sanitize(
                PayloadInput.of(new byte[] {1, 2, 3}), schema, PayloadCaptureMode.FULL_SANITIZED);
        SanitizationResult pdf = sanitizer.sanitize(
                PayloadInput.of("not-read", "application/pdf", 5L * 1024 * 1024), schema, PayloadCaptureMode.FULL_SANITIZED);
        SanitizationResult jpeg = sanitizer.sanitize(
                PayloadInput.of("not-read", "image/jpeg", 8L * 1024 * 1024), schema, PayloadCaptureMode.FULL_SANITIZED);

        assertEquals(BinaryKind.BINARY, bytes.omittedBinary().orElseThrow().kind());
        assertEquals(PayloadStatus.OVERSIZE, pdf.status());
        assertEquals(PayloadStatus.OVERSIZE, jpeg.status());
        assertTrue(bytes.payload().isEmpty());
    }

    @Test
    void captureModesAndUnknownContentFailClosed() {
        PayloadSchema schema = PayloadSchema.builder().allow("known").build();
        PayloadInput input = PayloadInput.of(Map.of("unknown", "unsafe", "known", "safe"));

        assertEquals(PayloadStatus.NOT_REQUESTED,
                sanitizer.sanitize(input, schema, PayloadCaptureMode.NO_PAYLOAD).status());
        assertEquals(PayloadStatus.NOT_REQUESTED,
                sanitizer.sanitize(input, schema, PayloadCaptureMode.METADATA_ONLY).status());
        assertEquals(Map.of("known", "safe"), safeMap(sanitize(input.value(), schema)));

        SanitizationResult onlyUnknown = sanitize(Map.of("unknown", "unsafe"), schema);
        assertEquals(PayloadStatus.NOT_ALLOWLISTED, onlyUnknown.status());
        assertTrue(onlyUnknown.payload().isEmpty());
    }

    @Test
    void explicitRemovePolicyWinsForNonStandardSecretNames() {
        PayloadSchema schema = PayloadSchema.builder()
                .field("vendorProof", PayloadFieldPolicy.REMOVE)
                .allow("companyName")
                .build();
        SanitizationResult result = sanitize(
                Map.of("vendorProof", "must-never-disclose", "companyName", "Synthetic Finance"), schema);

        assertEquals(Map.of("companyname", "Synthetic Finance"), safeMap(result));
        assertEquals(1, result.removedValues());
    }

    @Test
    void enforcesDepthNodeArrayStringAndSafeOutputLimits() {
        PayloadSchema schema = PayloadSchema.builder()
                .allow("values[]")
                .allow("nested.child.value")
                .allow("text")
                .build();

        FailClosedPayloadSanitizer arrayLimited = new FailClosedPayloadSanitizer(
                new PayloadLimits(1024, 1024, 32, 4, 16, 2));
        SanitizationResult tooManyArrayElements = arrayLimited.sanitize(
                PayloadInput.of(Map.of("values", List.of("a", "b", "c"))),
                schema,
                PayloadCaptureMode.FULL_SANITIZED);
        assertEquals(PayloadStatus.OVERSIZE, tooManyArrayElements.status());

        FailClosedPayloadSanitizer nodeLimited = new FailClosedPayloadSanitizer(
                new PayloadLimits(1024, 1024, 32, 4, 4, 4));
        SanitizationResult tooManyNodes = nodeLimited.sanitize(
                PayloadInput.of(Map.of("values", List.of("a", "b", "c", "d"))),
                schema,
                PayloadCaptureMode.FULL_SANITIZED);
        assertEquals(PayloadStatus.OVERSIZE, tooManyNodes.status());

        FailClosedPayloadSanitizer stringLimited = new FailClosedPayloadSanitizer(
                new PayloadLimits(1024, 1024, 8, 4, 16, 4));
        Map<?, ?> truncated = safeMap(stringLimited.sanitize(
                PayloadInput.of(Map.of("text", "abcdefghijk")), schema, PayloadCaptureMode.FULL_SANITIZED));
        assertEquals("abcde…", truncated.get("text"));

        FailClosedPayloadSanitizer outputLimited = new FailClosedPayloadSanitizer(
                new PayloadLimits(1024, 16, 16, 4, 16, 4));
        SanitizationResult tooLargeAfterSanitization = outputLimited.sanitize(
                PayloadInput.of(Map.of("text", "abcdefghijk")), schema, PayloadCaptureMode.FULL_SANITIZED);
        assertEquals(PayloadStatus.OVERSIZE, tooLargeAfterSanitization.status());

        FailClosedPayloadSanitizer depthLimited = new FailClosedPayloadSanitizer(
                new PayloadLimits(1024, 1024, 32, 2, 16, 4));
        SanitizationResult tooDeep = depthLimited.sanitize(
                PayloadInput.of(Map.of("nested", Map.of("child", Map.of("value", "safe")))),
                schema,
                PayloadCaptureMode.FULL_SANITIZED);
        assertEquals(PayloadStatus.NOT_ALLOWLISTED, tooDeep.status());
        assertTrue(tooDeep.payload().isEmpty());
    }

    private SanitizationResult sanitize(Object candidate, PayloadSchema schema) {
        return sanitizer.sanitize(PayloadInput.of(candidate), schema, PayloadCaptureMode.FULL_SANITIZED);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(SanitizationResult result) {
        assertEquals(SanitizationDisposition.CAPTURED, result.disposition());
        return (Map<String, Object>) result.payload().orElseThrow().value().toJavaValue();
    }
}
