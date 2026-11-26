package com.klswitch.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code klswitch.*} keys in the host app's
 * {@code application.properties} / {@code application.yml}. Only
 * {@link #token} is required; everything else falls back to SDK defaults.
 *
 * <pre>
 * klswitch.token=ks_live_...
 * klswitch.base-url=https://api.klswitch.dev   # optional
 * klswitch.refresh-interval-ms=30000             # optional
 * klswitch.timeout-ms=5000                       # optional
 * klswitch.instance-id=${POD_NAME}               # optional
 * klswitch.cache-path=/tmp/ks-cache-prod.json    # optional
 * klswitch.enabled=true                          # default true; set false to disable the starter
 * </pre>
 */
@ConfigurationProperties(prefix = "klswitch")
public class KLSwitchProperties {

    /** Master switch — set to {@code false} to disable the starter even
     *  when {@code klswitch.token} is set. */
    private boolean enabled = true;

    /** Service-scoped bearer token. Required when {@link #enabled}. */
    private String token;

    /** Optional backend base URL. Defaults to the SDK's hosted endpoint. */
    private String baseUrl;

    /** Optional override for the SDK's auto-derived instance identity. */
    private String instanceId;

    /** Optional polling interval (ms). Defaults to 30 000 (30 s); pass 0 to disable polling. */
    private Long refreshIntervalMs;

    /** Per-request timeout (ms) for the default HTTP rules fetcher. */
    private Long timeoutMs;

    /** Opt-in disk cache path for the last-known-good ruleset. */
    private String cachePath;

    /** Cap on request-body bytes buffered for fact extraction. Null uses the servlet default (1 MiB). */
    private Integer maxBodyBytes;

    public boolean isEnabled()            { return enabled; }
    public void setEnabled(boolean v)     { this.enabled = v; }
    public String getToken()              { return token; }
    public void setToken(String v)        { this.token = v; }
    public String getBaseUrl()            { return baseUrl; }
    public void setBaseUrl(String v)      { this.baseUrl = v; }
    public String getInstanceId()         { return instanceId; }
    public void setInstanceId(String v)   { this.instanceId = v; }
    public Long getRefreshIntervalMs()    { return refreshIntervalMs; }
    public void setRefreshIntervalMs(Long v) { this.refreshIntervalMs = v; }
    public Long getTimeoutMs()            { return timeoutMs; }
    public void setTimeoutMs(Long v)      { this.timeoutMs = v; }
    public String getCachePath()          { return cachePath; }
    public void setCachePath(String v)    { this.cachePath = v; }

    public Integer getMaxBodyBytes() { return maxBodyBytes; }

    public void setMaxBodyBytes(Integer maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
}
