package com.partner.observability.testapp.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.partner.observability.testapp.async.SyntheticAsyncLifecycleStore;
import com.partner.observability.testapp.async.SyntheticAsyncLifecycleStore.CallbackHandle;
import com.partner.observability.testapp.async.SyntheticCallbackAuthenticator;
import com.partner.observability.testapp.async.SyntheticCallbackSecurityCounters;
import com.partner.observability.testapp.async.SyntheticCallbackSecurityCounters.DenialReason;
import com.partner.observability.testapp.fixture.LocalMockPartnerServer;
import com.partner.observability.testapp.model.SyntheticAsyncScenario;
import com.partner.observability.testapp.model.SyntheticCorrelationIdentifiers;
import com.partner.observability.testapp.model.SyntheticPartner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Authenticated loopback callback ingress used only by the synthetic fixture. */
@RestController
@RequestMapping("/fixture/callback")
public final class SyntheticCallbackController {

    private static final int MAX_CALLBACK_BYTES = 16 * 1024 * 1024;
    private static final Pattern RUN_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ObjectMapper objectMapper;
    private final SyntheticCallbackAuthenticator authenticator;
    private final SyntheticCallbackSecurityCounters securityCounters;
    private final SyntheticAsyncLifecycleStore lifecycleStore;
    private final Executor processingExecutor;

    public SyntheticCallbackController(
            ObjectMapper objectMapper,
            SyntheticCallbackAuthenticator authenticator,
            SyntheticCallbackSecurityCounters securityCounters,
            SyntheticAsyncLifecycleStore lifecycleStore,
            @Qualifier("syntheticCallbackProcessingExecutor") Executor processingExecutor) {
        this.objectMapper = objectMapper;
        this.authenticator = authenticator;
        this.securityCounters = securityCounters;
        this.lifecycleStore = lifecycleStore;
        this.processingExecutor = processingExecutor;
    }

    @PostMapping(path = "/{partner}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> callback(
            @PathVariable String partner,
            @RequestHeader(name = LocalMockPartnerServer.CALLBACK_RUN_HEADER, required = false) String runId,
            @RequestHeader(name = LocalMockPartnerServer.CALLBACK_SIGNATURE_HEADER, required = false)
                    String signature,
            HttpServletRequest request) {
        SyntheticPartner routePartner = parsePartner(partner);
        if (routePartner == null || !validRunId(runId)) {
            securityCounters.increment(DenialReason.UNKNOWN_RUN);
            return fixedResponse(HttpStatus.UNAUTHORIZED, "SYNTHETIC_CALLBACK_DENIED");
        }

        Optional<SyntheticPartner> authenticated = authenticator.authenticate(routePartner, signature);
        if (authenticated.isEmpty()) {
            securityCounters.increment(DenialReason.AUTHENTICATION_FAILED);
            return fixedResponse(HttpStatus.UNAUTHORIZED, "SYNTHETIC_CALLBACK_DENIED");
        }
        Optional<SyntheticAsyncScenario> scenario = lifecycleStore.authorizedScenario(runId, authenticated.get());
        if (scenario.isEmpty()) {
            securityCounters.increment(DenialReason.WRONG_PARTNER);
            return fixedResponse(HttpStatus.FORBIDDEN, "SYNTHETIC_CALLBACK_PARTNER_CONFLICT");
        }

        byte[] body;
        try {
            body = readBounded(request.getInputStream());
        } catch (BodyTooLargeException exception) {
            securityCounters.increment(DenialReason.OVERSIZED_BODY);
            return fixedResponse(HttpStatus.PAYLOAD_TOO_LARGE, "SYNTHETIC_CALLBACK_TOO_LARGE");
        } catch (IOException exception) {
            return fixedResponse(HttpStatus.BAD_REQUEST, "SYNTHETIC_CALLBACK_READ_FAILED");
        }

        JsonNode callback;
        try {
            callback = objectMapper.readTree(body);
            if (callback == null || !callback.isObject()) {
                throw new IOException("SYNTHETIC_CALLBACK_JSON_REQUIRED");
            }
        } catch (IOException exception) {
            CallbackHandle handle = lifecycleStore.callbackReceived(
                    runId,
                    authenticated.get(),
                    emptyIdentifiers(),
                    null,
                    body.length,
                    "MALFORMED_JSON");
            lifecycleStore.processingFailed(handle, "PARSING_FAILED");
            lifecycleStore.responseSent(handle, 400);
            return fixedResponse(HttpStatus.BAD_REQUEST, "SYNTHETIC_CALLBACK_MALFORMED");
        }

        SyntheticCorrelationIdentifiers identifiers;
        try {
            identifiers = identifiers(callback);
        } catch (IllegalArgumentException exception) {
            CallbackHandle handle = lifecycleStore.callbackReceived(
                    runId,
                    authenticated.get(),
                    emptyIdentifiers(),
                    null,
                    body.length,
                    "INVALID_IDENTIFIERS");
            lifecycleStore.processingFailed(handle, "VALIDATION_FAILED");
            lifecycleStore.responseSent(handle, 422);
            return fixedResponse(HttpStatus.UNPROCESSABLE_ENTITY, "SYNTHETIC_CALLBACK_INVALID");
        }

        CallbackHandle handle = lifecycleStore.callbackReceived(
                runId,
                authenticated.get(),
                identifiers,
                integerOrNull(callback, "callbackSequence"),
                body.length,
                payloadCategory(scenario.get()));
        return process(handle);
    }

