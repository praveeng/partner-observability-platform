package com.samsung.sure.partner.observability.testapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.samsung.sure.partner.observability.testapp.model.ScenarioSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FixtureScenarioControllerIntegrationTest {

    @Autowired
    private TestRestTemplate controlPlane;

    @Test
    void exposesBoundedSummariesForSynchronousReactiveAndEncryptedScenarios() {
        ScenarioSummary rest = post("/fixture/rest/alpha/success");
        ScenarioSummary reactive = post("/fixture/webclient/beta/normal-json");
        ScenarioSummary encrypted = post("/fixture/encrypted-rest/alpha");

        assertThat(rest.httpStatus()).isEqualTo(200);
        assertThat(rest.partner()).isEqualTo("ALPHA");
        assertThat(rest.responseSha256()).hasSize(64);
        assertThat(reactive.httpStatus()).isEqualTo(200);
        assertThat(reactive.partner()).isEqualTo("BETA");
        assertThat(encrypted.scenario()).isEqualTo("ENCRYPTED_ROUND_TRIP");
        assertThat(encrypted.failureType()).isNull();
    }

    @Test
    void summarizesExpectedConnectionFailureWithoutReturningPayloadData() {
        ScenarioSummary failure = post("/fixture/okhttp/alpha/connection-failure");

        assertThat(failure.httpStatus()).isZero();
        assertThat(failure.responseBytes()).isZero();
        assertThat(failure.responseSha256()).isNull();
        assertThat(failure.failureType()).isNotBlank();
    }

    private ScenarioSummary post(String path) {
        ResponseEntity<ScenarioSummary> response = controlPlane.postForEntity(path, null, ScenarioSummary.class);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }
}
