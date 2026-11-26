package com.klswitch;

import java.util.Map;

/**
 * Wrapper configuration.
 *
 * The {@code token} is the only required field. It is service-scoped + env-scoped
 * at issue time; the backend derives the service identity and environment
 * from the token, so neither needs to be repeated here.
 *
 * {@code instanceId} is optional — falls back through {@code KS_INSTANCE_ID},
 * {@code POD_NAME}, and a deterministic {@code hostname-pid-startMs} derivation.
 * See {@link InstanceId}.
 *
 * {@code baseUrl} defaults to the hosted KLSwitch backend; the per-endpoint
 * overrides ({@code rulesUrl}, {@code decisionsUrl}) are
 * escape hatches for self-hosted setups.
 *
 * {@code rules} is a dev-only inline ruleset that bypasses the backend
 * entirely; useful for tests and local examples.
 */
public final class Config {

    /** Default per-request timeout for backend calls. */
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;

    /** Default poll interval when polling is enabled. */
    public static final long DEFAULT_REFRESH_INTERVAL_MS = 30_000L;

    private final String token;
    private final String instanceId;
    private final String baseUrl;
    private final String rulesUrl;
    private final String decisionsUrl;
    private final String manifestUrl;

    private final String rulesJson;        // inline rules (dev-only escape hatch)

    private final Long refreshIntervalMs;
    private final long timeoutMs;
    private final Map<String, String> extraHeaders;
    private final Logger logger;
    private final AnalyticsSink analyticsSink;
    private final int analyticsBatchSize;
    private final long analyticsFlushIntervalMs;
    private final int analyticsMaxQueueSize;
    private final RulesFetcher fetcher;     // advanced / tests; bypasses HTTP
    private final ManifestSource manifest;
    private final boolean reportManifest;
    private final String cachePath;

    private Config(Builder b) {
        this.token = b.token;
        this.instanceId = b.instanceId;
        this.baseUrl = b.baseUrl;
        this.rulesUrl = b.rulesUrl;
        this.decisionsUrl = b.decisionsUrl;
        this.manifestUrl = b.manifestUrl;
        this.rulesJson = b.rulesJson;
        this.refreshIntervalMs = b.refreshIntervalMs;
        this.timeoutMs = b.timeoutMs;
        this.extraHeaders = b.extraHeaders;
        this.logger = b.logger;
        this.analyticsSink = b.analyticsSink;
        this.analyticsBatchSize = b.analyticsBatchSize;
        this.analyticsFlushIntervalMs = b.analyticsFlushIntervalMs;
        this.analyticsMaxQueueSize = b.analyticsMaxQueueSize;
        this.fetcher = b.fetcher;
        this.manifest = b.manifest;
        this.reportManifest = b.reportManifest;
        this.cachePath = b.cachePath;
        validate();
    }

    private void validate() {
        // A token is required UNLESS inline rules are supplied (dev-only) or a
        // custom fetcher is injected (advanced / tests). The default remote
        // fetcher needs the token for the Authorization header.
        boolean remoteFetchNeedsToken = rulesJson == null && fetcher == null;
        if (remoteFetchNeedsToken && (token == null || token.isBlank())) {
            throw new IllegalArgumentException("KLSwitch config: token is required.");
        }
        if (refreshIntervalMs != null && refreshIntervalMs < 0) {
            throw new IllegalArgumentException(
                    "KLSwitch config: refreshIntervalMs must be >= 0 (pass 0 to disable polling).");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("KLSwitch config: timeoutMs must be positive.");
        }
        if (analyticsSink != null && analyticsBatchSize <= 0) {
            throw new IllegalArgumentException("KLSwitch config: analyticsBatchSize must be positive.");
        }
        if (analyticsSink != null && analyticsFlushIntervalMs <= 0) {
            throw new IllegalArgumentException("KLSwitch config: analyticsFlushIntervalMs must be positive.");
        }
    }

