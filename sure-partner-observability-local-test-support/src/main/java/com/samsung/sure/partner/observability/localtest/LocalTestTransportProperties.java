package com.samsung.sure.partner.observability.localtest;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Closed LOCAL-only route; callers cannot select a tenant per request or telemetry record. */
@ConfigurationProperties("partner-observability.local-test-transport")
public final class LocalTestTransportProperties {
    private boolean enabled;
    private URI endpoint;
    private String username;
    private String password;
    private String fixedPartnerKey;
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration requestTimeout = Duration.ofSeconds(2);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public URI getEndpoint() { return endpoint; }
    public void setEndpoint(URI value) { endpoint = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getPassword() { return password; }
    public void setPassword(String value) { password = value; }
    public String getFixedPartnerKey() { return fixedPartnerKey; }
    public void setFixedPartnerKey(String value) { fixedPartnerKey = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { requestTimeout = value; }
}
