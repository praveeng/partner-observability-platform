package com.samsung.sure.partner.observability.autoconfigure;

import com.samsung.sure.partner.observability.core.context.DeploymentEnvironment;
import com.samsung.sure.partner.observability.core.model.ExchangeMode;
import com.samsung.sure.partner.observability.core.model.Outcome;
import com.samsung.sure.partner.observability.core.payload.PayloadFieldPolicy;
import com.samsung.sure.partner.observability.core.payload.PayloadValueType;
import com.samsung.sure.partner.observability.core.policy.PayloadCaptureMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Closed, startup-validated manifest subset used by the Spring integrations. */
@ConfigurationProperties("partner-observability")
public class PartnerObservabilityProperties {

    private boolean enabled;
    private boolean payloadsEnabled;
    private boolean logsEnabled;
    private boolean eventsEnabled = true;
    private boolean explicitObservationsEnabled;
    private boolean metricsEnabled = true;
    private boolean exportEnabled = true;
    private boolean callbacksEnabled = true;
    private boolean localSynthetic;
    private String serviceName = "application";
    private String serviceVersion = "unknown";
    private String market = "local";
    private DeploymentEnvironment environment = DeploymentEnvironment.DEV;
    private String policyVersion = "default-v1";
    private final List<Partner> partners = new ArrayList<>();
    private final List<OutboundApi> outbound = new ArrayList<>();
    private final List<Callback> callbacks = new ArrayList<>();
    private final List<LogSelection> logSelections = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isPayloadsEnabled() { return payloadsEnabled; }
    public void setPayloadsEnabled(boolean value) { payloadsEnabled = value; }
    public boolean isLogsEnabled() { return logsEnabled; }
    public void setLogsEnabled(boolean value) { logsEnabled = value; }
    public boolean isEventsEnabled() { return eventsEnabled; }
    public void setEventsEnabled(boolean value) { eventsEnabled = value; }
    public boolean isExplicitObservationsEnabled() { return explicitObservationsEnabled; }
    public void setExplicitObservationsEnabled(boolean value) { explicitObservationsEnabled = value; }
    public boolean isMetricsEnabled() { return metricsEnabled; }
    public void setMetricsEnabled(boolean value) { metricsEnabled = value; }
    public boolean isExportEnabled() { return exportEnabled; }
    public void setExportEnabled(boolean value) { exportEnabled = value; }
    public boolean isCallbacksEnabled() { return callbacksEnabled; }
    public void setCallbacksEnabled(boolean value) { callbacksEnabled = value; }
    public boolean isLocalSynthetic() { return localSynthetic; }
    public void setLocalSynthetic(boolean value) { localSynthetic = value; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String value) { serviceName = value; }
    public String getServiceVersion() { return serviceVersion; }
    public void setServiceVersion(String value) { serviceVersion = value; }
    public String getMarket() { return market; }
    public void setMarket(String value) { market = value; }
    public DeploymentEnvironment getEnvironment() { return environment; }
    public void setEnvironment(DeploymentEnvironment value) { environment = value; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String value) { policyVersion = value; }
    public List<Partner> getPartners() { return partners; }
    public List<OutboundApi> getOutbound() { return outbound; }
    public List<Callback> getCallbacks() { return callbacks; }
    public List<LogSelection> getLogSelections() { return logSelections; }

    /** An exact safe category mapping for an existing SLF4J statement. */
    public static class LogSelection {
        private String category;
        private String loggerPattern;
        private String marker;
        private String messageTemplate;
        private String minimumLevel = "INFO";
        private String journeyStage = "LOG_EVENT";
        private Outcome outcome = Outcome.UNKNOWN;
        private String errorCode;
        private final List<LogArgument> arguments = new ArrayList<>();

