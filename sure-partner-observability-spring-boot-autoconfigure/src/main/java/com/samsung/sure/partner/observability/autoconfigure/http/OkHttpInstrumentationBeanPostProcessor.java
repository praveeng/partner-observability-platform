package com.samsung.sure.partner.observability.autoconfigure.http;

import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationEngine;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

final class OkHttpInstrumentationBeanPostProcessor implements BeanPostProcessor, Ordered {
    private final PartnerObservationEngine engine;

    OkHttpInstrumentationBeanPostProcessor(PartnerObservationEngine engine) { this.engine = engine; }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof OkHttpClient client
                && client.interceptors().stream().noneMatch(PartnerOkHttpInterceptor.class::isInstance)) {
            return client.newBuilder().addInterceptor(new PartnerOkHttpInterceptor(engine)).build();
        }
        return bean;
    }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
