package com.samsung.sure.partner.observability.testapp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.sure.partner.observability.testapp.fixture.LocalMockPartnerServer;
import com.samsung.sure.partner.observability.testapp.model.ClientExchange;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartner;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartnerRequest;
import com.samsung.sure.partner.observability.testapp.model.SyntheticScenario;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

@Component
public final class OkHttpFixtureClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final LocalMockPartnerServer mockServer;

    public OkHttpFixtureClient(
            OkHttpClient fixtureOkHttpClient, ObjectMapper objectMapper, LocalMockPartnerServer mockServer) {
        this.okHttpClient = fixtureOkHttpClient;
        this.objectMapper = objectMapper;
        this.mockServer = mockServer;
    }

    public ClientExchange exchange(SyntheticScenario scenario, SyntheticPartner partner) throws IOException {
        return exchange(scenario, partner, SyntheticPartnerRequest.COLLIDING_APPLICATION_ID);
    }

    public ClientExchange exchange(
            SyntheticScenario scenario, SyntheticPartner partner, String applicationId) throws IOException {
        byte[] json = objectMapper.writeValueAsBytes(SyntheticPartnerRequest.standard(partner, applicationId));
        Request request = new Request.Builder()
                .url(mockServer.partnerUri(scenario, partner).toURL())
                .header(LocalMockPartnerServer.PARTNER_HEADER, partner.name())
                .header(LocalMockPartnerServer.SCENARIO_HEADER, scenario.name())
                .header(LocalMockPartnerServer.ATTEMPT_HEADER, "1")
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            return new ClientExchange(
                    "okhttp",
                    scenario,
                    partner,
                    applicationId,
                    1,
                    response.code(),
                    body == null ? "" : body.string());
        }
    }
}
