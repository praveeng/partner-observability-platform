package com.partner.observability.testapp.client;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
public class FixtureHttpClientsConfiguration {

    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);
    static final Duration RESPONSE_TIMEOUT = Duration.ofMillis(750);

    @Bean
    RestTemplate fixtureRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) RESPONSE_TIMEOUT.toMillis());
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(org.springframework.http.client.ClientHttpResponse response) {
                // 4xx/5xx bodies are intentional fixture results, not control-plane failures.
            }
        });
        return restTemplate;
    }

    @Bean
    WebClient fixtureWebClient() {
        return WebClient.builder().build();
    }

    @Bean
    OkHttpClient fixtureOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }
}
