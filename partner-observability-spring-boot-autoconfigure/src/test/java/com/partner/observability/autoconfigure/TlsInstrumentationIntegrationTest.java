package com.partner.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.partner.observability.core.model.OutboundApiResponseRecord;
import com.partner.observability.core.model.TelemetryEnvelope;
import com.partner.observability.core.model.TransportFailureClass;
import com.partner.observability.core.model.TransportSecurity;
import com.partner.observability.core.publish.TelemetryPublisher;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpResponse;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

class TlsInstrumentationIntegrationTest {

    private static final String CERTIFICATE_SENTINEL = "SYNTHETIC_CERTIFICATE_CONTENT_MUST_NOT_APPEAR";
    private static final MediaType JSON = MediaType.get("application/json");

    @Test
    void instrumentationPreservesTlsValidationForEverySupportedClientAndEmitsOnlySafeFailures() throws Exception {
        try (TlsFixture fixture = new TlsFixture()) {
            ScenarioResult disabled = runScenario(false, fixture);
            ScenarioResult enabled = runScenario(true, fixture);

            assertThat(enabled.outcomes()).isEqualTo(disabled.outcomes());
            assertThat(enabled.outcomes()).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                    entry("rest-template/trusted-host", true),
                    entry("web-client/trusted-host", true),
                    entry("ok-http/trusted-host", true),
                    entry("rest-template/untrusted-certificate", false),
                    entry("web-client/untrusted-certificate", false),
                    entry("ok-http/untrusted-certificate", false),
                    entry("rest-template/wrong-host", false),
                    entry("web-client/wrong-host", false),
                    entry("ok-http/wrong-host", false),
                    entry("rest-template/expired-certificate", false),
                    entry("web-client/expired-certificate", false),
                    entry("ok-http/expired-certificate", false)));
            assertThat(disabled.telemetry()).isEmpty();
            assertThat(disabled.transportFailureMetricCount()).isZero();
            assertThat(enabled.transportFailureMetricCount()).isEqualTo(3);
            assertThat(enabled.metricMetadata())
                    .doesNotContain(CERTIFICATE_SENTINEL)
                    .doesNotContain("PRIVATE KEY")
                    .doesNotContain("trust-store-password");

            List<OutboundApiResponseRecord> responses = enabled.telemetry().stream()
                    .map(TelemetryEnvelope::body)
                    .filter(OutboundApiResponseRecord.class::isInstance)
                    .map(OutboundApiResponseRecord.class::cast)
                    .toList();
            assertThat(responses).hasSize(9);
            assertThat(responses).allSatisfy(response ->
                    assertThat(response.transportSecurity()).contains(TransportSecurity.TLS));
            assertThat(responses.stream().filter(response -> response.httpStatus().isEmpty()).toList())
                    .hasSize(6);
            assertThat(responses.stream()
                    .filter(response -> response.apiId().equals("tls-api") && response.httpStatus().isEmpty())
                    .toList())
                    .hasSize(3)
                    .allSatisfy(response -> assertThat(response.transportFailureClass()).isPresent());
            assertThat(responses.stream()
                    .filter(response -> response.apiId().equals("expired-tls-api"))
                    .toList())
                    .hasSize(3)
                    .allSatisfy(response -> {
                        assertThat(response.httpStatus()).isEmpty();
                        assertThat(response.outcome()).isEqualTo(com.partner.observability.core.model.Outcome.TECHNICAL_FAILURE);
                        assertThat(response.errorCode()).hasValueSatisfying(code ->
                                assertThat(code).isIn("timeout", "transport_failure", "tls_certificate_validation"));
                    });
            assertThat(responses.stream()
                    .flatMap(response -> response.transportFailureClass().stream())
                            .toList())
                    .contains(TransportFailureClass.TLS_CERTIFICATE_VALIDATION)
                    .allMatch(failure -> failure.name().startsWith("TLS_"));

