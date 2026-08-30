package com.samsung.sure.partner.observability.testapp.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Bounded control-plane response; potentially sensitive fixture bodies are never returned by it. */
public record ScenarioSummary(
        String client,
        String scenario,
        String partner,
        String applicationId,
        int attempts,
        int httpStatus,
        int responseBytes,
        String responseSha256,
        String failureType) {

    public static ScenarioSummary success(ClientExchange exchange) {
        byte[] bytes = exchange.responseBody().getBytes(StandardCharsets.UTF_8);
        return new ScenarioSummary(
                exchange.client(),
                exchange.scenario().name(),
                exchange.partner().name(),
                exchange.applicationId(),
                exchange.attempts(),
                exchange.httpStatus(),
                bytes.length,
                sha256(bytes),
                null);
    }

    public static ScenarioSummary failure(
            String client, SyntheticScenario scenario, SyntheticPartner partner, String failureType) {
        return new ScenarioSummary(
                client,
                scenario.name(),
                partner.name(),
                SyntheticPartnerRequest.COLLIDING_APPLICATION_ID,
                1,
                0,
                0,
                null,
                failureType);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", exception);
        }
    }
}
