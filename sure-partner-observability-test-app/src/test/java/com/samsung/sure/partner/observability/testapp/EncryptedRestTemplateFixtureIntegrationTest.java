package com.samsung.sure.partner.observability.testapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.sure.partner.observability.core.health.TelemetryHealth;
import com.samsung.sure.partner.observability.core.model.OutboundApiRequestRecord;
import com.samsung.sure.partner.observability.core.model.OutboundApiResponseRecord;
import com.samsung.sure.partner.observability.core.model.TelemetryEnvelope;
import com.samsung.sure.partner.observability.core.payload.SanitizationResult;
import com.samsung.sure.partner.observability.testapp.crypto.EncryptedRestTemplateFixture;
import com.samsung.sure.partner.observability.testapp.crypto.EncryptedRoundTrip;
import com.samsung.sure.partner.observability.testapp.crypto.SyntheticCryptoService;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartner;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartnerRequest;
import com.samsung.sure.partner.observability.testapp.telemetry.SyntheticTelemetryCollector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EncryptedRestTemplateFixtureIntegrationTest {

    @Autowired
    private EncryptedRestTemplateFixture encryptedFixture;

    @Autowired
    private SyntheticCryptoService cryptoService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SyntheticTelemetryCollector telemetry;

    @Autowired
    private TelemetryHealth health;

    @Test
    void exposesOnlySanitizedLogicalRequestAndResponse() {
        SyntheticPartnerRequest request = SyntheticPartnerRequest.standard(
                SyntheticPartner.ALPHA, "SYNTHETIC-ENCRYPTED-APPLICATION-0001");

        telemetry.clear();
        EncryptedRoundTrip result = encryptedFixture.roundTrip(SyntheticPartner.ALPHA, request);

        assertThat(result.response()).isEqualTo(request);
        assertThat(result.requestCiphertextBytes()).isGreaterThan(0);
        assertThat(result.responseCiphertextBytes()).isEqualTo(result.requestCiphertextBytes());
        List<TelemetryEnvelope<?>> records = awaitEncrypted("PARTNER_ALPHA_ENCRYPTED", 2);
        assertThat(records).hasSize(2);
        assertThat(records).anyMatch(record -> record.body() instanceof OutboundApiRequestRecord);
        assertThat(records).anyMatch(record -> record.body() instanceof OutboundApiResponseRecord);
        assertThat(records).extracting(TelemetryEnvelope::interactionId)
                .containsOnly(records.get(0).interactionId());
        assertThat(records).extracting(record -> record.partnerContext().canonicalPartnerKey())
                .containsOnly("partner-alpha-fixture");
        OutboundApiRequestRecord logicalRequest = records.stream()
                .map(TelemetryEnvelope::body)
                .filter(OutboundApiRequestRecord.class::isInstance)
                .map(OutboundApiRequestRecord.class::cast)
                .findFirst()
                .orElseThrow();
        OutboundApiResponseRecord logicalResponse = records.stream()
                .map(TelemetryEnvelope::body)
                .filter(OutboundApiResponseRecord.class::isInstance)
                .map(OutboundApiResponseRecord.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(logicalRequest.payload().payload().orElseThrow().value().toJavaValue().toString())
                .contains("SYNTHETIC-SKU-001", "SYNTHETIC_ONLY");
        assertThat(logicalResponse.payload().payload().orElseThrow().value().toJavaValue().toString())
                .contains("SYNTHETIC-SKU-001", "SYNTHETIC_ONLY");
        assertThat(logicalRequest.contentType()).contains("application/json");
        assertThat(logicalResponse.contentType()).contains("application/json");
        assertThat(logicalRequest.declaredSizeBytes()).isEmpty();
        assertThat(logicalResponse.declaredSizeBytes()).isEmpty();
        assertThat(records.toString()).doesNotContain("application/octet-stream");
    }

    @Test
    void ciphertextDoesNotContainThePlaintextDtoAndDecryptsExactly() throws Exception {
        SyntheticPartnerRequest request = SyntheticPartnerRequest.standard(
                SyntheticPartner.BETA, "SYNTHETIC-ENCRYPTED-APPLICATION-0002");
        byte[] plaintext = objectMapper.writeValueAsBytes(request);

        byte[] ciphertext = cryptoService.encrypt(plaintext);
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1))
                .doesNotContain(request.applicationId());
        assertThat(cryptoService.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    void removesCryptoSecretsAndExcludesLargeBase64BeforeQueueAdmission() {
        String syntheticKey = "SYNTHETIC-KEY-MATERIAL-NEVER-EMIT";
        String syntheticIv = "SYNTHETIC-IV-NEVER-EMIT";
        String syntheticCredential = "SYNTHETIC-CREDENTIAL-NEVER-EMIT";
        String document = Base64.getEncoder().encodeToString(new byte[96 * 1024]);
        SyntheticPartnerRequest base = SyntheticPartnerRequest.standard(
                SyntheticPartner.ALPHA, "SYNTHETIC-ENCRYPTED-APPLICATION-SECRET");
        Map<String, Object> attributes = new LinkedHashMap<>(base.attributes());
        attributes.put("encryptionKey", syntheticKey);
        attributes.put("initializationVector", syntheticIv);
        attributes.put("credential", syntheticCredential);
        attributes.put("document", document);
        SyntheticPartnerRequest request = new SyntheticPartnerRequest(
                base.applicationId(), base.partnerReference(), base.amount(), base.tenureMonths(),
                base.product(), attributes);

        telemetry.clear();
        EncryptedRoundTrip result = encryptedFixture.roundTrip(SyntheticPartner.ALPHA, request);

        assertThat(result.response()).isEqualTo(request);
        List<TelemetryEnvelope<?>> records = awaitEncrypted("PARTNER_ALPHA_ENCRYPTED", 2);
        String safeText = safePayloadText(records);
        assertThat(records).allMatch(record -> payload(record).omittedValues() > 0);
        assertThat(safeText)
                .contains("SYNTHETIC-SKU-001", "SYNTHETIC_ONLY")
                .doesNotContain("encryptionKey", "initializationVector", "credential", "document");
        assertThat(safeText.contains(syntheticKey)
                || safeText.contains(syntheticIv)
                || safeText.contains(syntheticCredential)
                || safeText.contains(document)).isFalse();
        String recordText = records.toString();
        assertThat(recordText.contains(syntheticKey)
                || recordText.contains(syntheticIv)
                || recordText.contains(syntheticCredential)
                || recordText.contains(document)).isFalse();
    }

    @Test
    void routesTwoEncryptedPartnersFromConfiguredServerSideIdentity() {
        telemetry.clear();
        encryptedFixture.roundTrip(SyntheticPartner.ALPHA, SyntheticPartnerRequest.standard(
                SyntheticPartner.ALPHA, "SYNTHETIC-ENCRYPTED-ALPHA"));
        encryptedFixture.roundTrip(SyntheticPartner.BETA, SyntheticPartnerRequest.standard(
                SyntheticPartner.BETA, "SYNTHETIC-ENCRYPTED-BETA"));

        List<TelemetryEnvelope<?>> records = awaitTotalEncrypted(4);
        assertThat(records).extracting(record -> record.partnerContext().canonicalPartnerKey())
                .containsExactlyInAnyOrder(
                        "partner-alpha-fixture", "partner-alpha-fixture",
                        "partner-beta-fixture", "partner-beta-fixture");
    }

    @Test
    void publisherFailureAndHookFailureDoNotAffectEncryptedBusinessTraffic() {
        telemetry.clear();
        long failuresBefore = health.snapshot().publisherFailures();
        telemetry.failPublishing(true);
        SyntheticPartnerRequest request = SyntheticPartnerRequest.standard(
                SyntheticPartner.BETA, "SYNTHETIC-ENCRYPTED-DOWN");
        assertThat(encryptedFixture.roundTrip(SyntheticPartner.BETA, request).response()).isEqualTo(request);
        await(() -> health.snapshot().publisherFailures() > failuresBefore);

        telemetry.clear();
        SyntheticPartnerRequest base = SyntheticPartnerRequest.standard(
                SyntheticPartner.ALPHA, "SYNTHETIC-ENCRYPTED-HOOK-FAILURE");
        Map<String, Object> attributes = new LinkedHashMap<>(base.attributes());
        attributes.put("failObservationExtractor", true);
        SyntheticPartnerRequest failingHook = new SyntheticPartnerRequest(
                base.applicationId(), base.partnerReference(), base.amount(), base.tenureMonths(),
                base.product(), attributes);
        assertThat(encryptedFixture.roundTrip(SyntheticPartner.ALPHA, failingHook).response())
                .isEqualTo(failingHook);
    }

    private List<TelemetryEnvelope<?>> awaitEncrypted(String apiName, int expected) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            List<TelemetryEnvelope<?>> values = telemetry.snapshot().stream()
                    .filter(record -> apiName.equals(apiName(record)))
                    .toList();
            if (values.size() >= expected) return values;
            sleep();
        }
        throw new AssertionError("SYNTHETIC_ENCRYPTED_TELEMETRY_TIMEOUT");
    }

    private List<TelemetryEnvelope<?>> awaitTotalEncrypted(int expected) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            List<TelemetryEnvelope<?>> values = telemetry.snapshot().stream()
                    .filter(record -> apiName(record).endsWith("_ENCRYPTED"))
                    .toList();
            if (values.size() >= expected) return values;
            sleep();
        }
        throw new AssertionError("SYNTHETIC_ENCRYPTED_TELEMETRY_TIMEOUT");
    }

    private String safePayloadText(List<TelemetryEnvelope<?>> records) {
        StringBuilder text = new StringBuilder();
        for (TelemetryEnvelope<?> record : records) {
            SanitizationResult payload = payload(record);
            payload.payload().ifPresent(value -> text.append(value.value().toJavaValue()));
        }
        return text.toString();
    }

    private SanitizationResult payload(TelemetryEnvelope<?> record) {
        return record.body() instanceof OutboundApiRequestRecord request
                ? request.payload() : ((OutboundApiResponseRecord) record.body()).payload();
    }

    private String apiName(TelemetryEnvelope<?> record) {
        if (record.body() instanceof OutboundApiRequestRecord request) return request.apiId();
        if (record.body() instanceof OutboundApiResponseRecord response) return response.apiId();
        return "";
    }

    private void sleep() {
        try { Thread.sleep(25); } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("SYNTHETIC_AWAIT_INTERRUPTED", exception);
        }
    }

    private void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            sleep();
        }
        throw new AssertionError("SYNTHETIC_ENCRYPTED_CONDITION_TIMEOUT");
    }
}
