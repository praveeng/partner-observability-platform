package com.samsung.sure.partner.observability.testapp.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.sure.partner.observability.testapp.async.SyntheticAsyncLifecycleStore;
import com.samsung.sure.partner.observability.testapp.async.SyntheticCallbackAuthenticator;
import com.samsung.sure.partner.observability.testapp.model.SyntheticAsyncAcknowledgement;
import com.samsung.sure.partner.observability.testapp.model.SyntheticAsyncRequest;
import com.samsung.sure.partner.observability.testapp.model.SyntheticAsyncScenario;
import com.samsung.sure.partner.observability.testapp.model.SyntheticCorrelationIdentifiers;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartner;
import com.samsung.sure.partner.observability.testapp.model.SyntheticScenario;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Dedicated loopback HTTP server that behaves like a synthetic external partner. */
@Component
public final class LocalMockPartnerServer implements SmartLifecycle {

    public static final String PARTNER_HEADER = "X-Synthetic-Partner-Lane";
    public static final String SCENARIO_HEADER = "X-Synthetic-Scenario";
    public static final String ATTEMPT_HEADER = "X-Synthetic-Attempt";
    public static final String ASYNC_SCENARIO_HEADER = "X-Synthetic-Async-Scenario";
    public static final String CALLBACK_ROOT_HEADER = "X-Synthetic-Callback-Root";
    public static final String CALLBACK_RUN_HEADER = "X-Synthetic-Run-Id";
    public static final String CALLBACK_SIGNATURE_HEADER = "X-Synthetic-Callback-Signature";

    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final Duration SLOW_RESPONSE_DELAY = Duration.ofMillis(100);
    private static final Duration TIMEOUT_RESPONSE_DELAY = Duration.ofMillis(1500);
    private static final Duration CALLBACK_DELAY = Duration.ofMillis(50);
    private static final Duration CALLBACK_AFTER_TIMEOUT_DELAY = Duration.ofMillis(900);
    private static final String COLLIDING_CALLBACK_REFERENCE =
            "SYNTHETIC-CALLBACK-REFERENCE-COLLISION-0001";

    private final ObjectMapper objectMapper;
    private final SyntheticPayloadFixtures fixtures;
    private final SyntheticCallbackAuthenticator callbackAuthenticator;
    private final SyntheticAsyncLifecycleStore lifecycleStore;
    private final HttpClient callbackClient;
    private final AtomicInteger threadSequence = new AtomicInteger();

    private volatile HttpServer server;
    private volatile ThreadPoolExecutor executor;
    private volatile int unavailablePort;