    public String token()           { return token; }
    public String instanceId()      { return instanceId; }
    public String baseUrl()         { return baseUrl; }
    public String rulesUrl()        { return rulesUrl; }
    public String decisionsUrl()    { return decisionsUrl; }
    public String manifestUrl()     { return manifestUrl; }
    public String rulesJson()       { return rulesJson; }
    public Long refreshIntervalMs() { return refreshIntervalMs; }
    public long timeoutMs()         { return timeoutMs; }
    public Map<String, String> extraHeaders() { return extraHeaders; }
    public Logger logger()          { return logger; }
    public AnalyticsSink analyticsSink() { return analyticsSink; }
    public int analyticsBatchSize() { return analyticsBatchSize; }
    public long analyticsFlushIntervalMs() { return analyticsFlushIntervalMs; }
    public int analyticsMaxQueueSize() { return analyticsMaxQueueSize; }
    public RulesFetcher fetcher()   { return fetcher; }
    public ManifestSource manifest() { return manifest; }
    public boolean reportManifest() { return reportManifest; }
    public String cachePath() { return cachePath; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String token;
        private String instanceId;
        private String baseUrl;
        private String rulesUrl;
        private String decisionsUrl;
        private String manifestUrl;
        private String rulesJson;
        private Long refreshIntervalMs;
        private long timeoutMs = DEFAULT_TIMEOUT_MS;
        private Map<String, String> extraHeaders;
        private Logger logger = LoggerAdapter.NOOP;
        private AnalyticsSink analyticsSink;
        private int analyticsBatchSize = 50;
        private long analyticsFlushIntervalMs = 5_000;
        private int analyticsMaxQueueSize = 10_000;
        private RulesFetcher fetcher;
        private ManifestSource manifest;
        private boolean reportManifest = true;
        private String cachePath;

        /** Service-scoped bearer token (required unless inline rules supplied). */
        public Builder token(String token) {
            this.token = token;
            return this;
        }

        /** Override the auto-derived instance identity. See {@link InstanceId}. */
        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        /** Base URL of the KLSwitch backend (default {@link UrlBuilder#DEFAULT_BASE_URL}). */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Override the rules endpoint URL (advanced / self-hosted). */
        public Builder rulesUrl(String rulesUrl) {
            this.rulesUrl = rulesUrl;
            return this;
        }

        /** Override the decisions endpoint URL (advanced / self-hosted). */
        public Builder decisionsUrl(String decisionsUrl) {
            this.decisionsUrl = decisionsUrl;
            return this;
        }

        /** Override the manifest endpoint URL (advanced / self-hosted). */
        public Builder manifestUrl(String manifestUrl) {
            this.manifestUrl = manifestUrl;
            return this;
        }

        /** Inline ruleset (dev-only). Bypasses the backend entirely. */
        public Builder rules(String rulesJson) {
            this.rulesJson = rulesJson;
            return this;
        }

        /** Pass 0 to disable polling. Defaults to {@link #DEFAULT_REFRESH_INTERVAL_MS} when remote fetching. */
        public Builder refreshIntervalMs(long refreshIntervalMs) {
            this.refreshIntervalMs = refreshIntervalMs;
            return this;
        }

        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /** Additional headers sent on every backend call. Reserved headers (Authorization, X-Instance-Key, User-Agent) are SDK-controlled. */
        public Builder extraHeaders(Map<String, String> extraHeaders) {
            this.extraHeaders = extraHeaders;
            return this;
        }

        public Builder logger(Logger logger) {
            this.logger = logger;
            return this;
        }

        public Builder analytics(AnalyticsSink sink) {
            this.analyticsSink = sink;
            return this;
        }

        public Builder analyticsBatchSize(int batchSize) {
            this.analyticsBatchSize = batchSize;
            return this;
        }

        public Builder analyticsFlushIntervalMs(long flushIntervalMs) {
            this.analyticsFlushIntervalMs = flushIntervalMs;
            return this;
        }

        public Builder analyticsMaxQueueSize(int maxQueueSize) {
            this.analyticsMaxQueueSize = maxQueueSize;
            return this;
        }

        /** Override the rules fetcher (advanced / tests). Bypasses the HTTP default. */
        public Builder fetcher(RulesFetcher fetcher) {
            this.fetcher = fetcher;
            return this;
        }

        /**
         * Framework-specific source for the API manifest reporter. When set
         * (and a token is configured), the SDK pushes the discovered route
         * list to {@code /v1/manifest} at init and on
         * {@code client.reportManifest()}.
         *
         * <p>Adapters ship helpers that construct one (e.g.
         * {@code JaxRsManifestSource.of(...)}).
         */
        public Builder manifest(ManifestSource manifest) {
            this.manifest = manifest;
            return this;
        }

        /**
         * Opt-out: skip the manifest reporter even when a source is supplied.
         * Defaults to {@code true} (push enabled).
         */
        public Builder reportManifest(boolean reportManifest) {
            this.reportManifest = reportManifest;
            return this;
        }

        /**
         * Opt-in disk cache for the last-known-good ruleset. When set,
         * every successful remote fetch persists the accepted ruleset to
         * this file path. On init, if the first fetch fails AND the file
         * exists, the SDK loads it and serves traffic while polling keeps
         * retrying the backend in the background. Off by default.
         *
         * <p>Use {@link RulesCache#defaultCachePath(String)} for the
         * conventional {@code ${tmpdir}/ks-cache-<env>.json} location.
         */
        public Builder cachePath(String cachePath) {
            this.cachePath = cachePath;
            return this;
        }

        public Config build() {
            return new Config(this);
        }
    }
}