            String telemetryText = enabled.telemetry().toString();
            assertThat(telemetryText)
                    .doesNotContain(CERTIFICATE_SENTINEL)
                    .doesNotContain("PRIVATE KEY")
                    .doesNotContain("trust-store-password")
                    .doesNotContain("session secret");
        }
    }

    private ScenarioResult runScenario(boolean enabled, TlsFixture fixture) {
        CapturingPublisher publisher = new CapturingPublisher();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ClientSet originals = fixture.clients();
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PartnerObservabilityAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(TelemetryPublisher.class, () -> publisher)
                .withBean(MeterRegistry.class, () -> meterRegistry)
                .withBean("trustedRestTemplate", RestTemplate.class, () -> originals.trustedRestTemplate)
                .withBean("untrustedRestTemplate", RestTemplate.class, () -> originals.untrustedRestTemplate)
                .withBean("trustedWebClient", WebClient.class, () -> originals.trustedWebClient)
                .withBean("untrustedWebClient", WebClient.class, () -> originals.untrustedWebClient)
                .withBean("trustedOkHttp", OkHttpClient.class, () -> originals.trustedOkHttp)
                .withBean("untrustedOkHttp", OkHttpClient.class, () -> originals.untrustedOkHttp)
                .withBean("expiredRestTemplate", RestTemplate.class, () -> originals.expiredRestTemplate)
                .withBean("expiredWebClient", WebClient.class, () -> originals.expiredWebClient)
                .withBean("expiredOkHttp", OkHttpClient.class, () -> originals.expiredOkHttp)
                .withPropertyValues(properties(enabled, fixture));

        Map<String, Boolean> outcomes = new LinkedHashMap<>();
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RestTemplate trustedRestTemplate = context.getBean("trustedRestTemplate", RestTemplate.class);
            RestTemplate untrustedRestTemplate = context.getBean("untrustedRestTemplate", RestTemplate.class);
            WebClient trustedWebClient = context.getBean("trustedWebClient", WebClient.class);
            WebClient untrustedWebClient = context.getBean("untrustedWebClient", WebClient.class);
            OkHttpClient trustedOkHttp = context.getBean("trustedOkHttp", OkHttpClient.class);
            OkHttpClient untrustedOkHttp = context.getBean("untrustedOkHttp", OkHttpClient.class);
            RestTemplate expiredRestTemplate = context.getBean("expiredRestTemplate", RestTemplate.class);
            WebClient expiredWebClient = context.getBean("expiredWebClient", WebClient.class);
            OkHttpClient expiredOkHttp = context.getBean("expiredOkHttp", OkHttpClient.class);

            assertThat(trustedOkHttp.sslSocketFactory()).isSameAs(originals.trustedOkHttp.sslSocketFactory());
            assertThat(trustedOkHttp.x509TrustManager()).isSameAs(originals.trustedOkHttp.x509TrustManager());
            assertThat(trustedOkHttp.hostnameVerifier()).isSameAs(originals.trustedOkHttp.hostnameVerifier());
            assertThat(trustedOkHttp.certificatePinner()).isEqualTo(originals.trustedOkHttp.certificatePinner());
            assertThat(trustedOkHttp.connectionSpecs()).isEqualTo(originals.trustedOkHttp.connectionSpecs());

            outcomes.put("rest-template/trusted-host", rest(trustedRestTemplate, fixture.trustedUrl()));
            outcomes.put("web-client/trusted-host", web(trustedWebClient, fixture.trustedUrl()));
            outcomes.put("ok-http/trusted-host", okHttp(trustedOkHttp, fixture.trustedUrl()));
            outcomes.put("rest-template/untrusted-certificate", rest(untrustedRestTemplate, fixture.trustedUrl()));
            outcomes.put("web-client/untrusted-certificate", web(untrustedWebClient, fixture.trustedUrl()));
            outcomes.put("ok-http/untrusted-certificate", okHttp(untrustedOkHttp, fixture.trustedUrl()));
            outcomes.put("rest-template/wrong-host", rest(trustedRestTemplate, fixture.wrongHostUrl()));
            outcomes.put("web-client/wrong-host", web(trustedWebClient, fixture.wrongHostUrl()));
            outcomes.put("ok-http/wrong-host", okHttp(trustedOkHttp, fixture.wrongHostUrl()));
            outcomes.put("rest-template/expired-certificate", rest(expiredRestTemplate, fixture.expiredUrl()));
            outcomes.put("web-client/expired-certificate", web(expiredWebClient, fixture.expiredUrl()));
            outcomes.put("ok-http/expired-certificate", okHttp(expiredOkHttp, fixture.expiredUrl()));

            assertThat(originals.trustedRestFactory.invocations()).isGreaterThan(0);
            assertThat(originals.untrustedRestFactory.invocations()).isGreaterThan(0);
            assertThat(originals.trustedWebConnector.invocations()).isGreaterThan(0);
            assertThat(originals.untrustedWebConnector.invocations()).isGreaterThan(0);

            if (enabled) {
                publisher.awaitResponseCount(9);
            }
        });
        double transportFailureMetricCount = meterRegistry
                .find("partner_observability_transport_security_failures_total")
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
        return new ScenarioResult(
                Map.copyOf(outcomes), publisher.snapshot(), transportFailureMetricCount,
                meterRegistry.find("partner_observability_transport_security_failures_total")
                        .meters().toString());
    }

    private boolean rest(RestTemplate client, String url) {
        try {
            return client.postForEntity(url, "{}", String.class).getStatusCodeValue() == 200;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean web(WebClient client, String url) {
        try {
            return client.post()
                            .uri(url)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .bodyValue("{}")
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(Duration.ofSeconds(5))
                    != null;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean okHttp(OkHttpClient client, String url) {
        Request request = new Request.Builder().url(url).post(RequestBody.create("{}", JSON)).build();
        try (Response response = client.newCall(request).execute()) {
            return response.code() == 200;
        } catch (IOException failure) {
            return false;
        }
    }

    private String[] properties(boolean enabled, TlsFixture fixture) {
        return new String[] {
            "partner-observability.enabled=" + enabled,
            "partner-observability.service-name=tls-fixture-service",
            "partner-observability.service-version=1.0",
            "partner-observability.market=synthetic",
            "partner-observability.partners[0].key=partner-a",
            "partner-observability.partners[0].tenant-route-id=tenant-a",
            "partner-observability.partners[0].slot=p001",
            "partner-observability.outbound[0].name=tls-api",
            "partner-observability.outbound[0].origin=" + fixture.origin(),
            "partner-observability.outbound[0].path=/partner/a",
            "partner-observability.outbound[0].partner=partner-a",
            "partner-observability.outbound[0].capture-mode=METADATA_ONLY",
            "partner-observability.outbound[1].name=expired-tls-api",
            "partner-observability.outbound[1].origin=" + fixture.expiredOrigin(),
            "partner-observability.outbound[1].path=/partner/expired",
            "partner-observability.outbound[1].partner=partner-a",
            "partner-observability.outbound[1].capture-mode=METADATA_ONLY"
        };
    }

    private static Map.Entry<String, Boolean> entry(String key, boolean value) {
        return Map.entry(key, value);
    }

    private record ScenarioResult(
            Map<String, Boolean> outcomes,
            List<TelemetryEnvelope<?>> telemetry,
            double transportFailureMetricCount,
            String metricMetadata) {}

    private static final class CapturingPublisher implements TelemetryPublisher {
        private final CopyOnWriteArrayList<TelemetryEnvelope<?>> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(com.partner.observability.core.publish.PublishBatch batch) {
            batch.submissions().forEach(submission -> records.add(submission.envelope()));
        }

        private void awaitResponseCount(int expected) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (responseCount() < expected && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while awaiting synthetic telemetry", failure);
                }
            }
            assertThat(responseCount()).isEqualTo(expected);
        }

        private long responseCount() {
            return records.stream()
                    .map(TelemetryEnvelope::body)
                    .filter(OutboundApiResponseRecord.class::isInstance)
                    .count();
        }

        private List<TelemetryEnvelope<?>> snapshot() {
            return List.copyOf(records);
        }
    }

    private static final class TlsFixture implements AutoCloseable {
        private final MockWebServer server = new MockWebServer();
        private final MockWebServer expiredServer = new MockWebServer();
        private final HandshakeCertificates clientCertificates;
        private final SslContext nettyClientSslContext;
        private final HandshakeCertificates expiredClientCertificates;
        private final SslContext expiredNettyClientSslContext;

        private TlsFixture() throws IOException {
            HeldCertificate certificate = new HeldCertificate.Builder()
                    .commonName(CERTIFICATE_SENTINEL)
                    .addSubjectAlternativeName("localhost")
                    .build();
            HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
                    .heldCertificate(certificate)
                    .build();
            clientCertificates = new HandshakeCertificates.Builder()
                    .addTrustedCertificate(certificate.certificate())
                    .build();
            nettyClientSslContext = SslContextBuilder.forClient()
                    .trustManager(certificate.certificate())
                    .build();
            long now = System.currentTimeMillis();
            HeldCertificate expiredCertificate = new HeldCertificate.Builder()
                    .commonName("SYNTHETIC_EXPIRED_CERTIFICATE")
                    .addSubjectAlternativeName("localhost")
                    .validityInterval(now - Duration.ofDays(2).toMillis(), now - Duration.ofDays(1).toMillis())
                    .build();
            HandshakeCertificates expiredServerCertificates = new HandshakeCertificates.Builder()
                    .heldCertificate(expiredCertificate)
                    .build();
            expiredClientCertificates = new HandshakeCertificates.Builder()
                    .addTrustedCertificate(expiredCertificate.certificate())
                    .build();
            expiredNettyClientSslContext = SslContextBuilder.forClient()
                    .trustManager(expiredCertificate.certificate())
                    .build();
            server.useHttps(serverCertificates.sslSocketFactory(), false);
            expiredServer.useHttps(expiredServerCertificates.sslSocketFactory(), false);
            for (int index = 0; index < 12; index++) {
                server.enqueue(new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"status\":\"SYNTHETIC_OK\"}"));
            }
            server.start();
            expiredServer.start();
        }

        private ClientSet clients() {
            RestTemplate trustedRest = restTemplate(clientCertificates.sslSocketFactory());
            RestTemplate untrustedRest = restTemplate(null);
            TestHttpsRequestFactory trustedRestFactory =
                    (TestHttpsRequestFactory) trustedRest.getRequestFactory();
            TestHttpsRequestFactory untrustedRestFactory =
                    (TestHttpsRequestFactory) untrustedRest.getRequestFactory();
            TrackingClientHttpConnector trustedWebConnector = new TrackingClientHttpConnector(
                    new ReactorClientHttpConnector(HttpClient.create()
                            .secure(specification -> specification.sslContext(nettyClientSslContext))
                            .responseTimeout(Duration.ofSeconds(2))));
            WebClient trustedWeb = WebClient.builder()
                    .clientConnector(trustedWebConnector)
                    .build();
            TrackingClientHttpConnector untrustedWebConnector = new TrackingClientHttpConnector(
                    new ReactorClientHttpConnector(HttpClient.create()
                            .responseTimeout(Duration.ofSeconds(2))));
            WebClient untrustedWeb = WebClient.builder()
                    .clientConnector(untrustedWebConnector)
                    .build();
            OkHttpClient trustedOkHttp = new OkHttpClient.Builder()
                    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager())
                    .callTimeout(Duration.ofSeconds(3))
                    .build();
            OkHttpClient untrustedOkHttp = new OkHttpClient.Builder()
                    .callTimeout(Duration.ofSeconds(3))
                    .build();
            RestTemplate expiredRest = restTemplate(expiredClientCertificates.sslSocketFactory());
            WebClient expiredWeb = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                            .secure(specification -> specification.sslContext(expiredNettyClientSslContext))
                            .responseTimeout(Duration.ofSeconds(2))))
                    .build();
            OkHttpClient expiredOkHttp = new OkHttpClient.Builder()
                    .sslSocketFactory(
                            expiredClientCertificates.sslSocketFactory(),
                            expiredClientCertificates.trustManager())
                    .callTimeout(Duration.ofSeconds(3))
                    .build();
            return new ClientSet(
                    trustedRest, untrustedRest, trustedWeb, untrustedWeb, trustedOkHttp, untrustedOkHttp,
                    expiredRest, expiredWeb, expiredOkHttp,
                    trustedRestFactory, untrustedRestFactory, trustedWebConnector, untrustedWebConnector);
        }

        private RestTemplate restTemplate(SSLSocketFactory socketFactory) {
            TestHttpsRequestFactory requestFactory = new TestHttpsRequestFactory(socketFactory);
            requestFactory.setConnectTimeout(2_000);
            requestFactory.setReadTimeout(2_000);
            return new RestTemplate(requestFactory);
        }

        private String trustedUrl() {
            return server.url("/partner/a").toString();
        }

        private String origin() {
            okhttp3.HttpUrl url = server.url("/");
            return url.scheme() + "://" + url.host() + ":" + url.port();
        }

        private String wrongHostUrl() {
            return server.url("/partner/a").newBuilder().host("127.0.0.1").build().toString();
        }

        private String expiredUrl() {
            return expiredServer.url("/partner/expired").toString();
        }

        private String expiredOrigin() {
            okhttp3.HttpUrl url = expiredServer.url("/");
            return url.scheme() + "://" + url.host() + ":" + url.port();
        }

        @Override
        public void close() throws IOException {
            server.shutdown();
            expiredServer.shutdown();
        }
    }

    private static final class TestHttpsRequestFactory extends SimpleClientHttpRequestFactory {
        private final SSLSocketFactory socketFactory;
        private final AtomicInteger invocations = new AtomicInteger();

        private TestHttpsRequestFactory(SSLSocketFactory socketFactory) {
            this.socketFactory = socketFactory;
        }

        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            invocations.incrementAndGet();
            super.prepareConnection(connection, httpMethod);
            if (socketFactory != null && connection instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(socketFactory);
            }
        }

        private int invocations() {
            return invocations.get();
        }
    }

    private static final class TrackingClientHttpConnector implements ClientHttpConnector {
        private final ClientHttpConnector delegate;
        private final AtomicInteger invocations = new AtomicInteger();

        private TrackingClientHttpConnector(ClientHttpConnector delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<ClientHttpResponse> connect(
                HttpMethod method,
                URI uri,
                Function<? super ClientHttpRequest, Mono<Void>> requestCallback) {
            invocations.incrementAndGet();
            return delegate.connect(method, uri, requestCallback);
        }

        private int invocations() {
            return invocations.get();
        }
    }

    private static final class ClientSet {
        private final RestTemplate trustedRestTemplate;
        private final RestTemplate untrustedRestTemplate;
        private final WebClient trustedWebClient;
        private final WebClient untrustedWebClient;
        private final OkHttpClient trustedOkHttp;
        private final OkHttpClient untrustedOkHttp;
        private final RestTemplate expiredRestTemplate;
        private final WebClient expiredWebClient;
        private final OkHttpClient expiredOkHttp;
        private final TestHttpsRequestFactory trustedRestFactory;
        private final TestHttpsRequestFactory untrustedRestFactory;
        private final TrackingClientHttpConnector trustedWebConnector;
        private final TrackingClientHttpConnector untrustedWebConnector;

        private ClientSet(
                RestTemplate trustedRestTemplate,
                RestTemplate untrustedRestTemplate,
                WebClient trustedWebClient,
                WebClient untrustedWebClient,
                OkHttpClient trustedOkHttp,
                OkHttpClient untrustedOkHttp,
                RestTemplate expiredRestTemplate,
                WebClient expiredWebClient,
                OkHttpClient expiredOkHttp,
                TestHttpsRequestFactory trustedRestFactory,
                TestHttpsRequestFactory untrustedRestFactory,
                TrackingClientHttpConnector trustedWebConnector,
                TrackingClientHttpConnector untrustedWebConnector) {
            this.trustedRestTemplate = trustedRestTemplate;
            this.untrustedRestTemplate = untrustedRestTemplate;
            this.trustedWebClient = trustedWebClient;
            this.untrustedWebClient = untrustedWebClient;
            this.trustedOkHttp = trustedOkHttp;
            this.untrustedOkHttp = untrustedOkHttp;
            this.expiredRestTemplate = expiredRestTemplate;
            this.expiredWebClient = expiredWebClient;
            this.expiredOkHttp = expiredOkHttp;
            this.trustedRestFactory = trustedRestFactory;
            this.untrustedRestFactory = untrustedRestFactory;
            this.trustedWebConnector = trustedWebConnector;
            this.untrustedWebConnector = untrustedWebConnector;
        }
    }
}
