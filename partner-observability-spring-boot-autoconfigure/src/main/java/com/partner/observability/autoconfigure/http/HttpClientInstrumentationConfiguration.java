package com.partner.observability.autoconfigure.http;

import com.partner.observability.autoconfigure.PartnerObservationEngine;
import java.util.List;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
@Import({
    HttpClientInstrumentationConfiguration.RestTemplateConfiguration.class,
    HttpClientInstrumentationConfiguration.WebClientConfiguration.class,
    HttpClientInstrumentationConfiguration.OkHttpConfiguration.class
})
public class HttpClientInstrumentationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestTemplate.class)
    static class RestTemplateConfiguration {
        @Bean
        BeanPostProcessor partnerObservabilityRestTemplateBeanPostProcessor(
                PartnerObservationEngine engine, ObjectProvider<OutboundAttemptResolver> attemptResolvers) {
            return new RestTemplateInstrumentationBeanPostProcessor(
                    engine, attemptResolvers.orderedStream().toList());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebClient.class)
    static class WebClientConfiguration {
        @Bean
        BeanPostProcessor partnerObservabilityWebClientBeanPostProcessor(PartnerObservationEngine engine) {
            return new WebClientInstrumentationBeanPostProcessor(engine);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OkHttpClient.class)
    static class OkHttpConfiguration {
        @Bean
        BeanPostProcessor partnerObservabilityOkHttpBeanPostProcessor(PartnerObservationEngine engine) {
            return new OkHttpInstrumentationBeanPostProcessor(engine);
        }
    }
}
