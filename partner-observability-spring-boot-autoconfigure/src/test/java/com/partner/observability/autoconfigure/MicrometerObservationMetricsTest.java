package com.partner.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.partner.observability.core.model.AcknowledgementOutcome;
import com.partner.observability.core.model.DeliveryClassification;
import com.partner.observability.core.model.ExchangeMode;
import com.partner.observability.core.model.HttpResult;
import com.partner.observability.core.model.Outcome;
import com.partner.observability.core.model.ProcessingMode;
import com.partner.observability.core.model.ProcessingPhase;
import com.partner.observability.core.model.StatusClass;
import com.partner.observability.core.model.TransportOutcome;
import com.partner.observability.core.policy.PayloadCaptureMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MicrometerObservationMetricsTest {
    private PrometheusMeterRegistry registry;
    private MicrometerObservationMetrics metrics;
    private ObservationDefinition sync;
    private ObservationDefinition async;
    private ObservationDefinition callback;

    @BeforeEach
    void setUp() {
        PartnerObservabilityProperties properties = properties();
        ConfiguredObservationRegistry definitions = new ConfiguredObservationRegistry(properties);
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new MicrometerObservationMetrics(registry, properties.getServiceName(), definitions);
        sync = definitions.outboundByName("CREDIT_SUBMIT").orElseThrow();
        async = definitions.outboundByName("CREDIT_ASYNC").orElseThrow();
        callback = definitions.callbackDefinitions().get(0);
    }

    @Test
    void recordsOutboundSuccessHttpErrorsTimeoutRetryConnectionFailureAndSlowLatency() {
        complete(sync, 1, Outcome.SUCCESS, StatusClass.TWO_XX, 80, HttpResult.HTTP_2XX);
        complete(sync, 1, Outcome.BUSINESS_REJECTED, StatusClass.FOUR_XX, 120, HttpResult.HTTP_4XX);
        complete(sync, 1, Outcome.TECHNICAL_FAILURE, StatusClass.FIVE_XX, 240, HttpResult.HTTP_5XX);
        complete(sync, 1, Outcome.TECHNICAL_FAILURE, StatusClass.IO_ERROR, 500, HttpResult.TIMEOUT);
        complete(sync, 2, Outcome.TECHNICAL_FAILURE, StatusClass.IO_ERROR, 90, HttpResult.CONNECTION_FAILURE);
        complete(sync, 1, Outcome.SUCCESS, StatusClass.TWO_XX, 1_500, HttpResult.HTTP_2XX);

        assertThat(counter("partner_observability_http_interactions_total",
                "api", "CREDIT_SUBMIT", "result", "http_2xx").count()).isEqualTo(2);
        assertThat(counter("partner_observability_http_interactions_total",
                "api", "CREDIT_SUBMIT", "status_class", "4xx").count()).isEqualTo(1);
        assertThat(counter("partner_observability_http_interactions_total",
                "api", "CREDIT_SUBMIT", "status_class", "5xx").count()).isEqualTo(1);
        assertThat(counter("partner_observability_http_interactions_total",
                "api", "CREDIT_SUBMIT", "result", "timeout").count()).isEqualTo(1);
        assertThat(counter("partner_observability_http_interactions_total",
                "api", "CREDIT_SUBMIT", "result", "connection_failure").count()).isEqualTo(1);
        assertThat(counter("partner_observability_outbound_retries_total",
                "api", "CREDIT_SUBMIT").count()).isEqualTo(1);
        Timer success = timer("partner_observability_http_duration_seconds",
                "api", "CREDIT_SUBMIT", "outcome", "success");
        assertThat(success.count()).isEqualTo(2);
        assertThat(success.max(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isGreaterThanOrEqualTo(1_500);
        assertThat(gauge("partner_observability_http_in_flight", "api", "CREDIT_SUBMIT"))
                .isZero();
    }

    @Test
    void recordsAsyncAcknowledgementOutcomeAndLatency() {
        metrics.outboundStarted(async, 1);
        metrics.outboundCompleted(
                async, Outcome.SUCCESS, StatusClass.TWO_XX, 350,
                AcknowledgementOutcome.ACCEPTED, HttpResult.HTTP_2XX);
        metrics.outboundStarted(async, 1);
        metrics.outboundCompleted(
                async, Outcome.TECHNICAL_FAILURE, StatusClass.IO_ERROR, 2_100,
                AcknowledgementOutcome.NO_ACK_TIMEOUT, HttpResult.TIMEOUT);

        assertThat(counter("partner_observability_async_acknowledgements_total",
                "api", "CREDIT_ASYNC", "ack_outcome", "accepted").count()).isEqualTo(1);
        assertThat(counter("partner_observability_async_acknowledgements_total",
                "api", "CREDIT_ASYNC", "ack_outcome", "no_ack_timeout").count()).isEqualTo(1);
        assertThat(timer("partner_observability_async_acknowledgement_duration_seconds",
                "api", "CREDIT_ASYNC", "ack_outcome", "accepted").totalTime(
                        java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(350);
    }

    @Test
    void recordsCallbackSuccessFailureRejectionRetryDuplicateAndResponseClasses() {
        metrics.callbackStarted(callback);
        metrics.callbackReceived(callback, DeliveryClassification.INITIAL);
        metrics.callbackProcessed(
                callback, Outcome.SUCCESS, 40, ProcessingMode.INLINE, ProcessingPhase.BUSINESS_PROCESSING);
        metrics.callbackResponded(
                callback, Outcome.SUCCESS, StatusClass.TWO_XX, TransportOutcome.WRITE_COMPLETED);

        metrics.callbackReceived(callback, DeliveryClassification.RETRY);
        metrics.callbackReceived(callback, DeliveryClassification.DUPLICATE);
        metrics.callbackProcessed(
                callback, Outcome.TECHNICAL_FAILURE, 70,
                ProcessingMode.BACKGROUND, ProcessingPhase.BUSINESS_PROCESSING);
        metrics.callbackProcessed(
                callback, Outcome.TECHNICAL_FAILURE, 0,
                ProcessingMode.INLINE, ProcessingPhase.VALIDATION);
        metrics.callbackStarted(callback);
        metrics.callbackResponded(
                callback, Outcome.BUSINESS_REJECTED, StatusClass.FOUR_XX, TransportOutcome.WRITE_COMPLETED);
        metrics.callbackStarted(callback);
        metrics.callbackResponded(
                callback, Outcome.TECHNICAL_FAILURE, StatusClass.FIVE_XX, TransportOutcome.WRITE_COMPLETED);
        metrics.callbackDenied("untrusted");

        assertThat(counter("partner_observability_callback_deliveries_total",
                "delivery_class", "initial").count()).isEqualTo(1);
        assertThat(counter("partner_observability_callback_deliveries_total",
                "delivery_class", "retry").count()).isEqualTo(1);
        assertThat(counter("partner_observability_callback_deliveries_total",
                "delivery_class", "duplicate").count()).isEqualTo(1);
        assertThat(counter("partner_observability_callback_processing_total",
                "processing_mode", "inline", "processing_phase", "business_processing",
                "outcome", "success").count()).isEqualTo(1);
        assertThat(counter("partner_observability_callback_processing_total",
                "processing_mode", "background", "processing_phase", "business_processing",
                "outcome", "technical_failure").count()).isEqualTo(1);
        assertThat(counter("partner_observability_callback_processing_total",
                "processing_mode", "inline", "processing_phase", "validation",
                "outcome", "technical_failure").count()).isEqualTo(1);
        assertThat(counter("partner_observability_callback_response_total",
                "status_class", "2xx", "result", "write_completed").count()).isEqualTo(1);
        assertThat(counter("partner_observability_callback_response_total",
                "status_class", "4xx", "result", "write_completed").count()).isEqualTo(1);
        assertThat(counter("partner_observability_callback_response_total",
                "status_class", "5xx", "result", "write_completed").count()).isEqualTo(1);
        assertThat(counter("partner_observability_callback_ingress_denied_total",
                "reason", "untrusted").count()).isEqualTo(1);
        assertThat(timer("partner_observability_callback_processing_duration_seconds",
                "processing_mode", "inline", "outcome", "success").count()).isEqualTo(1);
    }

    @Test
    void highVolumeCallbackUpdatesDoNotCreatePerTransactionSeriesOrBlockHandling() {
        int meterCount = registry.getMeters().size();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int index = 0; index < 100_000; index++) {
                metrics.callbackReceived(callback, index % 10 == 0
                        ? DeliveryClassification.RETRY : DeliveryClassification.INITIAL);
            }
        });
        assertThat(registry.getMeters()).hasSize(meterCount);
        assertThat(counter("partner_observability_callback_deliveries_total",
                "delivery_class", "initial").count()).isEqualTo(90_000);
        Set<String> labels = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getKey)
                .collect(Collectors.toSet());
        assertThat(labels).doesNotContain(
                "applicationId", "loanId", "correlationId", "requestId",
                "partnerReferenceId", "callbackReferenceId");
    }

    @Test
    void prometheusScrapeHasOnlyFixedBucketsAndBoundedTrustedDimensions() {
        complete(sync, 1, Outcome.SUCCESS, StatusClass.TWO_XX, 75, HttpResult.HTTP_2XX);
        String scrape = registry.scrape();

        assertThat(scrape)
                .contains("partner_observability_http_duration_seconds_bucket")
                .contains("le=\"0.05\"")
                .contains("le=\"30.0\"")
                .contains("partner_slot=\"p001\"")
                .contains("interaction_kind=\"sync_outbound\"")
                .contains("direction=\"outbound\"")
                .doesNotContain("applicationId", "loanId", "correlationId", "requestId",
                        "partnerReferenceId", "callbackReferenceId");
        assertThat(metrics.activeSeries()).isEqualTo(389);
        assertThat(scrape.lines()
                .filter(line -> line.startsWith("partner_observability_"))
                .count()).isEqualTo(metrics.activeSeries());
    }

    @Test
    void oversizedManifestFailsStartupInsteadOfCreatingUnboundedMeters() {
        PartnerObservabilityProperties properties = properties();
        for (int index = 0; index < 63; index++) {
            outbound(properties, "EXTRA_SYNC_" + index, "/extra/" + index, ExchangeMode.SYNC);
            callback(properties, "EXTRA_CALLBACK_" + index, "/callback/extra/" + index);
        }
        ConfiguredObservationRegistry definitions = new ConfiguredObservationRegistry(properties);

        assertThatThrownBy(() -> new MicrometerObservationMetrics(
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT), "fixture-service", definitions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active Prometheus series")
                .hasMessageContaining("maximum is 10000");
    }

    private void complete(
            ObservationDefinition definition,
            int attempt,
            Outcome outcome,
            StatusClass status,
            long durationMs,
            HttpResult result) {
        metrics.outboundStarted(definition, attempt);
        metrics.outboundCompleted(definition, outcome, status, durationMs, null, result);
    }

    private Counter counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter();
    }

    private Timer timer(String name, String... tags) {
        return registry.get(name).tags(tags).timer();
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    private static PartnerObservabilityProperties properties() {
        PartnerObservabilityProperties properties = new PartnerObservabilityProperties();
        properties.setEnabled(true);
        properties.setServiceName("fixture-service");
        PartnerObservabilityProperties.Partner partner = new PartnerObservabilityProperties.Partner();
        partner.setKey("PARTNER_A");
        partner.setTenantRouteId("local-p001");
        partner.setSlot("p001");
        properties.getPartners().add(partner);
        outbound(properties, "CREDIT_SUBMIT", "/credit", ExchangeMode.SYNC);
        outbound(properties, "CREDIT_ASYNC", "/credit/async", ExchangeMode.ASYNC_INITIATION);
        callback(properties, "CREDIT_CALLBACK", "/callback/credit");
        return properties;
    }

    private static void outbound(
            PartnerObservabilityProperties properties, String name, String path, ExchangeMode mode) {
        PartnerObservabilityProperties.OutboundApi api = new PartnerObservabilityProperties.OutboundApi();
        configure(api, name, path);
        api.setOrigin("https://partner-a.example");
        api.setExchangeMode(mode);
        properties.getOutbound().add(api);
    }

    private static void callback(PartnerObservabilityProperties properties, String name, String path) {
        PartnerObservabilityProperties.Callback callback = new PartnerObservabilityProperties.Callback();
        configure(callback, name, path);
        callback.setProcessingEventsEnabled(true);
        properties.getCallbacks().add(callback);
    }

    private static void configure(
            PartnerObservabilityProperties.PayloadDefinition definition, String name, String path) {
        definition.setName(name);
        definition.setPath(path);
        definition.setMethod("POST");
        definition.setPartner("PARTNER_A");
        definition.setCaptureMode(PayloadCaptureMode.METADATA_ONLY);
    }
}
