package klswitch

import "time"

// Wrapper configuration.
//
// The token is the only required option (unless inline rules or a custom
// fetcher is supplied — the dev / advanced escape hatches). It is
// service-scoped + env-scoped at issue time; the backend derives the
// service identity and env from the token, so neither needs to be
// repeated here.
//
// Logging and analytics are opt-in and live entirely outside WASM.

// DefaultRefreshInterval is the polling cadence used when WithToken is set
// and the operator hasn't passed WithRefreshInterval explicitly.
const DefaultRefreshInterval = 30 * time.Second

// config is the assembled, validated configuration. All public-facing
// configuration flows through Option values applied by Init.
type config struct {
	// Identity + auth.
	token      string
	instanceID string

	// Backend URLs.
	baseURL      string
	rulesURL     string
	decisionsURL string
	manifestURL  string

	// Inline ruleset (dev-only escape hatch).
	rules *RuleSetPayload

	// Polling: nil pointer = "default to DefaultRefreshInterval when remote
	// fetching is configured"; 0 = explicitly disable polling; >0 = use that.
	refreshInterval *time.Duration
	timeout         time.Duration
	extraHeaders    map[string]string
	fetcher         RulesFetcher // advanced / tests; bypasses HTTPFetcher

	// Analytics opts; reporter is created only if Sink is non-nil.
	analytics *AnalyticsOptions

	// Manifest reporting — only fires when manifest != nil AND token != "" AND
	// reportManifest is true. reportManifest defaults to true; the operator
	// opts out via WithReportManifest(false).
	manifest       *ManifestSource
	reportManifest bool

	// Opt-in disk cache for the last-known-good ruleset. Empty when
	// caching is off (the default). See WithCachePath.
	cachePath string

	// Override the bundled WASM artifact (advanced / tests).
	customWASM []byte
	// Logger for lifecycle/refresh events. Defaults to silent.
	logger Logger
}

// Option configures Init. Compose multiple options into a single call.
type Option func(*config)

// WithToken supplies the service-scoped bearer token. Required unless an
// inline ruleset (WithRules) or custom fetcher (WithFetcher) is provided.
func WithToken(token string) Option {
	return func(c *config) { c.token = token }
}

// WithInstanceID overrides the auto-derived instance identity. When unset
// the SDK walks KS_INSTANCE_ID, POD_NAME, then derives from hostname+pid+startMs.
// See instance_id.go for the full chain.
func WithInstanceID(id string) Option {
	return func(c *config) { c.instanceID = id }
}

// WithBaseURL sets the KLSwitch backend base URL (default DefaultBaseURL).
// Per-endpoint overrides (WithRulesURL, WithDecisionsURL)
// take precedence when set.
func WithBaseURL(url string) Option {
	return func(c *config) { c.baseURL = url }
}

// WithRules supplies an inline ruleset that is loaded at init (dev-only).
// Bypasses the backend entirely.
func WithRules(rs RuleSetPayload) Option {
	return func(c *config) { c.rules = &rs }
}

// WithRulesURL overrides the rules endpoint URL (advanced / self-hosted).
// Normally derived from WithBaseURL.
func WithRulesURL(url string) Option {
	return func(c *config) { c.rulesURL = url }
}

// WithDecisionsURL overrides the decisions endpoint URL (advanced / self-hosted).
func WithDecisionsURL(url string) Option {
	return func(c *config) { c.decisionsURL = url }
}

// WithManifestURL overrides the manifest endpoint URL (advanced / self-hosted).
func WithManifestURL(url string) Option {
	return func(c *config) { c.manifestURL = url }
}

// WithRefreshInterval enables periodic polling at the given interval. Pass 0
// to explicitly disable polling; the default when remote fetching is
// configured is DefaultRefreshInterval (30s).
func WithRefreshInterval(d time.Duration) Option {
	return func(c *config) {
		v := d
		c.refreshInterval = &v
	}
}

// WithTimeout overrides the per-request timeout used by the default HTTP fetcher.
// Defaults to DefaultRefreshTimeout (5s) when unset.
func WithTimeout(d time.Duration) Option {
	return func(c *config) { c.timeout = d }
}

