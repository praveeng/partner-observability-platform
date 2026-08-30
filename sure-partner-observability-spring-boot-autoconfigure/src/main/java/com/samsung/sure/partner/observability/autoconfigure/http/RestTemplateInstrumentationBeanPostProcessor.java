package com.samsung.sure.partner.observability.autoconfigure.http;

import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationEngine;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestTemplate;

final class RestTemplateInstrumentationBeanPostProcessor implements BeanPostProcessor, Ordered {
    private final PartnerObservationEngine engine;
    private final List<OutboundAttemptResolver> attemptResolvers;

    RestTemplateInstrumentationBeanPostProcessor(
            PartnerObservationEngine engine, List<OutboundAttemptResolver> attemptResolvers) {
        this.engine = engine;
        this.attemptResolvers = List.copyOf(attemptResolvers);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RestTemplate restTemplate) {
            boolean present = restTemplate.getInterceptors().stream()
                    .anyMatch(PartnerRestTemplateInterceptor.class::isInstance);
            if (!present) {
                List<org.springframework.http.client.ClientHttpRequestInterceptor> interceptors =
                        new ArrayList<>(restTemplate.getInterceptors());
                interceptors.add(new PartnerRestTemplateInterceptor(engine, attemptResolvers));
                restTemplate.setInterceptors(interceptors);
            }
        }
        return bean;
    }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
