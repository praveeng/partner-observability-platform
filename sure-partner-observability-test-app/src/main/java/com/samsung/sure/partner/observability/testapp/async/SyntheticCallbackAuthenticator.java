package com.samsung.sure.partner.observability.testapp.async;

import com.samsung.sure.partner.observability.testapp.model.SyntheticPartner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Fixture-only fixed signature adapter. It models a trusted host result, not production auth. */
@Component
public final class SyntheticCallbackAuthenticator {

    private static final String ALPHA_SIGNATURE = "SYNTHETIC-SIGNATURE-ALPHA-ONLY";
    private static final String BETA_SIGNATURE = "SYNTHETIC-SIGNATURE-BETA-ONLY";

    public Optional<SyntheticPartner> authenticate(SyntheticPartner routePartner, String suppliedSignature) {
        if (suppliedSignature == null) {
            return Optional.empty();
        }
        byte[] expected = signatureFor(routePartner).getBytes(StandardCharsets.UTF_8);
        byte[] supplied = suppliedSignature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied) ? Optional.of(routePartner) : Optional.empty();
    }

    public String signatureFor(SyntheticPartner partner) {
        return partner == SyntheticPartner.ALPHA ? ALPHA_SIGNATURE : BETA_SIGNATURE;
    }
}