        public String getCategory() { return category; }
        public void setCategory(String value) { category = value; }
        public String getLoggerPattern() { return loggerPattern; }
        public void setLoggerPattern(String value) { loggerPattern = value; }
        public String getMarker() { return marker; }
        public void setMarker(String value) { marker = value; }
        public String getMessageTemplate() { return messageTemplate; }
        public void setMessageTemplate(String value) { messageTemplate = value; }
        public String getMinimumLevel() { return minimumLevel; }
        public void setMinimumLevel(String value) {
            minimumLevel = value == null ? null : value.toUpperCase(Locale.ROOT);
        }
        public String getJourneyStage() { return journeyStage; }
        public void setJourneyStage(String value) { journeyStage = value; }
        public Outcome getOutcome() { return outcome; }
        public void setOutcome(Outcome value) { outcome = value; }
        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String value) { errorCode = value; }
        public List<LogArgument> getArguments() { return arguments; }
    }

    /** One explicitly indexed scalar argument; arbitrary object rendering is never used. */
    public static class LogArgument {
        private int index = -1;
        private String name;
        private PayloadValueType type = PayloadValueType.STRING;
        private PayloadFieldPolicy policy = PayloadFieldPolicy.ALLOW;

        public int getIndex() { return index; }
        public void setIndex(int value) { index = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public PayloadValueType getType() { return type; }
        public void setType(PayloadValueType value) { type = value; }
        public PayloadFieldPolicy getPolicy() { return policy; }
        public void setPolicy(PayloadFieldPolicy value) { policy = value; }
    }


    public static class Partner {
        private String key;
        private String tenantRouteId;
        private String slot;
        private String subjectSource = "configured-integration";

        public String getKey() { return key; }
        public void setKey(String value) { key = value; }
        public String getTenantRouteId() { return tenantRouteId; }
        public void setTenantRouteId(String value) { tenantRouteId = value; }
        public String getSlot() { return slot; }
        public void setSlot(String value) { slot = value; }
        public String getSubjectSource() { return subjectSource; }
        public void setSubjectSource(String value) { subjectSource = value; }
    }

    public static class OutboundApi extends PayloadDefinition {
        private ExchangeMode exchangeMode = ExchangeMode.SYNC;
        private String origin;

        public ExchangeMode getExchangeMode() { return exchangeMode; }
        public void setExchangeMode(ExchangeMode value) { exchangeMode = value; }
        public String getOrigin() { return origin; }
        public void setOrigin(String value) { origin = value; }
    }

    public static class Callback extends PayloadDefinition {
        private boolean processingEventsEnabled;
        private String authenticatedPrincipal;

        public boolean isProcessingEventsEnabled() { return processingEventsEnabled; }
        public void setProcessingEventsEnabled(boolean value) { processingEventsEnabled = value; }
        public String getAuthenticatedPrincipal() { return authenticatedPrincipal; }
        public void setAuthenticatedPrincipal(String value) { authenticatedPrincipal = value; }
    }

    public abstract static class PayloadDefinition {
        private String name;
        private String path;
        private String method = "POST";
        private String partner;
        private String correlationProfile = "default-profile";
        private PayloadCaptureMode captureMode = PayloadCaptureMode.METADATA_ONLY;
        private final List<String> safeFields = new ArrayList<>();
        private final Correlation correlation = new Correlation();

        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public String getPath() { return path; }
        public void setPath(String value) { path = value; }
        public String getMethod() { return method; }
        public void setMethod(String value) { method = value == null ? null : value.toUpperCase(Locale.ROOT); }
        public String getPartner() { return partner; }
        public void setPartner(String value) { partner = value; }
        public String getCorrelationProfile() { return correlationProfile; }
        public void setCorrelationProfile(String value) { correlationProfile = value; }
        public PayloadCaptureMode getCaptureMode() { return captureMode; }
        public void setCaptureMode(PayloadCaptureMode value) { captureMode = value; }
        public List<String> getSafeFields() { return safeFields; }
        public Correlation getCorrelation() { return correlation; }
    }

    public static class Correlation {
        private String applicationIdPath;
        private String loanIdPath;
        private String originalCorrelationIdPath;
        private String partnerReferenceIdPath;
        private String externalTransactionIdPath;
        private String callbackReferenceIdPath;
        private String requestIdPath;

        public String getApplicationIdPath() { return applicationIdPath; }
        public void setApplicationIdPath(String value) { applicationIdPath = value; }
        public String getLoanIdPath() { return loanIdPath; }
        public void setLoanIdPath(String value) { loanIdPath = value; }
        public String getOriginalCorrelationIdPath() { return originalCorrelationIdPath; }
        public void setOriginalCorrelationIdPath(String value) { originalCorrelationIdPath = value; }
        public String getPartnerReferenceIdPath() { return partnerReferenceIdPath; }
        public void setPartnerReferenceIdPath(String value) { partnerReferenceIdPath = value; }
        public String getExternalTransactionIdPath() { return externalTransactionIdPath; }
        public void setExternalTransactionIdPath(String value) { externalTransactionIdPath = value; }
        public String getCallbackReferenceIdPath() { return callbackReferenceIdPath; }
        public void setCallbackReferenceIdPath(String value) { callbackReferenceIdPath = value; }
        public String getRequestIdPath() { return requestIdPath; }
        public void setRequestIdPath(String value) { requestIdPath = value; }
    }
}
