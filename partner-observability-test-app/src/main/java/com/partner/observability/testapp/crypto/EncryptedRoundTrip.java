package com.partner.observability.testapp.crypto;

import com.partner.observability.testapp.model.SyntheticPartnerRequest;

public record EncryptedRoundTrip(
        SyntheticPartnerRequest response, int requestCiphertextBytes, int responseCiphertextBytes) {}
