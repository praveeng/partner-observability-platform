package com.samsung.sure.partner.observability.autoconfigure;

import com.samsung.sure.partner.observability.core.context.DeploymentEnvironment;
import com.samsung.sure.partner.observability.core.context.PartnerContext;
import com.samsung.sure.partner.observability.core.context.PartnerContextResolver;
import com.samsung.sure.partner.observability.core.model.ExchangeMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ConfiguredObservationRegistry {

    private final Map<String, PartnerContext> partners;
    private final List<ObservationDefinition> outbound;
    private final List<ObservationDefinition> callbacks;

    ConfiguredObservationRegistry(PartnerObservabilityProperties properties) {
        Map<String, PartnerContext> contexts = new LinkedHashMap<>();
        for (PartnerObservabilityProperties.Partner partner : properties.getPartners()) {
            PartnerContext context = new ConfigurationResolver(properties, partner)
                    .resolve(ConfigurationSubject.INSTANCE)
                    .orElseThrow();
            contexts.put(partner.getKey(), context);
        }
        partners = Map.copyOf(contexts);
        outbound = definitions(properties.getOutbound(), false);
        callbacks = definitions(properties.getCallbacks(), true);
    }

    public Optional<ObservationDefinition> outbound(String method, URI uri) {
        if (uri == null) {
            return Optional.empty();
        }
        return outbound.stream()
                .filter(definition -> definition.method().equalsIgnoreCase(method)
                        && originMatches(definition.origin(), uri)
                        && routeMatches(definition.path(), uri.getPath()))
                .findFirst();
    }

    private boolean originMatches(URI configured, URI actual) {
        if (actual.getScheme() == null || actual.getHost() == null) {
            return false;
        }
        if (!configured.getScheme().equalsIgnoreCase(actual.getScheme())
                || !configured.getHost().equalsIgnoreCase(actual.getHost())) {
            return false;
        }
        int configuredPort = configured.getPort();
        int actualPort = actual.getPort();
        if (configuredPort >= 0) {
            return effectivePort(configured) == effectivePort(actual);
        }
        if ("http".equalsIgnoreCase(configured.getScheme()) && isLiteralLoopback(configured.getHost())) {
            return true;
        }
        return effectivePort(configured) == effectivePort(actual);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean isLiteralLoopback(String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host);
    }

    public Optional<ObservationDefinition> outboundByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return outbound.stream().filter(definition -> definition.name().equals(name)).findFirst();
    }

    public Optional<ObservationDefinition> callback(String method, String requestPath) {
        return match(callbacks, method, requestPath);
    }

    public Optional<PartnerContext> partner(String canonicalKey) {
        return Optional.ofNullable(partners.get(canonicalKey));
    }

    public List<ObservationDefinition> outboundDefinitions() { return outbound; }
    public List<ObservationDefinition> callbackDefinitions() { return callbacks; }

    private Optional<ObservationDefinition> match(
            List<ObservationDefinition> definitions, String method, String path) {
        if (method == null || path == null) {
            return Optional.empty();
        }
        return definitions.stream()
                .filter(definition -> definition.method().equalsIgnoreCase(method)
                        && routeMatches(definition.path(), path))
                .findFirst();
    }

    private boolean routeMatches(String configured, String actual) {
        if (configured.equals(actual)) {
            return true;
        }
        String[] configuredSegments = configured.split("/", -1);
        String[] actualSegments = actual.split("/", -1);
        if (configuredSegments.length != actualSegments.length) {
            return false;
        }
        for (int index = 0; index < configuredSegments.length; index++) {
            String expected = configuredSegments[index];
            if (!(expected.startsWith("{") && expected.endsWith("}"))
                    && !expected.equals(actualSegments[index])) {
                return false;
            }
        }
        return true;
    }

    private List<ObservationDefinition> definitions(
            List<? extends PartnerObservabilityProperties.PayloadDefinition> source, boolean callback) {
        List<ObservationDefinition> result = new ArrayList<>(source.size());
        for (PartnerObservabilityProperties.PayloadDefinition definition : source) {
            boolean processing = callback && ((PartnerObservabilityProperties.Callback) definition).isProcessingEventsEnabled();
            String authenticatedPrincipal = callback
                    ? ((PartnerObservabilityProperties.Callback) definition).getAuthenticatedPrincipal()
                    : null;
            ExchangeMode exchangeMode = callback
                    ? ExchangeMode.SYNC
                    : ((PartnerObservabilityProperties.OutboundApi) definition).getExchangeMode();
            result.add(new ObservationDefinition(
                    definition.getName(), callback ? null : URI.create(
                            ((PartnerObservabilityProperties.OutboundApi) definition).getOrigin()),
                    definition.getPath(), definition.getMethod(),
                    partners.get(definition.getPartner()), definition.getCorrelationProfile(),
                    definition.getCaptureMode(), definition.getSafeFields(), CorrelationPaths.copyOf(definition.getCorrelation()),
                    exchangeMode, callback, processing, authenticatedPrincipal));
        }
        return List.copyOf(result);
    }

    private enum ConfigurationSubject { INSTANCE }

    private static final class ConfigurationResolver extends PartnerContextResolver<ConfigurationSubject> {
        private final PartnerObservabilityProperties properties;
        private final PartnerObservabilityProperties.Partner partner;

        private ConfigurationResolver(
                PartnerObservabilityProperties properties, PartnerObservabilityProperties.Partner partner) {
            this.properties = properties;
            this.partner = partner;
        }

        @Override
        protected Optional<ResolvedPartner> resolveAuthenticated(ConfigurationSubject subject) {
            DeploymentEnvironment environment = properties.getEnvironment();
            return Optional.of(authenticatedPartner(
                    properties.getMarket(), environment, partner.getKey(), partner.getTenantRouteId(),
                    partner.getSlot(), partner.getSubjectSource()));
        }
    }
}
