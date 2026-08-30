package com.samsung.sure.partner.observability.autoconfigure.http;

import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationEngine;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.function.client.WebClient;

final class WebClientInstrumentationBeanPostProcessor implements BeanPostProcessor, Ordered {
    private final PartnerObservationEngine engine;

    WebClientInstrumentationBeanPostProcessor(PartnerObservationEngine engine) { this.engine = engine; }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof WebClient webClient) {
            return webClient.mutate().filter(new PartnerWebClientFilter(engine)).build();
        }
        return bean;
    }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
