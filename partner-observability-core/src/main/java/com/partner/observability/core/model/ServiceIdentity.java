package com.partner.observability.core.model;

import java.util.Objects;

public record ServiceIdentity(String serviceName, String serviceVersion) {
    public ServiceIdentity {
        serviceName = safeToken(serviceName, "serviceName");
        serviceVersion = safeToken(serviceVersion, "serviceVersion");
    }

    private static String safeToken(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,62}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
