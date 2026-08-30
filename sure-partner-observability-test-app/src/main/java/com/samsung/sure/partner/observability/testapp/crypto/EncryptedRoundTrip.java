package com.samsung.sure.partner.observability.testapp.crypto;

import com.samsung.sure.partner.observability.testapp.model.SyntheticPartnerRequest;

public record EncryptedRoundTrip(
        SyntheticPartnerRequest response, int requestCiphertextBytes, int responseCiphertextBytes) {}
