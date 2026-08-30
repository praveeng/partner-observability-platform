package com.samsung.sure.partner.observability.testapp.fixture;

import com.samsung.sure.partner.observability.testapp.model.SyntheticPartner;
import com.samsung.sure.partner.observability.testapp.model.SyntheticScenario;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Generates deterministic synthetic payloads without storing customer- or partner-derived samples. */
@Component
public final class SyntheticPayloadFixtures {

    public static final int PDF_BYTES = 5 * 1024 * 1024;
    public static final int JPEG_BYTES = 8 * 1024 * 1024;
    public static final int UNKNOWN_BINARY_BYTES = 2 * 1024 * 1024;
    public static final int DOCUMENT_ARRAY_ELEMENT_BYTES = 128 * 1024;
    public static final int LARGE_NORMAL_TEXT_BYTES = 32 * 1024;
    public static final int MIXED_LARGE_TEXT_BYTES = 96 * 1024;

    private volatile String pdfBase64;
    private volatile String jpegBase64;
    private volatile String unknownBase64;

    public Map<String, Object> payloadFor(SyntheticScenario scenario, SyntheticPartner partner) {
        return switch (scenario) {
            case LARGE_NORMAL_JSON -> largeNormalJson(partner);
            case MIXED_LARGE_JSON_96_KIB -> mixedLargeJson(partner);
            case PDF_BASE64_5_MB, PDF_REQUEST_BASE64_5_MB -> Map.of(
                    "fixtureClassification", "SYNTHETIC_ONLY",
                    "documentType", "PDF",
                    "document", pdfBase64());
            case JPEG_BASE64_8_MB, JPEG_REQUEST_BASE64_8_MB -> Map.of(
                    "fixtureClassification", "SYNTHETIC_ONLY",
                    "imageType", "JPEG",
                    "image", jpegBase64());
            case UNKNOWN_LARGE_BASE64, UNKNOWN_REQUEST_LARGE_BASE64, MALFORMED_RESPONSE_BINARY_REQUEST -> Map.of(
                    "fixtureClassification", "SYNTHETIC_ONLY",
                    "opaqueWidgetState", unknownBase64());
            case BASE64_DOCUMENT_ARRAY -> documentArray();
            case NESTED_SENSITIVE -> nestedSensitive();
            case CREDENTIALS -> credentials();
            case OTP -> Map.of(
                    "fixtureClassification", "SYNTHETIC_ONLY",
                    "verification", Map.of("oneTimePassword", "111111", "verificationCode", "222222"));
            case CARD_DATA -> Map.of(
                    "fixtureClassification", "SYNTHETIC_ONLY",
                    "paymentCard", Map.of(
                            "cardNumber", "4111111111111111",
                            "cvv", "123",
                            "expiry", "12/39",
                            "pin", "4321"));
            case RESTRICTED_PII -> restrictedPii();
            default -> throw new IllegalArgumentException("SCENARIO_HAS_NO_SPECIAL_PAYLOAD");
        };
    }

    public String pdfBase64() {
        String current = pdfBase64;
        if (current == null) {
            synchronized (this) {
                current = pdfBase64;
                if (current == null) {
                    current = encodedFixture(PDF_BYTES, "%PDF-1.7\n%SYNTHETIC-FIXTURE\n".getBytes(StandardCharsets.US_ASCII), (byte) 'P');
                    pdfBase64 = current;
                }
            }
        }
        return current;
    }