// WithExtraHeaders adds additional headers sent on every backend call.
// Reserved headers (Authorization, X-Instance-Key, User-Agent) are
// SDK-controlled and cannot be overridden — the SDK warns and ignores attempts.
func WithExtraHeaders(h map[string]string) Option {
	return func(c *config) { c.extraHeaders = h }
}

// WithFetcher replaces the default HTTPFetcher with a custom one (advanced /
// tests). When set, WithRulesURL/WithBaseURL/WithToken/WithTimeout/
// WithExtraHeaders are ignored for the fetcher itself — the fetcher owns its
// own transport and auth.
func WithFetcher(f RulesFetcher) Option {
	return func(c *config) { c.fetcher = f }
}

// WithAnalytics enables wrapper-owned analytics. The sink receives batches of
// AnalyticsEvent outside the evaluate hot path; errors are logged. Zero-
// valued fields in opts use sensible defaults (see AnalyticsOptions).
func WithAnalytics(opts AnalyticsOptions) Option {
	return func(c *config) { c.analytics = &opts }
}

// WithManifest plugs in a framework-specific source for API manifest reporting.
// Each adapter ships its own helper (e.g. chiadapter.ManifestSource(router)).
// Requires a token — the manifest POST uses the same auth headers as the rest
// of the SDK. No-op when omitted.
func WithManifest(src ManifestSource) Option {
	return func(c *config) {
		v := src
		c.manifest = &v
	}
}

// WithReportManifest opts out of manifest reporting even when WithManifest is
// supplied. Defaults to true. Useful for environments where the operator
// doesn't want the SDK to talk to the manifest endpoint.
func WithReportManifest(enabled bool) Option {
	return func(c *config) { c.reportManifest = enabled }
}

// WithCachePath opts in to the boot-time disk cache. When set, every
// successful remote fetch persists the accepted ruleset JSON to this
// file path. On Init, if the first fetch fails AND the file exists,
// the SDK loads it and serves traffic while polling keeps retrying
// the backend in the background. Off by default.
//
// Use [DefaultCachePath] for the conventional
// ${tmpdir}/ks-cache-<env>.json location.
func WithCachePath(path string) Option {
	return func(c *config) { c.cachePath = path }
}

// WithCustomWASM replaces the bundled engine with the provided WASM bytes.
// Intended for tests and advanced integrations; production code should rely on
// the bundled artifact so the SDK ships as a single binary.
func WithCustomWASM(wasm []byte) Option {
	return func(c *config) { c.customWASM = wasm }
}

// WithLogger plugs in a structured logger. The default logger is silent.
func WithLogger(l Logger) Option {
	return func(c *config) {
		if l != nil {
			c.logger = l
		}
	}
}

func applyOptions(opts []Option) *config {
	c := &config{logger: noopLogger{}, reportManifest: true}
	for _, opt := range opts {
		if opt != nil {
			opt(c)
		}
	}
	return c
}

// validate checks for option combinations that can't be honoured. Returns nil
// on success.
func (c *config) validate() error {
	// A token is required UNLESS inline rules are supplied (dev) or a
	// custom fetcher is injected (advanced / tests). The default HTTP
	// fetcher needs the token for the Authorization header.
	remoteFetchNeedsToken := c.rules == nil && c.fetcher == nil
	if remoteFetchNeedsToken && c.token == "" {
		return errTokenRequired
	}
	if c.refreshInterval != nil && *c.refreshInterval < 0 {
		return errInvalidRefreshInterval
	}
	if c.timeout < 0 {
		return errInvalidTimeout
	}
	if c.analytics != nil && c.analytics.Sink == nil {
		return errAnalyticsNeedsSink
	}
	return nil
}

// resolvedRefreshInterval returns the actual polling cadence: the explicit
// override if set, otherwise DefaultRefreshInterval. Callers still skip
// recurring polling when this returns 0 (explicit disable).
func (c *config) resolvedRefreshInterval() time.Duration {
	if c.refreshInterval == nil {
		return DefaultRefreshInterval
	}
	return *c.refreshInterval
}
