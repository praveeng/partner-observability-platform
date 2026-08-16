package com.partner.observability.testapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.partner.observability.testapp.crypto.EncryptedRestTemplateFixture;
import com.partner.observability.testapp.crypto.EncryptedRoundTrip;
import com.partner.observability.testapp.crypto.FixturePlaintextObservationPort;
import com.partner.observability.testapp.crypto.SyntheticCryptoService;
import com.partner.observability.testapp.model.SyntheticPartner;
import com.partner.observability.testapp.model.SyntheticPartnerRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EncryptedRestTemplateFixtureIntegrationTest {

    @Autowired
    private EncryptedRestTemplateFixture encryptedFixture;

    @Autowired
    private SyntheticCryptoService cryptoService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FixturePlaintextObservationPort plaintextObservation;

    @Test
    void observesDtoBeforeEncryptionAndAfterResponseDecryption() {
        SyntheticPartnerRequest request = SyntheticPartnerRequest.standard(
                SyntheticPartner.ALPHA, "SYNTHETIC-ENCRYPTED-APPLICATION-0001");

        EncryptedRoundTrip result = encryptedFixture.roundTrip(request);

        assertThat(result.response()).isEqualTo(request);
        assertThat(result.requestCiphertextBytes()).isGreaterThan(0);
        assertThat(result.responseCiphertextBytes()).isEqualTo(result.requestCiphertextBytes());
        verify(plaintextObservation).beforeEncryption(request);
        verify(plaintextObservation).afterDecryption(request);
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
}
