package com.partner.observability.testapp.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.partner.observability.testapp.model.SyntheticPartner;
import com.partner.observability.testapp.model.SyntheticScenario;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
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

    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final Duration SLOW_RESPONSE_DELAY = Duration.ofMillis(100);
    private static final Duration TIMEOUT_RESPONSE_DELAY = Duration.ofMillis(1500);

    private final ObjectMapper objectMapper;
    private final SyntheticPayloadFixtures fixtures;
    private final AtomicInteger threadSequence = new AtomicInteger();

    private volatile HttpServer server;
    private volatile ThreadPoolExecutor executor;
    private volatile int unavailablePort;

    public LocalMockPartnerServer(ObjectMapper objectMapper, SyntheticPayloadFixtures fixtures) {
        this.objectMapper = objectMapper;
        this.fixtures = fixtures;
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
                    4,
                    8,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    threadFactory,
                    new ThreadPoolExecutor.AbortPolicy());
            created.setExecutor(boundedExecutor);
            created.createContext("/partner", this::handlePartner);
            created.createContext("/encrypted", this::handleEncryptedEcho);
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

    public URI partnerUri(SyntheticScenario scenario) {
        if (scenario == SyntheticScenario.CONNECTION_FAILURE) {
            return URI.create("http://127.0.0.1:" + unavailablePort + "/partner");
        }
        return baseUri().resolve("/partner");
    }

    public URI encryptedUri() {
        return baseUri().resolve("/encrypted");
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
