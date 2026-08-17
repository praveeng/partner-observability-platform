package com.partner.observability.autoconfigure.callback;

import com.partner.observability.autoconfigure.ConfiguredObservationRegistry;
import com.partner.observability.autoconfigure.ObservationMetrics;
import com.partner.observability.autoconfigure.PartnerObservationEngine;
import javax.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.web.server.WebFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "partner-observability", name = "callbacks-enabled", havingValue = "true", matchIfMissing = true)
@Import({
    CallbackInstrumentationConfiguration.MvcConfiguration.class,
    CallbackInstrumentationConfiguration.WebFluxConfiguration.class
})
public class CallbackInstrumentationConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "javax.servlet.http.HttpServletRequest")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class MvcConfiguration {
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(CallbackPartnerKeyResolver.class)
        CallbackPartnerKeyResolver configuredPrincipalCallbackPartnerKeyResolver(
                ConfiguredObservationRegistry registry) {
            return (request, callbackName) -> registry.callbackDefinitions().stream()
                    .filter(definition -> definition.name().equals(callbackName))
                    .filter(definition -> definition.authenticatedPrincipal() != null)
                    .filter(definition -> request.getUserPrincipal() != null
                            && definition.authenticatedPrincipal().equals(request.getUserPrincipal().getName()))
                    .map(definition -> definition.partnerContext().canonicalPartnerKey())
                    .findFirst();
        }

        @Bean
        CallbackObservations partnerCallbackObservations() {
            return new CallbackObservations();
        }

        @Bean
        FilterRegistrationBean<PartnerCallbackMvcFilter> partnerCallbackMvcFilter(
                ConfiguredObservationRegistry registry,
                CallbackPartnerKeyResolver resolver,
                PartnerObservationEngine engine,
                ObjectProvider<ObservationMetrics> metrics) {
            FilterRegistrationBean<PartnerCallbackMvcFilter> registration = new FilterRegistrationBean<>();
            registration.setFilter(new PartnerCallbackMvcFilter(
                    registry, resolver, engine, metrics.getIfAvailable(() -> ObservationMetrics.NONE)));
            registration.setName("partnerObservabilityCallbackFilter");
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
            registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebFilter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class WebFluxConfiguration {
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(ReactiveCallbackPartnerKeyResolver.class)
        ReactiveCallbackPartnerKeyResolver configuredReactivePrincipalCallbackPartnerKeyResolver(
                ConfiguredObservationRegistry registry) {
            return (exchange, callbackName) -> exchange.getPrincipal()
                    .map(principal -> registry.callbackDefinitions().stream()
                            .filter(definition -> definition.name().equals(callbackName))
                            .filter(definition -> definition.authenticatedPrincipal() != null
                                    && definition.authenticatedPrincipal().equals(principal.getName()))
                            .map(definition -> definition.partnerContext().canonicalPartnerKey())
                            .findFirst())
                    .defaultIfEmpty(java.util.Optional.empty());
        }

        @Bean
        ReactiveCallbackObservations partnerReactiveCallbackObservations() {
            return new ReactiveCallbackObservations();
        }

        @Bean
        WebFilter partnerCallbackWebFilter(
                ConfiguredObservationRegistry registry,
                ReactiveCallbackPartnerKeyResolver resolver,
                PartnerObservationEngine engine,
                ObjectProvider<ObservationMetrics> metrics) {
            return new PartnerCallbackWebFilter(
                    registry, resolver, engine, metrics.getIfAvailable(() -> ObservationMetrics.NONE));
        }
    }
}