    public LocalMockPartnerServer(
            ObjectMapper objectMapper,
            SyntheticPayloadFixtures fixtures,
            SyntheticCallbackAuthenticator callbackAuthenticator,
            SyntheticAsyncLifecycleStore lifecycleStore) {
        this.objectMapper = objectMapper;
        this.fixtures = fixtures;
        this.callbackAuthenticator = callbackAuthenticator;
        this.lifecycleStore = lifecycleStore;
        callbackClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        try {
            HttpServer created = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ThreadFactory threadFactory = runnable -> {
                Thread thread = new Thread(runnable, "synthetic-mock-partner-" + threadSequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
            ThreadPoolExecutor boundedExecutor = new ThreadPoolExecutor(
                    8,
                    16,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    threadFactory,
                    new ThreadPoolExecutor.AbortPolicy());
            created.setExecutor(boundedExecutor);
            created.createContext("/partner", this::handlePartner);
            created.createContext("/encrypted", this::handleEncryptedEcho);
            created.createContext("/async", this::handleAsyncInitiation);
            created.start();
            unavailablePort = reserveClosedPort();
            executor = boundedExecutor;
            server = created;
        } catch (IOException exception) {
            throw new IllegalStateException("MOCK_PARTNER_START_FAILED", exception);
        }
    }

    @Override
    public synchronized void stop() {
        HttpServer currentServer = server;
        server = null;
        if (currentServer != null) {
            currentServer.stop(0);
        }
        ThreadPoolExecutor currentExecutor = executor;
        executor = null;
        if (currentExecutor != null) {
            currentExecutor.shutdown();
            try {
                if (!currentExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    currentExecutor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                currentExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    public URI partnerUri(SyntheticScenario scenario, SyntheticPartner partner) {
        return partnerUri(scenario, partner, 1);
    }

    public URI partnerUri(SyntheticScenario scenario, SyntheticPartner partner, int attempt) {
        if (scenario == SyntheticScenario.CONNECTION_FAILURE) {
            return URI.create("http://127.0.0.1:" + unavailablePort + "/partner/"
                    + partner.name().toLowerCase(Locale.ROOT) + "?syntheticAttempt=" + attempt);
        }
        return baseUri().resolve("/partner/" + partner.name().toLowerCase(Locale.ROOT)
                + "?syntheticAttempt=" + attempt);
    }

    public URI encryptedUri(SyntheticPartner partner) {
        return baseUri().resolve(
                "/encrypted/" + partner.name().toLowerCase(Locale.ROOT));
    }

    public URI asyncUri(SyntheticPartner partner) {
        return baseUri().resolve("/async/" + partner.name().toLowerCase(Locale.ROOT));
    }

    public URI baseUri() {
        HttpServer current = server;
        if (current == null) {
            throw new IllegalStateException("MOCK_PARTNER_NOT_RUNNING");
        }
        return URI.create("http://127.0.0.1:" + current.getAddress().getPort());
    }

    private void handlePartner(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                processPartnerRequest(exchange);
            } catch (IllegalArgumentException exception) {
                if (exchange.getResponseCode() == -1) {
                    writeJson(exchange, 400, Map.of("errorCode", "SYNTHETIC_INVALID_FIXTURE_REQUEST"));
                }
            }
        } catch (IOException exception) {
            // Client timeouts and disconnects are expected scenario outcomes; no request data is logged.
        }
    }

    private void processPartnerRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("errorCode", "SYNTHETIC_METHOD_NOT_ALLOWED"));
            return;
        }

        SyntheticPartner partner = parsePartner(exchange);
        SyntheticScenario scenario = parseScenario(exchange);
        byte[] requestBytes = readBounded(exchange.getRequestBody(), MAX_REQUEST_BYTES);
        String applicationId = applicationId(requestBytes);

        if (scenario == SyntheticScenario.TIMEOUT) {
            delay(TIMEOUT_RESPONSE_DELAY);
        } else if (scenario == SyntheticScenario.SLOW_RESPONSE) {
            delay(SLOW_RESPONSE_DELAY);
        }

        switch (scenario) {
            case PARTNER_4XX -> writeJson(exchange, 422, Map.of(
                    "applicationId", applicationId,
                    "partnerLane", partner.name(),
                    "errorCode", "SYNTHETIC_PARTNER_REJECTED"));
            case PARTNER_5XX -> writeJson(exchange, 503, Map.of(
                    "applicationId", applicationId,
                    "partnerLane", partner.name(),
                    "errorCode", "SYNTHETIC_PARTNER_UNAVAILABLE"));
            case RETRY -> handleRetry(exchange, partner, applicationId);
            case MALFORMED_RESPONSE -> writeBytes(
                    exchange, 200, "application/json", "{\"applicationId\":".getBytes(StandardCharsets.UTF_8));
            case LARGE_NORMAL_JSON,
                    PDF_BASE64_5_MB,
                    JPEG_BASE64_8_MB,
                    UNKNOWN_LARGE_BASE64,
                    BASE64_DOCUMENT_ARRAY,
                    NESTED_SENSITIVE,
                    CREDENTIALS,
                    OTP,
                    CARD_DATA,
                    RESTRICTED_PII -> writeJson(exchange, 200, fixtures.payloadFor(scenario, partner));
            default -> writeJson(exchange, 200, normalResponse(partner, applicationId, scenario));
        }
    }

    private void handleEncryptedEcho(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("errorCode", "SYNTHETIC_METHOD_NOT_ALLOWED"));
                return;
            }
            byte[] encrypted = readBounded(exchange.getRequestBody(), MAX_REQUEST_BYTES);
            writeBytes(exchange, 200, "application/octet-stream", encrypted);
        } catch (IOException exception) {
            // The fixture deliberately exposes transport failures without logging wire content.
        }
    }

    private void handleAsyncInitiation(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                processAsyncInitiation(exchange);
            } catch (IllegalArgumentException exception) {
                if (exchange.getResponseCode() == -1) {
                    writeJson(exchange, 400, Map.of("errorCode", "SYNTHETIC_INVALID_ASYNC_REQUEST"));
                }
            }
        } catch (IOException exception) {
            // A timed-out acknowledgement is an expected fixture outcome; no request data is logged.
        }
    }

    private void processAsyncInitiation(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("errorCode", "SYNTHETIC_METHOD_NOT_ALLOWED"));
            return;
        }

        SyntheticPartner headerPartner = parsePartner(exchange);
        SyntheticAsyncScenario headerScenario = SyntheticAsyncScenario.valueOf(
                requiredHeader(exchange, ASYNC_SCENARIO_HEADER));
        URI callbackRoot = callbackRoot(requiredHeader(exchange, CALLBACK_ROOT_HEADER));
        SyntheticAsyncRequest request = objectMapper.readValue(
                readBounded(exchange.getRequestBody(), MAX_REQUEST_BYTES), SyntheticAsyncRequest.class);
        if (request.partner() != headerPartner || request.scenario() != headerScenario) {
            throw new IllegalArgumentException("SYNTHETIC_ASYNC_HEADER_CONFLICT");
        }

        SyntheticAsyncAcknowledgement acknowledgement = acknowledgement(request);
        if (headerScenario == SyntheticAsyncScenario.CALLBACK_AFTER_OUTBOUND_TIMEOUT) {
            submitCallbackPlan(request, acknowledgement, callbackRoot, CALLBACK_AFTER_TIMEOUT_DELAY);
            delay(TIMEOUT_RESPONSE_DELAY);
            writeJson(exchange, 202, acknowledgement);
            return;
        }

        writeJson(exchange, 202, acknowledgement);
        if (headerScenario.sendsCallbacks()) {
            submitCallbackPlan(request, acknowledgement, callbackRoot, CALLBACK_DELAY);
        }
    }

    private SyntheticAsyncAcknowledgement acknowledgement(SyntheticAsyncRequest request) {
        if (request.scenario() == SyntheticAsyncScenario.ACKNOWLEDGEMENT_ONLY) {
            return new SyntheticAsyncAcknowledgement(request.runId(), "ACCEPTED", null, null);
        }
        String suffix = request.runId().substring(request.runId().length() - 12);
        return new SyntheticAsyncAcknowledgement(
                request.runId(),
                "ACCEPTED",
                "SYNTHETIC-PARTNER-REFERENCE-" + suffix,
                "SYNTHETIC-EXTERNAL-TRANSACTION-" + suffix);
    }

    private void submitCallbackPlan(
            SyntheticAsyncRequest request,
            SyntheticAsyncAcknowledgement acknowledgement,
            URI callbackRoot,
            Duration initialDelay) {
        ThreadPoolExecutor current = executor;
        if (current == null) {
            lifecycleStore.mockDelivery(
                    request.runId(), 0, request.partner(), null, "MOCK_SERVER_NOT_RUNNING");
            return;
        }
        try {
            current.execute(() -> executeCallbackPlan(request, acknowledgement, callbackRoot, initialDelay));
        } catch (RejectedExecutionException exception) {
            lifecycleStore.mockDelivery(
                    request.runId(), 0, request.partner(), null, "MOCK_EXECUTOR_SATURATED");
        }
    }

    private void executeCallbackPlan(
            SyntheticAsyncRequest request,
            SyntheticAsyncAcknowledgement acknowledgement,
            URI callbackRoot,
            Duration initialDelay) {
        delay(initialDelay);
        SyntheticAsyncScenario scenario = request.scenario();
        if (scenario == SyntheticAsyncScenario.HIGH_CONCURRENCY_CALLBACKS) {
            for (int index = 1; index <= 32; index++) {
                int delivery = index;
                submitCallbackDelivery(request, acknowledgement, callbackRoot, delivery, delivery);
            }
            return;
        }

        List<Integer> callbackSequences = switch (scenario) {
            case CALLBACK_RETRY, DUPLICATE_CALLBACK -> List.of(1, 1);
            case CALLBACK_OUT_OF_ORDER -> List.of(2, 1);
            case MULTIPLE_CALLBACKS -> List.of(1, 2, 3);
            default -> List.of(1);
        };
        for (int index = 0; index < callbackSequences.size(); index++) {
            deliverCallback(
                    request,
                    acknowledgement,
                    callbackRoot,
                    index + 1,
                    callbackSequences.get(index));
            if (index + 1 < callbackSequences.size()) {
                delay(Duration.ofMillis(40));
            }
        }
    }

    private void submitCallbackDelivery(
            SyntheticAsyncRequest request,
            SyntheticAsyncAcknowledgement acknowledgement,
            URI callbackRoot,
            int deliveryNumber,
            int callbackSequence) {
        ThreadPoolExecutor current = executor;
        if (current == null) {
            return;
        }
        try {
            current.execute(() -> deliverCallback(
                    request, acknowledgement, callbackRoot, deliveryNumber, callbackSequence));
        } catch (RejectedExecutionException exception) {
            lifecycleStore.mockDelivery(
                    request.runId(), deliveryNumber, request.partner(), null, "MOCK_EXECUTOR_SATURATED");
        }
    }

    private void deliverCallback(
            SyntheticAsyncRequest request,
            SyntheticAsyncAcknowledgement acknowledgement,
            URI callbackRoot,
            int deliveryNumber,
            int callbackSequence) {
        SyntheticPartner targetPartner = request.scenario() == SyntheticAsyncScenario.WRONG_PARTNER
                ? request.partner().other()
                : request.partner();
        URI target = callbackRoot.resolve(targetPartner.name().toLowerCase(Locale.ROOT));
        byte[] body = callbackBody(request, acknowledgement, deliveryNumber, callbackSequence);
        String signature = request.scenario() == SyntheticAsyncScenario.AUTHENTICATION_FAILURE
                ? "SYNTHETIC-INVALID-SIGNATURE"
                : callbackAuthenticator.signatureFor(targetPartner);
        HttpRequest.Builder callbackRequest = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header(CALLBACK_RUN_HEADER, request.runId())
                .header(CALLBACK_SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (request.scenario() == SyntheticAsyncScenario.CALLBACK_CREDENTIALS) {
            callbackRequest.header("Authorization", "Bearer SYNTHETIC-CALLBACK-AUTHORIZATION-ONLY");
        }
        try {
            HttpResponse<Void> response = callbackClient.send(
                    callbackRequest.build(), HttpResponse.BodyHandlers.discarding());
            lifecycleStore.mockDelivery(
                    request.runId(), deliveryNumber, targetPartner, response.statusCode(), "HTTP_RESPONSE");
        } catch (IOException exception) {
            lifecycleStore.mockDelivery(
                    request.runId(), deliveryNumber, targetPartner, null, "RESPONSE_TRANSMISSION_FAILED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            lifecycleStore.mockDelivery(
                    request.runId(), deliveryNumber, targetPartner, null, "CALLBACK_INTERRUPTED");
        }
    }

    private byte[] callbackBody(
            SyntheticAsyncRequest request,
            SyntheticAsyncAcknowledgement acknowledgement,
            int deliveryNumber,
            int callbackSequence) {
        if (request.scenario() == SyntheticAsyncScenario.MALFORMED_CALLBACK) {
            return "{\"fixtureClassification\":\"SYNTHETIC_ONLY\",\"applicationId\":"
                    .getBytes(StandardCharsets.UTF_8);
        }

        SyntheticCorrelationIdentifiers initial = request.identifiers();
        String callbackReference = callbackReference(request, deliveryNumber);
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("fixtureClassification", "SYNTHETIC_ONLY");
        callback.put("callbackSequence", callbackSequence);
        switch (request.scenario()) {
            case CALLBACK_WITH_APPLICATION_ID -> callback.put("applicationId", initial.applicationId());
            case CALLBACK_WITH_PARTNER_REFERENCE_ONLY ->
                callback.put("partnerReferenceId", acknowledgement.partnerReferenceId());
            case CALLBACK_WITH_CALLBACK_REFERENCE -> callback.put("callbackReferenceId", callbackReference);
            default -> {
                callback.put("applicationId", initial.applicationId());
                callback.put("loanId", initial.loanId());
                callback.put("originalCorrelationId", initial.originalCorrelationId());
                callback.put("partnerReferenceId", partnerReference(request, acknowledgement));
                callback.put("callbackReferenceId", callbackReference);
                callback.put("externalTransactionId", acknowledgement.externalTransactionId());
            }
        }

        switch (request.scenario()) {
            case CALLBACK_PDF_BASE64_5_MB -> callback.put(
                    "callbackData", fixtures.payloadFor(SyntheticScenario.PDF_BASE64_5_MB, request.partner()));
            case CALLBACK_IMAGE_BASE64 -> callback.put(
                    "callbackData", fixtures.payloadFor(SyntheticScenario.JPEG_BASE64_8_MB, request.partner()));
            case CALLBACK_SENSITIVE_PII -> callback.put(
                    "callbackData", fixtures.payloadFor(SyntheticScenario.RESTRICTED_PII, request.partner()));
            case CALLBACK_CREDENTIALS -> callback.put(
                    "callbackData", fixtures.payloadFor(SyntheticScenario.CREDENTIALS, request.partner()));
            default -> callback.put("outcome", "SYNTHETIC_PARTNER_COMPLETED");
        }
        try {
            return objectMapper.writeValueAsBytes(callback);
        } catch (IOException exception) {
            throw new IllegalStateException("SYNTHETIC_CALLBACK_SERIALIZATION_FAILED", exception);
        }
    }

    private String partnerReference(
            SyntheticAsyncRequest request, SyntheticAsyncAcknowledgement acknowledgement) {
        if (request.scenario() == SyntheticAsyncScenario.UNKNOWN_PARTNER_REFERENCE) {
            return "SYNTHETIC-UNKNOWN-PARTNER-REFERENCE-0001";
        }
        return acknowledgement.partnerReferenceId();
    }

    private String callbackReference(SyntheticAsyncRequest request, int deliveryNumber) {
        if (request.scenario() == SyntheticAsyncScenario.CROSS_PARTNER_CALLBACK_REFERENCE) {
            return COLLIDING_CALLBACK_REFERENCE;
        }
        String suffix = request.runId().substring(request.runId().length() - 12);
        if (request.scenario() == SyntheticAsyncScenario.MULTIPLE_CALLBACKS
                || request.scenario() == SyntheticAsyncScenario.HIGH_CONCURRENCY_CALLBACKS) {
            return "SYNTHETIC-CALLBACK-REFERENCE-" + suffix + "-" + deliveryNumber;
        }
        return "SYNTHETIC-CALLBACK-REFERENCE-" + suffix;
    }

    private URI callbackRoot(String value) {
        URI uri = URI.create(value);
        if (!"http".equals(uri.getScheme())
                || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1
                || !uri.getPath().equals("/fixture/callback/")) {
            throw new IllegalArgumentException("SYNTHETIC_CALLBACK_ROOT_INVALID");
        }
        return uri;
    }

    private void handleRetry(HttpExchange exchange, SyntheticPartner partner, String applicationId) throws IOException {
        int attempt = Integer.parseInt(requiredHeader(exchange, ATTEMPT_HEADER));
        if (attempt < 2) {
            writeJson(exchange, 503, Map.of(
                    "applicationId", applicationId,
                    "partnerLane", partner.name(),
                    "errorCode", "SYNTHETIC_RETRY_REQUIRED"));
            return;
        }
        writeJson(exchange, 200, normalResponse(partner, applicationId, SyntheticScenario.RETRY));
    }

    private Map<String, Object> normalResponse(
            SyntheticPartner partner, String applicationId, SyntheticScenario scenario) {
        return Map.of(
                "fixtureClassification", "SYNTHETIC_ONLY",
                "applicationId", applicationId,
                "partnerLane", partner.name(),
                "scenario", scenario.name(),
                "outcome", "SUCCESS",
                "statusCode", "SYNTHETIC_APPROVED");
    }

    private SyntheticPartner parsePartner(HttpExchange exchange) {
        return SyntheticPartner.valueOf(requiredHeader(exchange, PARTNER_HEADER));
    }

    private SyntheticScenario parseScenario(HttpExchange exchange) {
        return SyntheticScenario.valueOf(requiredHeader(exchange, SCENARIO_HEADER));
    }

    private String requiredHeader(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MISSING_FIXTURE_HEADER");
        }
        return value;
    }

    private String applicationId(byte[] requestBytes) throws IOException {
        JsonNode request = objectMapper.readTree(requestBytes);
        JsonNode value = request.get("applicationId");
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("MISSING_SYNTHETIC_APPLICATION_ID");
        }
        return value.textValue();
    }

    private byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IllegalArgumentException("FIXTURE_REQUEST_TOO_LARGE");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        writeBytes(exchange, status, "application/json", objectMapper.writeValueAsBytes(body));
    }

    private void writeBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Synthetic-Mock", "true");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private void delay(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SYNTHETIC_DELAY_INTERRUPTED", exception);
        }
    }

    private int reserveClosedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
