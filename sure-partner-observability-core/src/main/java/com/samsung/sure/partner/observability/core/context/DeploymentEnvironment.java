package com.samsung.sure.partner.observability.core.context;

public enum DeploymentEnvironment {
    LOCAL("local"),
    DEV("dev"),
    STAGE("stage"),
    PROD("prod");

    private final String canonicalValue;

    DeploymentEnvironment(String canonicalValue) {
        this.canonicalValue = canonicalValue;
    }

    public String canonicalValue() {
        return canonicalValue;
    }
}