    public String jpegBase64() {
        String current = jpegBase64;
        if (current == null) {
            synchronized (this) {
                current = jpegBase64;
                if (current == null) {
                    byte[] prefix = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0,
                            'S', 'Y', 'N', 'T', 'H', 'E', 'T', 'I', 'C'};
                    current = encodedFixture(JPEG_BYTES, prefix, (byte) 0x5a);
                    jpegBase64 = current;
                }
            }
        }
        return current;
    }

    public String unknownBase64() {
        String current = unknownBase64;
        if (current == null) {
            synchronized (this) {
                current = unknownBase64;
                if (current == null) {
                    current = encodedFixture(
                            UNKNOWN_BINARY_BYTES,
                            "SYNTHETIC-OPAQUE-BINARY".getBytes(StandardCharsets.US_ASCII),
                            (byte) 0x3c);
                    unknownBase64 = current;
                }
            }
        }
        return current;
    }

    private Map<String, Object> largeNormalJson(SyntheticPartner partner) {
        return textualPayload(partner, LARGE_NORMAL_TEXT_BYTES);
    }

    private Map<String, Object> mixedLargeJson(SyntheticPartner partner) {
        return textualPayload(partner, MIXED_LARGE_TEXT_BYTES);
    }

    private Map<String, Object> textualPayload(SyntheticPartner partner, int bytes) {
        String marker = "SYNTHETIC-NORMAL-TEXT-";
        String text = marker.repeat((bytes / marker.length()) + 1).substring(0, bytes);
        return Map.of(
                "fixtureClassification", "SYNTHETIC_ONLY",
                "partnerLane", partner.name(),
                "description", text,
                "amount", "1234.56",
                "currency", "USD",
                "product", "SYNTHETIC-SKU-001");
    }

    private Map<String, Object> documentArray() {
        List<Map<String, String>> documents = new ArrayList<>();
        documents.add(Map.of(
                "name", "synthetic-one.pdf",
                "content", encodedFixture(
                        DOCUMENT_ARRAY_ELEMENT_BYTES,
                        "%PDF-SYNTHETIC-ONE".getBytes(StandardCharsets.US_ASCII),
                        (byte) '1')));
        documents.add(Map.of(
                "name", "synthetic-two.jpg",
                "content", encodedFixture(
                        DOCUMENT_ARRAY_ELEMENT_BYTES,
                        new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 'T', 'W', 'O'},
                        (byte) '2')));
        documents.add(Map.of(
                "name", "synthetic-three.bin",
                "content", encodedFixture(
                        DOCUMENT_ARRAY_ELEMENT_BYTES,
                        "SYNTHETIC-THREE".getBytes(StandardCharsets.US_ASCII),
                        (byte) '3')));
        return Map.of("fixtureClassification", "SYNTHETIC_ONLY", "attachments", List.copyOf(documents));
    }

    private Map<String, Object> nestedSensitive() {
        return Map.of(
                "fixtureClassification", "SYNTHETIC_ONLY",
                "customer", Map.of(
                        "profile", Map.of(
                                "contact", Map.of(
                                        "emailAddress", "fixture.user@example.invalid",
                                        "mobilePhone", "+1-202-555-0147"),
                                "identity", Map.of(
                                        "nationalIdentifier", "SYNTHETIC-NID-00006789",
                                        "password", "SYNTHETIC_PASSWORD_ONLY"))));
    }

    private Map<String, Object> credentials() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fixtureClassification", "SYNTHETIC_ONLY");
        values.put("Authorization", "Bearer SYNTHETIC_AUTHORIZATION_ONLY");
        values.put("jwt", "eyJzeW50aGV0aWMiOiJmaXh0dXJlIn0.eyJzdWIiOiJub24tcmVhbCJ9.c3ludGhldGljLXNpZ25hdHVyZQ");
        values.put("Cookie", "fixture_session=SYNTHETIC_SESSION_ONLY");
        values.put("apiKey", "SYNTHETIC_API_KEY_ONLY");
        values.put("password", "SYNTHETIC_PASSWORD_ONLY");
        values.put("clientSecret", "SYNTHETIC_CLIENT_SECRET_ONLY");
        values.put("accessToken", "SYNTHETIC_ACCESS_TOKEN_ONLY");
        values.put("encryptionKey", "SYNTHETIC_ENCRYPTION_KEY_ONLY");
        return Map.copyOf(values);
    }

    private Map<String, Object> restrictedPii() {
        return Map.of(
                "fixtureClassification", "SYNTHETIC_ONLY",
                "phone", "+1-202-555-0147",
                "email", "fixture.user@example.invalid",
                "bankAccount", "SYNTHETIC-ACCOUNT-00001234",
                "nationalId", "SYNTHETIC-NID-00006789",
                "address", Map.of(
                        "line1", "100 Synthetic Fixture Avenue",
                        "city", "Testville",
                        "postalCode", "SYN-000"));
    }

    private String encodedFixture(int size, byte[] prefix, byte fill) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, fill);
        System.arraycopy(prefix, 0, bytes, 0, Math.min(prefix.length, bytes.length));
        return Base64.getEncoder().encodeToString(bytes);
    }
}
