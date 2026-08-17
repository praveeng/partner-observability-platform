package com.partner.observability.autoconfigure;

import com.partner.observability.core.policy.PayloadCaptureMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class PartnerObservabilityConfigurationValidator {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,62}");
    private static final Pattern SLOT = Pattern.compile("p0(?:0[1-9]|[1-5][0-9]|6[0-4])");
    private static final Pattern TENANT = Pattern.compile("[a-z0-9-]{1,40}");
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

    void validate(PartnerObservabilityProperties properties) {
        token(properties.getServiceName(), "service-name");
        token(properties.getServiceVersion(), "service-version");
        token(properties.getMarket(), "market");
        token(properties.getPolicyVersion(), "policy-version");
        if (properties.getEnvironment() == null) {
            fail("environment is required");
        }
        if (properties.getPartners().size() > 64) {
            fail("at most 64 partners are permitted");
        }
        Set<String> partners = new HashSet<>();
        Set<String> tenants = new HashSet<>();
        Set<String> slots = new HashSet<>();
        for (PartnerObservabilityProperties.Partner partner : properties.getPartners()) {
            token(partner.getKey(), "partner key");
            token(partner.getSubjectSource(), "partner subject-source");
            if (!TENANT.matcher(required(partner.getTenantRouteId(), "partner tenant-route-id")).matches()) {
                fail("partner tenant-route-id is invalid");
            }
            if (!SLOT.matcher(required(partner.getSlot(), "partner slot")).matches()) {
                fail("partner slot is invalid");
            }
            if (!partners.add(partner.getKey()) || !tenants.add(partner.getTenantRouteId()) || !slots.add(partner.getSlot())) {
                fail("partner keys, tenant routes, and slots must be unique");
            }
        }
        validateDefinitions(properties.getOutbound(), partners, "outbound", 64);
        validateDefinitions(properties.getCallbacks(), partners, "callback", 64);
        if (properties.isEnabled() && properties.getOutbound().isEmpty() && properties.getCallbacks().isEmpty()) {
            fail("enabled configuration must define an outbound API or callback");
        }
    }

    private void validateDefinitions(
            List<? extends PartnerObservabilityProperties.PayloadDefinition> definitions,
            Set<String> partners,
            String kind,
            int maximum) {
        if (definitions.size() > maximum) {
            fail("at most " + maximum + " " + kind + " definitions are permitted");
        }
        Set<String> names = new HashSet<>();
        Set<String> routes = new HashSet<>();
        for (PartnerObservabilityProperties.PayloadDefinition definition : definitions) {
            token(definition.getName(), kind + " name");
            token(definition.getCorrelationProfile(), kind + " correlation-profile");
            String path = required(definition.getPath(), kind + " path");
            if (!path.startsWith("/") || path.length() > 256 || path.contains("?") || path.contains("#")) {
                fail(kind + " path is invalid");
            }
            if (!METHODS.contains(required(definition.getMethod(), kind + " method"))) {
                fail(kind + " method is invalid");
            }
            if (!partners.contains(definition.getPartner())) {
                fail(kind + " definition references an unknown partner");
            }
            if (definition.getCaptureMode() == null) {
                fail(kind + " capture-mode is required");
            }
            if (definition.getCaptureMode() == PayloadCaptureMode.FULL_SANITIZED
                    && definition.getSafeFields().isEmpty()) {
                fail("full-sanitized " + kind + " definition requires safe-fields");
            }
            if (definition.getSafeFields().size() > 128) {
                fail(kind + " safe-fields exceed the hard cap");
            }
            if (!names.add(definition.getName()) || !routes.add(definition.getMethod() + " " + path)) {
                fail(kind + " names and method/path routes must be unique");
            }
            validateCorrelation(definition.getCorrelation());
            if (definition instanceof PartnerObservabilityProperties.Callback callback
                    && callback.getAuthenticatedPrincipal() != null
                    && !callback.getAuthenticatedPrincipal().matches("[A-Za-z0-9@._-]{1,128}")) {
                fail("callback authenticated-principal is invalid");
            }
        }
    }

    private void validateCorrelation(PartnerObservabilityProperties.Correlation correlation) {
        for (String path : List.of(
                value(correlation.getApplicationIdPath()), value(correlation.getLoanIdPath()),
                value(correlation.getOriginalCorrelationIdPath()), value(correlation.getPartnerReferenceIdPath()),
                value(correlation.getExternalTransactionIdPath()), value(correlation.getCallbackReferenceIdPath()),
                value(correlation.getRequestIdPath()))) {
            if (!path.isEmpty() && (!path.startsWith("$.") || path.length() > 256 || path.contains("[") || path.contains(".."))) {
                fail("correlation paths must be bounded simple JSON paths beginning with $.");
            }
        }
    }

    private static String value(String value) { return value == null ? "" : value; }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) { fail(name + " is required"); }
        return value;
    }
    private static void token(String value, String name) {
        if (!TOKEN.matcher(required(value, name)).matches()) { fail(name + " is invalid"); }
    }
    private static void fail(String message) { throw new IllegalStateException("partner-observability: " + message); }
}
