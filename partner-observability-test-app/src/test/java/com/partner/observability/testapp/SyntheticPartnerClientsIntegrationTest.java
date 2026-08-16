package com.partner.observability.testapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.partner.observability.testapp.client.OkHttpFixtureClient;
import com.partner.observability.testapp.client.RestTemplateFixtureClient;
import com.partner.observability.testapp.client.WebClientFixtureClient;
import com.partner.observability.testapp.model.ClientExchange;
import com.partner.observability.testapp.model.SyntheticPartner;
import com.partner.observability.testapp.model.SyntheticPartnerRequest;
import com.partner.observability.testapp.model.SyntheticScenario;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SyntheticPartnerClientsIntegrationTest {

    @Autowired
    private RestTemplateFixtureClient restTemplateClient;

    @Autowired
    private WebClientFixtureClient webClient;

    @Autowired
    private OkHttpFixtureClient okHttpClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void allThreeClientsPerformNormalJsonRequestResponse() throws Exception {
        ClientExchange rest = restTemplateClient.exchange(SyntheticScenario.NORMAL_JSON, SyntheticPartner.ALPHA);
        ClientExchange reactive = webClient
                .exchange(SyntheticScenario.NORMAL_JSON, SyntheticPartner.ALPHA)
                .block(Duration.ofSeconds(5));
        ClientExchange okHttp = okHttpClient.exchange(SyntheticScenario.NORMAL_JSON, SyntheticPartner.ALPHA);

        assertNormalSuccess(rest, "rest-template", SyntheticPartner.ALPHA);
        assertNormalSuccess(reactive, "web-client", SyntheticPartner.ALPHA);
        assertNormalSuccess(okHttp, "okhttp", SyntheticPartner.ALPHA);
    }

    @Test
    void exposesSuccessPartnerErrorsSlowResponseAndRetry() throws Exception {
        assertThat(restTemplateClient.exchange(SyntheticScenario.SUCCESS, SyntheticPartner.ALPHA).httpStatus())
                .isEqualTo(200);
        assertThat(restTemplateClient.exchange(SyntheticScenario.PARTNER_4XX, SyntheticPartner.ALPHA).httpStatus())
                .isEqualTo(422);
        assertThat(restTemplateClient.exchange(SyntheticScenario.PARTNER_5XX, SyntheticPartner.ALPHA).httpStatus())
                .isEqualTo(503);
        assertThat(restTemplateClient.exchange(SyntheticScenario.SLOW_RESPONSE, SyntheticPartner.ALPHA).httpStatus())
                .isEqualTo(200);

        ClientExchange retry = restTemplateClient.exchange(SyntheticScenario.RETRY, SyntheticPartner.ALPHA);
        assertThat(retry.httpStatus()).isEqualTo(200);
        assertThat(retry.attempts()).isEqualTo(2);
        assertThat(objectMapper.readTree(retry.responseBody()).path("scenario").asText()).isEqualTo("RETRY");
    }

    @Test
    void exposesTimeoutAndConnectionFailureWithoutExternalNetwork() {
        assertThatThrownBy(() -> restTemplateClient.exchange(SyntheticScenario.TIMEOUT, SyntheticPartner.ALPHA))
                .isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> restTemplateClient.exchange(
                        SyntheticScenario.CONNECTION_FAILURE, SyntheticPartner.ALPHA))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void returnsMalformedAndLargeNormalJson() throws Exception {
        ClientExchange malformed = restTemplateClient.exchange(
                SyntheticScenario.MALFORMED_RESPONSE, SyntheticPartner.ALPHA);
        assertThat(malformed.httpStatus()).isEqualTo(200);
        assertThatThrownBy(() -> objectMapper.readTree(malformed.responseBody()))
                .isInstanceOf(JsonProcessingException.class);

        ClientExchange large = restTemplateClient.exchange(
                SyntheticScenario.LARGE_NORMAL_JSON, SyntheticPartner.BETA);
        JsonNode largeBody = objectMapper.readTree(large.responseBody());
        assertThat(largeBody.path("description").asText().length()).isGreaterThan(40_000);
        assertThat(largeBody.path("partnerLane").asText()).isEqualTo("BETA");
    }

    @Test
    void servesEveryBinaryAndSensitivePayloadScenario() throws Exception {
        List<SyntheticScenario> scenarios = List.of(
                SyntheticScenario.PDF_BASE64_5_MB,
                SyntheticScenario.JPEG_BASE64_8_MB,
                SyntheticScenario.UNKNOWN_LARGE_BASE64,
                SyntheticScenario.BASE64_DOCUMENT_ARRAY,
                SyntheticScenario.NESTED_SENSITIVE,
                SyntheticScenario.CREDENTIALS,
                SyntheticScenario.OTP,
                SyntheticScenario.CARD_DATA,
                SyntheticScenario.RESTRICTED_PII);

        for (SyntheticScenario scenario : scenarios) {
            ClientExchange response = restTemplateClient.exchange(scenario, SyntheticPartner.ALPHA);
            assertThat(response.httpStatus()).as(scenario.name()).isEqualTo(200);
            JsonNode body = objectMapper.readTree(response.responseBody());
            assertThat(body.path("fixtureClassification").asText()).as(scenario.name())
                    .isEqualTo("SYNTHETIC_ONLY");
        }
    }

    @Test
    void keepsSameApplicationIdDistinctAcrossTwoSyntheticPartners() throws Exception {
        String sharedApplicationId = SyntheticPartnerRequest.COLLIDING_APPLICATION_ID;
        ClientExchange alpha = restTemplateClient.exchange(
                SyntheticScenario.SUCCESS, SyntheticPartner.ALPHA, sharedApplicationId);
        ClientExchange beta = restTemplateClient.exchange(
                SyntheticScenario.SUCCESS, SyntheticPartner.BETA, sharedApplicationId);

        JsonNode alphaBody = objectMapper.readTree(alpha.responseBody());
        JsonNode betaBody = objectMapper.readTree(beta.responseBody());
        assertThat(alphaBody.path("applicationId").asText()).isEqualTo(sharedApplicationId);
        assertThat(betaBody.path("applicationId").asText()).isEqualTo(sharedApplicationId);
        assertThat(alphaBody.path("partnerLane").asText()).isEqualTo("ALPHA");
        assertThat(betaBody.path("partnerLane").asText()).isEqualTo("BETA");
    }

    @Test
    void handlesConcurrentRestTemplateTrafficWithBoundedTestExecutor() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                8,
                8,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            List<Future<ClientExchange>> futures = new ArrayList<>();
            for (int index = 0; index < 48; index++) {
                SyntheticPartner partner = index % 2 == 0 ? SyntheticPartner.ALPHA : SyntheticPartner.BETA;
                String applicationId = "SYNTHETIC-CONCURRENT-" + index;
                futures.add(executor.submit(() -> restTemplateClient.exchange(
                        SyntheticScenario.SUCCESS, partner, applicationId)));
            }

            for (int index = 0; index < futures.size(); index++) {
                ClientExchange exchange = futures.get(index).get(5, TimeUnit.SECONDS);
                SyntheticPartner expected = index % 2 == 0 ? SyntheticPartner.ALPHA : SyntheticPartner.BETA;
                JsonNode body = objectMapper.readTree(exchange.responseBody());
                assertThat(body.path("partnerLane").asText()).isEqualTo(expected.name());
                assertThat(body.path("applicationId").asText()).isEqualTo("SYNTHETIC-CONCURRENT-" + index);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void handlesReactiveWebClientConcurrencyWithoutCrossPartnerResults() {
        List<ClientExchange> results = Flux.range(0, 100)
                .flatMap(index -> {
                    SyntheticPartner partner = index % 2 == 0 ? SyntheticPartner.ALPHA : SyntheticPartner.BETA;
                    return webClient.exchange(
                            SyntheticScenario.SUCCESS, partner, "SYNTHETIC-REACTIVE-" + index);
                }, 32)
                .collectList()
                .block(Duration.ofSeconds(15));

        assertThat(results).hasSize(100);
        assertThat(results).allSatisfy(exchange -> {
            try {
                JsonNode body = objectMapper.readTree(exchange.responseBody());
                assertThat(body.path("partnerLane").asText()).isEqualTo(exchange.partner().name());
                assertThat(body.path("applicationId").asText()).isEqualTo(exchange.applicationId());
            } catch (JsonProcessingException exception) {
                throw new AssertionError("SYNTHETIC_RESPONSE_WAS_NOT_JSON", exception);
            }
        });
    }

    private void assertNormalSuccess(
            ClientExchange exchange, String expectedClient, SyntheticPartner expectedPartner) throws Exception {
        assertThat(exchange).isNotNull();
        assertThat(exchange.client()).isEqualTo(expectedClient);
        assertThat(exchange.httpStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(exchange.responseBody());
        assertThat(body.path("partnerLane").asText()).isEqualTo(expectedPartner.name());
        assertThat(body.path("applicationId").asText())
                .isEqualTo(SyntheticPartnerRequest.COLLIDING_APPLICATION_ID);
    }
}
