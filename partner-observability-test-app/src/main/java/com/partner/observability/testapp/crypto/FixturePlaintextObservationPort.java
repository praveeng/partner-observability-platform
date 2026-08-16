package com.partner.observability.testapp.crypto;

import com.partner.observability.testapp.model.SyntheticPartnerRequest;

/**
 * Fixture-only seam for a future production explicit observation API. Implementations must project
 * synchronously and must not retain the supplied DTO.
 */
public interface FixturePlaintextObservationPort {

    void beforeEncryption(SyntheticPartnerRequest request);

    void afterDecryption(SyntheticPartnerRequest response);
}
