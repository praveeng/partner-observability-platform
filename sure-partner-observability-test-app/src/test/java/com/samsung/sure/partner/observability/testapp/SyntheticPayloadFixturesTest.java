package com.samsung.sure.partner.observability.testapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.samsung.sure.partner.observability.testapp.fixture.SyntheticPayloadFixtures;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartner;
import com.samsung.sure.partner.observability.testapp.model.SyntheticScenario;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyntheticPayloadFixturesTest {

    private final SyntheticPayloadFixtures fixtures = new SyntheticPayloadFixtures();

    @Test
    void generatesExactSizePdfAndJpegBase64Fixtures() {
        byte[] pdf = Base64.getDecoder().decode(fixtures.pdfBase64());
        byte[] jpeg = Base64.getDecoder().decode(fixtures.jpegBase64());

        assertThat(pdf).hasSize(SyntheticPayloadFixtures.PDF_BYTES);
        assertThat(new String(pdf, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.7");
        assertThat(jpeg).hasSize(SyntheticPayloadFixtures.JPEG_BYTES);
        assertThat(jpeg).startsWith((byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0);
    }

    @Test
    void generatesOpaqueAndDocumentArrayBase64Fixtures() {
        Map<String, Object> opaque = fixtures.payloadFor(
                SyntheticScenario.UNKNOWN_LARGE_BASE64, SyntheticPartner.ALPHA);
        byte[] opaqueBytes = Base64.getDecoder().decode((String) opaque.get("opaqueWidgetState"));
        assertThat(opaqueBytes).hasSize(SyntheticPayloadFixtures.UNKNOWN_BINARY_BYTES);

        Map<String, Object> array = fixtures.payloadFor(
                SyntheticScenario.BASE64_DOCUMENT_ARRAY, SyntheticPartner.ALPHA);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> attachments = (List<Map<String, String>>) array.get("attachments");
        assertThat(attachments).hasSize(3);
        assertThat(attachments)
                .allSatisfy(attachment -> assertThat(Base64.getDecoder().decode(attachment.get("content")))
                        .hasSize(SyntheticPayloadFixtures.DOCUMENT_ARRAY_ELEMENT_BYTES));
    }

    @Test
    void coversEverySensitiveFixtureCategoryWithSyntheticValues() {
        Map<String, Object> credentials = fixtures.payloadFor(
                SyntheticScenario.CREDENTIALS, SyntheticPartner.ALPHA);
        assertThat(credentials.keySet()).contains(
                "Authorization", "jwt", "Cookie", "apiKey", "password", "clientSecret", "accessToken", "encryptionKey");

        Map<String, Object> otp = fixtures.payloadFor(SyntheticScenario.OTP, SyntheticPartner.ALPHA);
        Map<String, Object> card = fixtures.payloadFor(SyntheticScenario.CARD_DATA, SyntheticPartner.ALPHA);
        Map<String, Object> pii = fixtures.payloadFor(SyntheticScenario.RESTRICTED_PII, SyntheticPartner.ALPHA);
        Map<String, Object> nested = fixtures.payloadFor(SyntheticScenario.NESTED_SENSITIVE, SyntheticPartner.ALPHA);

        assertThat(otp).containsKey("verification");
        assertThat(card).containsKey("paymentCard");
        assertThat(pii.keySet()).contains("phone", "email", "bankAccount", "nationalId", "address");
        assertThat(nested).containsKey("customer");
        assertThat(List.of(credentials, otp, card, pii, nested))
                .allSatisfy(payload -> assertThat(payload).containsEntry("fixtureClassification", "SYNTHETIC_ONLY"));
    }

    @Test
    void largeNormalJsonIsTextualAndWithinTheRawCandidateFixtureBoundary() {
        Map<String, Object> payload = fixtures.payloadFor(
                SyntheticScenario.LARGE_NORMAL_JSON, SyntheticPartner.BETA);
        String description = (String) payload.get("description");

        assertThat(description.getBytes(StandardCharsets.UTF_8))
                .hasSize(SyntheticPayloadFixtures.LARGE_NORMAL_TEXT_BYTES);
        assertThat(payload).containsEntry("partnerLane", "BETA");

        Map<String, Object> mixed = fixtures.payloadFor(
                SyntheticScenario.MIXED_LARGE_JSON_96_KIB, SyntheticPartner.BETA);
        assertThat(((String) mixed.get("description")).getBytes(StandardCharsets.UTF_8))
                .hasSize(SyntheticPayloadFixtures.MIXED_LARGE_TEXT_BYTES);
    }

    @Test
    void requestSideBinaryScenariosUseOnlySyntheticPayloads() {
        assertThat(fixtures.payloadFor(
                        SyntheticScenario.PDF_REQUEST_BASE64_5_MB, SyntheticPartner.ALPHA))
                .containsEntry("fixtureClassification", "SYNTHETIC_ONLY");
        assertThat(fixtures.payloadFor(
                        SyntheticScenario.JPEG_REQUEST_BASE64_8_MB, SyntheticPartner.ALPHA))
                .containsEntry("fixtureClassification", "SYNTHETIC_ONLY");
        assertThat(fixtures.payloadFor(
                        SyntheticScenario.UNKNOWN_REQUEST_LARGE_BASE64, SyntheticPartner.ALPHA))
                .containsEntry("fixtureClassification", "SYNTHETIC_ONLY");
        assertThat(fixtures.payloadFor(
                        SyntheticScenario.MALFORMED_RESPONSE_BINARY_REQUEST, SyntheticPartner.ALPHA))
                .containsEntry("fixtureClassification", "SYNTHETIC_ONLY");
    }
}