    private ResponseEntity<?> process(CallbackHandle handle) {
        SyntheticAsyncScenario scenario = handle.scenario();
        if (scenario == SyntheticAsyncScenario.ACCEPTED_THEN_DOWNSTREAM_FAILURE) {
            lifecycleStore.responseSent(handle, 202);
            try {
                processingExecutor.execute(() -> {
                    lifecycleStore.processingStarted(handle);
                    lifecycleStore.processingFailed(handle, "DOWNSTREAM_FAILED");
                });
            } catch (RejectedExecutionException exception) {
                lifecycleStore.processingFailed(handle, "DOWNSTREAM_EXECUTOR_SATURATED");
            }
            return fixedResponse(HttpStatus.ACCEPTED, "SYNTHETIC_CALLBACK_ACCEPTED");
        }

        lifecycleStore.processingStarted(handle);
        if (scenario == SyntheticAsyncScenario.CALLBACK_PROCESSING_FAILURE
                || (scenario == SyntheticAsyncScenario.CALLBACK_RETRY && handle.attemptNumber() == 1)) {
            lifecycleStore.processingFailed(handle, "BUSINESS_PROCESSING_FAILED");
            lifecycleStore.responseSent(handle, 500);
            return fixedResponse(HttpStatus.INTERNAL_SERVER_ERROR, "SYNTHETIC_CALLBACK_PROCESSING_FAILED");
        }

        lifecycleStore.processingSucceeded(handle);
        if (scenario == SyntheticAsyncScenario.RESPONSE_TRANSMISSION_FAILURE) {
            lifecycleStore.responseWriteFailed(handle, 200);
            StreamingResponseBody body = output -> {
                throw new IOException("SYNTHETIC_RESPONSE_WRITE_FAILURE");
            };
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        }

        lifecycleStore.responseSent(handle, 200);
        return fixedResponse(HttpStatus.OK, "SYNTHETIC_CALLBACK_PROCESSED");
    }

    private SyntheticCorrelationIdentifiers identifiers(JsonNode callback) {
        return new SyntheticCorrelationIdentifiers(
                textOrNull(callback, "applicationId"),
                textOrNull(callback, "loanId"),
                textOrNull(callback, "originalCorrelationId"),
                textOrNull(callback, "partnerReferenceId"),
                textOrNull(callback, "callbackReferenceId"),
                textOrNull(callback, "externalTransactionId"));
    }

    private SyntheticCorrelationIdentifiers emptyIdentifiers() {
        return new SyntheticCorrelationIdentifiers(null, null, null, null, null, null);
    }

    private String payloadCategory(SyntheticAsyncScenario scenario) {
        return switch (scenario) {
            case CALLBACK_PDF_BASE64_5_MB -> "PDF_BASE64";
            case CALLBACK_IMAGE_BASE64 -> "IMAGE_BASE64";
            case CALLBACK_SENSITIVE_PII -> "SENSITIVE_PII";
            case CALLBACK_CREDENTIALS -> "CREDENTIALS";
            default -> "NORMAL_JSON";
        };
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() ? value.intValue() : null;
    }

    private byte[] readBounded(InputStream input) throws IOException, BodyTooLargeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_CALLBACK_BYTES) {
                throw new BodyTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private SyntheticPartner parsePartner(String value) {
        try {
            return SyntheticPartner.fromFixturePath(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean validRunId(String runId) {
        return runId != null && RUN_ID.matcher(runId).matches();
    }

    private ResponseEntity<Map<String, String>> fixedResponse(HttpStatus status, String outcome) {
        return ResponseEntity.status(status).body(Map.of(
                "fixtureClassification", "SYNTHETIC_ONLY",
                "outcome", outcome));
    }

    private static final class BodyTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
