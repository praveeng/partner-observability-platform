package com.partner.observability.testapp.model;

import java.util.Locale;

/** Fixed synthetic partner identities used only by the local fixture control plane. */
public enum SyntheticPartner {
    ALPHA("partner-alpha-fixture", "p001"),
    BETA("partner-beta-fixture", "p002");

    private final String canonicalKey;
    private final String partnerSlot;

    SyntheticPartner(String canonicalKey, String partnerSlot) {
        this.canonicalKey = canonicalKey;
        this.partnerSlot = partnerSlot;
    }

    public String canonicalKey() {
        return canonicalKey;
    }

    public String partnerSlot() {
        return partnerSlot;
    }

    public SyntheticPartner other() {
        return this == ALPHA ? BETA : ALPHA;
    }

    public static SyntheticPartner fromFixturePath(String value) {
        return valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
