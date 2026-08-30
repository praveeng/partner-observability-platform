package com.samsung.sure.partner.observability.testapp.telemetry;

import com.samsung.sure.partner.observability.autoconfigure.callback.CallbackPartnerKeyResolver;
import com.samsung.sure.partner.observability.autoconfigure.http.OutboundAttemptResolver;
import java.net.URI;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SyntheticTelemetryConfiguration {
    @Bean
    CallbackPartnerKeyResolver syntheticCallbackPartnerKeyResolver() {
        return (request, callbackName) -> Optional.ofNullable(
                (String) request.getAttribute(SyntheticCallbackTrustFilter.TRUSTED_PARTNER_ATTRIBUTE));
    }

    @Bean
    OutboundAttemptResolver syntheticOutboundAttemptResolver() {
        return (apiName, endpoint) -> fixtureAttempt(endpoint);
    }

    private int fixtureAttempt(URI endpoint) {
        String query = endpoint.getRawQuery();
        if (query == null || !query.startsWith("syntheticAttempt=")) return 1;
        try {
            int value = Integer.parseInt(query.substring("syntheticAttempt=".length()));
            return value >= 1 && value <= 10 ? value : 1;
        } catch (NumberFormatException exception) {
            return 1;
        }
    }
}
