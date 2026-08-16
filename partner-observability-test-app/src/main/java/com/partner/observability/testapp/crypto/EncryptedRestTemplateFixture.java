package com.partner.observability.testapp.crypto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.partner.observability.testapp.fixture.LocalMockPartnerServer;
import com.partner.observability.testapp.model.SyntheticPartnerRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Exercises DTO -> JSON -> encryption -> RestTemplate and the inverse response path. */
@Component
public final class EncryptedRestTemplateFixture {

    private final RestTemplate restTemplate;
    private final LocalMockPartnerServer mockServer;
    private final ObjectMapper objectMapper;
    private final SyntheticCryptoService cryptoService;
    private final FixturePlaintextObservationPort plaintextObservation;

    public EncryptedRestTemplateFixture(
            RestTemplate fixtureRestTemplate,
            LocalMockPartnerServer mockServer,
            ObjectMapper objectMapper,
            SyntheticCryptoService cryptoService,
            FixturePlaintextObservationPort plaintextObservation) {
        this.restTemplate = fixtureRestTemplate;
        this.mockServer = mockServer;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.plaintextObservation = plaintextObservation;
    }

    public EncryptedRoundTrip roundTrip(SyntheticPartnerRequest request) {
        plaintextObservation.beforeEncryption(request);
        byte[] requestJson = writeJson(request);
        byte[] requestCiphertext = cryptoService.encrypt(requestJson);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                mockServer.encryptedUri(),
                HttpMethod.POST,
                new HttpEntity<>(requestCiphertext, headers),
                byte[].class);
        byte[] responseCiphertext = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || responseCiphertext == null) {
            throw new IllegalStateException("SYNTHETIC_ENCRYPTED_CALL_FAILED");
        }

        SyntheticPartnerRequest responseDto = readJson(cryptoService.decrypt(responseCiphertext));
        plaintextObservation.afterDecryption(responseDto);
        return new EncryptedRoundTrip(responseDto, requestCiphertext.length, responseCiphertext.length);
    }

    private byte[] writeJson(SyntheticPartnerRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SYNTHETIC_JSON_SERIALIZATION_FAILED", exception);
        }
    }

    private SyntheticPartnerRequest readJson(byte[] plaintext) {
        try {
            return objectMapper.readValue(plaintext, SyntheticPartnerRequest.class);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("SYNTHETIC_JSON_DESERIALIZATION_FAILED", exception);
        }
    }
}
