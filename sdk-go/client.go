package klswitch

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

// SDKVersion is pinned at build time so the user-agent reflects the
// shipped version.
const SDKVersion = "0.1.0"

// Client is the public KLSwitch SDK. One client owns one WASM engine instance
// and all host-side concerns (polling, analytics, logging). Safe for concurrent
// use from multiple goroutines; engine access is serialised internally.
//
// Lifecycle: [Init] → [Client.Evaluate] / [Client.Refresh] / [Client.GetStatus]
// → [Client.Close].
type Client struct {
	mu     sync.Mutex
	bridge *bridge
	handle uint32
	logger Logger

	// Resolved once at init; immutable for the process's lifetime.
	instanceID InstanceIDResolution
	urls       ResolvedURLs

	activeRulesVersion *int
	// rulesByID indexes the active rules' condition trees for schema-drift
	// Layer 3 lookups in Evaluate. Rebuilt atomically with activeRulesVersion
	// on every successful ruleset swap.
	rulesByID map[string]Rule
	// cachePath is the operator-set disk-cache path for last-known-good
	// rules. Empty when caching is off (the default).
	cachePath string

	// Polling + analytics + manifest; nil when not configured.
	polling       PollingControl
	pollingActive bool
	analytics     AnalyticsControl
	manifest      ManifestControl
	// Notified after every evaluation. Never nil — the no-op default keeps the
	// hot path branch-free.
	observer      EvaluationObserver
	// One-shot guard for the first-evaluation manifest re-check.

	// Last refresh outcome (nil until a refresh happens).
	lastRefreshAt    *int64
	lastRefreshOk    *bool
	lastRefreshError *string

	// Rolling refresh-error counter, stamped on the next analytics event and
	// reset. Lets operators see refresh-side pain through the analytics
	// dashboard as well as via Status. Guarded by c.mu.
	pendingErrorCount int
}

// Init loads the WASM engine, applies the supplied options, loads inline rules
// (if any) plus optionally fetches from the configured rules endpoint, starts
// polling + analytics, and returns a ready-to-use [*Client]. Any failure after
// engine creation calls Close() so the engine + goroutines are released before
// the error propagates.
func Init(ctx context.Context, opts ...Option) (*Client, error) {
	cfg := applyOptions(opts)
	if err := cfg.validate(); err != nil {
		return nil, err
	}

	b, err := newBridge(ctx, cfg.customWASM)
	if err != nil {
		return nil, err
	}

	handle, err := b.CreateEngine(ctx)
	if err != nil {
		_ = b.CloseRuntime(ctx)
		return nil, fmt.Errorf("create engine: %w", err)
	}

	c := &Client{bridge: b, handle: handle, logger: cfg.logger, observer: noopObserver{}}
	var analyticsRecorder AnalyticsRecorder
	c.instanceID = resolveInstanceID(cfg.instanceID, nil, cfg.logger)
	c.urls = resolveURLs(cfg.baseURL, cfg.rulesURL, cfg.decisionsURL, cfg.manifestURL)
	c.cachePath = cfg.cachePath
	cfg.logger.Info("instance identity resolved", map[string]any{
		"instanceKey": c.instanceID.Value,
		"source":      c.instanceID.Source.String(),
	})

	// defer-recover: a panic from any user-supplied callback during init
	// (logger, custom fetcher, analytics sink setup) releases the engine +
	// runtime before re-raising. Same engine-handle-leak class as the Phase 1
	// fix in Node/Java.
	defer func() {
		if r := recover(); r != nil {
			_ = c.Close()
			panic(r)
		}
	}()

	cfg.logger.Info("wasm engine loaded", nil)

	release := func(err error) (*Client, error) {
		_ = c.Close()
		return nil, err
	}

	// 1. Inline rules first.
	if cfg.rules != nil {
		result, err := c.applyRules(ctx, *cfg.rules)
		if err != nil {
			return release(err)
		}
		if !result.Ok {
			detail := "no rules compiled"
			if len(result.Errors) > 0 {
				detail = result.Errors[0].Code + ": " + result.Errors[0].Message
			}
			return release(fmt.Errorf("KLSwitch init: initial rules failed to load (%s)", detail))
		}
	}

	// 2. Set up polling if remote fetching is configured.
	if cfg.fetcher == nil && cfg.token != "" {
		headers := buildHeaders(headerInputs{
			token:        cfg.token,
			instanceKey:  c.instanceID.Value,
			sdkVersion:   SDKVersion,
			extraHeaders: cfg.extraHeaders,
		}, c.logger)
		fetcher := &HTTPFetcher{
			URL:     c.urls.Rules,
			Headers: headers,
			Timeout: cfg.timeout,
		}
		interval := cfg.resolvedRefreshInterval()
		c.polling = newPollingManager(
			fetcher, interval, c.logger,
			c.onRulesFetched, c.onRefreshFailed)
		c.polling.PollOnce(ctx) // initial fetch
		if interval > 0 {
			c.polling.Start(context.Background())
			c.pollingActive = true
		}
	} else if fetcher := buildFetcher(cfg, c); fetcher != nil {
		// Operator-injected fetcher path (advanced / tests).
		interval := cfg.resolvedRefreshInterval()
		c.polling = newPollingManager(
			fetcher,
			interval,
			c.logger,
			c.onRulesFetched,
			c.onRefreshFailed,
		)
		// Initial fetch (blocking) — the only way to get rules when no inline set was supplied.
		c.polling.PollOnce(ctx)
		if interval > 0 {
			// Detach the polling loop from the Init ctx so it survives the
			// caller's request scope; Close cancels it cleanly.
			c.polling.Start(context.Background())
			c.pollingActive = true
		}
	}

	// 3. Boot-time disk-cache fallback: first fetch errored AND operator
	//    opted in to caching AND a previous good ruleset is on disk →
	//    load it so we can serve traffic while polling keeps retrying
	//    the real backend in the background.
	c.mu.Lock()
	hasRules := c.activeRulesVersion != nil
	c.mu.Unlock()
	if !hasRules && cfg.cachePath != "" {
		if cached := readCachedRuleset(cfg.cachePath, c.logger); cached != nil {
			c.logger.Info("ruleset boot-cache hit", map[string]any{"path": cfg.cachePath})
			if _, err := c.applyRules(ctx, *cached); err == nil {
				c.mu.Lock()
				hasRules = c.activeRulesVersion != nil
				c.mu.Unlock()
			}
		}
	}

	// 4. No rules from any source → fail clearly and release everything.
	if !hasRules {
		return release(errors.New(
			"KLSwitch init: no rules available — provide WithRules (dev) or a reachable backend (set WithToken)",
		))
	}

	// 4. Analytics — inside the release scope so a constructor panic / config
	//    issue still releases the engine (mirrors the Phase 1 Node/Java fixes).
	//
	//    When a token is set we build the default HTTP transport (gzipped POST
	//    to /v1/decisions). When AnalyticsOptions.Sink is supplied the reporter
	//    routes batches there and skips HTTP entirely.
	if cfg.analytics != nil || cfg.token != "" {
		var (
			opts AnalyticsOptions
			t    analyticsTransport
		)
		if cfg.analytics != nil {
			opts = *cfg.analytics
		}
		if opts.Sink != nil {
			t = analyticsTransport{
				SDKLang:     "go",
				SDKVersion:  SDKVersion,
				InstanceKey: c.instanceID.Value,
				Sink:        opts.Sink,
			}
		} else if cfg.token != "" {
			headers := buildHeaders(headerInputs{
				token:        cfg.token,
				instanceKey:  c.instanceID.Value,
				sdkVersion:   SDKVersion,
				extraHeaders: cfg.extraHeaders,
			}, c.logger)
			t = analyticsTransport{
				URL:         c.urls.Decisions,
				Headers:     headers,
				SDKLang:     "go",
				SDKVersion:  SDKVersion,
				InstanceKey: c.instanceID.Value,
				PostTimeout: cfg.timeout,
			}
		}
		if t.URL != "" || t.Sink != nil {
			// One object, two roles: the Client gets the lifecycle half, the observer
			// gets the recording half. Keeping the concrete reference here is what lets
			// each depend on only the interface it uses.
			ar := newAnalyticsReporter(opts, t, c.logger)
			analyticsRecorder = ar
			c.mu.Lock()
			c.analytics = ar
			c.mu.Unlock()
		}
	}

	// 5. Manifest reporter — only when a source was supplied AND a token is
	//    set AND the operator hasn't opted out. We need the token for the
	//    /v1/manifest POST headers; the inline-rules dev path skips manifest
	//    reporting entirely.
	if cfg.manifest != nil && cfg.reportManifest && cfg.token != "" {
		manifestHeaders := buildHeaders(headerInputs{
			token:        cfg.token,
			instanceKey:  c.instanceID.Value,
			sdkVersion:   SDKVersion,
			extraHeaders: cfg.extraHeaders,
		}, c.logger)
		mr := NewManifestReporter(ManifestOptions{
			URL:         c.urls.Manifest,
			Headers:     manifestHeaders,
			Source:      *cfg.manifest,
			InstanceKey: c.instanceID.Value,
			Timeout:     cfg.timeout,
			Logger:      c.logger,
		})
		c.mu.Lock()
		c.manifest = mr
		c.mu.Unlock()
		// Fire-and-forget initial push so a slow/unreachable backend can't
		// stall Init. Uses context.Background() because Init's ctx may be
		// scoped to the caller's request.
		go mr.Report(context.Background())
	}

	// Assemble the observers once, now that the reporters exist. Composing here
	// rather than branching inside Evaluate is what keeps the hot path free of nil
	// checks and free of any knowledge of what telemetry is configured.
	c.mu.Lock()
	var observers []EvaluationObserver
	if analyticsRecorder != nil {
		observers = append(observers, analyticsObserver{reporter: analyticsRecorder})
	}
	if c.manifest != nil {
		observers = append(observers, &manifestRecheckObserver{reporter: c.manifest})
	}
	switch len(observers) {
	case 1:
		c.observer = observers[0]
	default:
		if len(observers) > 1 {
			c.observer = compositeObserver{observers: observers}
		}
	}
	c.mu.Unlock()

	c.logger.Info("client initialized", map[string]any{
		"rulesVersion": derefInt(c.activeRulesVersion),
		"instanceKey":  c.instanceID.Value,
		"polling":      c.pollingActive,
		"analytics":    c.analytics != nil,
		"manifest":     c.manifest != nil,
	})
	return c, nil
}

// InstanceID returns the resolved instance identifier. Stable for the
// process's lifetime.
func (c *Client) InstanceID() string {
	return c.instanceID.Value
}

// URLs returns the resolved backend URLs (rules, decisions, manifest).
func (c *Client) URLs() ResolvedURLs {
	return c.urls
}

// SDKVersion returns the SDK version string, surfaced for adapters that
// want to log it.
func (c *Client) SDKVersion() string {
	return SDKVersion
}

// buildFetcher resolves the active rules fetcher. An operator-injected
// WithFetcher takes precedence; otherwise — and only when a token is set
// (which is enforced by validate when no inline rules / fetcher are present) —
// the default HTTPFetcher is constructed with auth + instance-key headers.
func buildFetcher(cfg *config, c *Client) RulesFetcher {
	if cfg.fetcher != nil {
		return cfg.fetcher
	}
	if cfg.token == "" {
		return nil // dev-only inline-rules path
	}
	headers := buildHeaders(headerInputs{
		token:        cfg.token,
		instanceKey:  c.instanceID.Value,
		sdkVersion:   SDKVersion,
		extraHeaders: cfg.extraHeaders,
	}, c.logger)
	return &HTTPFetcher{
		URL:     c.urls.Rules,
		Headers: headers,
		Timeout: cfg.timeout, // zero → DefaultRefreshTimeout inside HTTPFetcher
	}
}

// Evaluate runs a request against the active ruleset. Safe for concurrent use.
// Analytics recording (if enabled) is non-blocking and never affects the
// returned result.
// recheckManifestOnce re-runs manifest discovery a single time, on the first
// evaluation.
//
// The init-time push races route registration. The ordering that causes it is
// not unusual — it is what the recommended wiring forces, because
// chimware.New needs the client, so routes are registered after Init returns:
//
//	ks, _ := ks.Init(ctx, ks.WithManifest(chimanifest.Source(r)))
//	r.Use(chimware.New(r, ks))
//	r.Get("/v1/users/{id}", h)   // <- discovered only if the push lost the race
//
// Go's scheduler usually runs the init goroutine late enough to mask this, but
// "usually" is not a guarantee: forcing the other interleaving reports zero of
// two routes. By the first request every route is registered, so one re-check
// here closes it deterministically.
//
// Cheap: Report hashes the route list and skips the network when nothing has
// changed, so the routes-registered-first case pays only a discovery walk.
// Evaluate a request against the active ruleset.
//
// Evaluates, times the call, and tells the observer. It does not decide what is
// worth reporting, build a decision event, walk condition trees, or push a route
// manifest — all of which it used to do inline. Those are observers now, so this
// method has one reason to change and telemetry can change without touching the
// hot path.
func (c *Client) Evaluate(ctx context.Context, input EvaluationInput) (EvaluationResult, error) {
	c.mu.Lock()
	b, handle, ver := c.bridge, c.handle, c.activeRulesVersion
	rulesIdx, observer := c.rulesByID, c.observer
	c.mu.Unlock()
	if b == nil || handle == 0 {
		return EvaluationResult{}, errors.New("KLSwitch: client is not initialized (or has been closed)")
	}

	start := time.Now()
	// Inject the trusted clock the engine lacks (for effective-window rules).
	// Zero means unset; respect a caller-supplied Now.
	if input.Now == 0 {
		input.Now = start.UnixMilli()
	}
	payload, err := json.Marshal(input)
	if err != nil {
		return EvaluationResult{}, fmt.Errorf("marshal evaluation input: %w", err)
	}
	out, err := b.Evaluate(ctx, handle, string(payload))
	if err != nil {
		return EvaluationResult{}, err
	}
	var result EvaluationResult
	if err := json.Unmarshal([]byte(out), &result); err != nil {
		return EvaluationResult{}, fmt.Errorf("unmarshal evaluation result: %w (raw=%s)", err, out)
	}

	observer.OnEvaluated(EvaluationContext{
		Input:        input,
		Result:       result,
		Elapsed:      time.Since(start),
		RulesVersion: ver,
		RulesByID:    rulesIdx,
	})
	return result, nil
}

// extractAnalyticsRequest pulls the templated request shape out of the
// evaluation input's selector. Falls back to safe defaults for non-API
// selectors so the analytics event always satisfies the console schema.
func extractAnalyticsRequest(input EvaluationInput) AnalyticsReq {
	method := "UNKNOWN"
	path := ""
	if input.Selector.Keys != nil {
		if m, ok := input.Selector.Keys["method"]; ok {
			method = strings.ToUpper(m)
		}
		if p, ok := input.Selector.Keys["path"]; ok {
			path = p
		}
	}
	return AnalyticsReq{Method: method, Path: path}
}

// AnalyticsStatus returns the reporter's status block (or a "disabled" one
// when analytics isn't configured).
func (c *Client) AnalyticsStatus() AnalyticsStatus {
	c.mu.Lock()
	rep := c.analytics
	c.mu.Unlock()
	if rep == nil {
		return disabledAnalyticsStatus()
	}
	return rep.Status()
}

// ManifestStatus returns the manifest reporter's status block (or a "disabled"
// one when no manifest source was configured).
func (c *Client) ManifestStatus() ManifestStatus {
	c.mu.Lock()
	rep := c.manifest
	c.mu.Unlock()
	if rep == nil {
		return disabledManifestStatus()
	}
	return rep.Status()
}

// PollingStatus snapshots the polling loop's state: LastEtag, NextPollAt
// (zero value before the loop is started), and CurrentInterval (server-
// driven when available, otherwise the configured bootstrap interval).
// Returns a zero-value snapshot when polling isn't configured.
func (c *Client) PollingStatus() PollingStatus {
	c.mu.Lock()
	p := c.polling
	c.mu.Unlock()
	if p == nil {
		return PollingStatus{}
	}
	return p.Status()
}

// ReportManifest re-runs framework discovery and pushes the manifest if the
// route set has changed. Backend dedups by hash so calling this on a steady-
// state app is free. Useful after registering new routes at runtime (dev
// hot-reload, dynamic plugins). Returns false when no manifest source is
// configured.
func (c *Client) ReportManifest(ctx context.Context) bool {
	c.mu.Lock()
	rep := c.manifest
	c.mu.Unlock()
	if rep == nil {
		return false
	}
	return rep.Report(ctx)
}

// Refresh forces an immediate rules fetch + reload. Requires a configured
// token or fetcher. Returns nil even if the fetch failed — the active
// ruleset is preserved and the failure is surfaced via [Client.GetStatus]
// (`LastRefreshOk = false`, `LastRefreshError` populated).
func (c *Client) Refresh(ctx context.Context) error {
	c.mu.Lock()
	p, b := c.polling, c.bridge
	c.mu.Unlock()
	if b == nil {
		return errors.New("KLSwitch: client is not initialized (or has been closed)")
	}
	if p == nil {
		return errors.New("KLSwitch refresh: no backend configured (init with WithToken or WithFetcher)")
	}
	p.PollOnce(ctx)
	return nil
}

// GetStatus returns the combined engine + wrapper status. Safe to call after
// [Client.Close] (returns `Ready: false` with an `Initialized: false` engine
// status in that case).
func (c *Client) GetStatus(ctx context.Context) (Status, error) {
	c.mu.Lock()
	b, handle := c.bridge, c.handle
	ready := b != nil && handle != 0
	wrap := Status{
		Ready:            ready,
		Polling:          c.pollingActive,
		LastRefreshAt:    c.lastRefreshAt,
		LastRefreshOk:    c.lastRefreshOk,
		LastRefreshError: c.lastRefreshError,
	}
	c.mu.Unlock()

	if !ready {
		wrap.EngineStatus = EngineStatus{Initialized: false, SchemaVersion: "1.0"}
		return wrap, nil
	}

	out, err := b.GetStatus(ctx, handle)
	if err != nil {
		return Status{}, err
	}
	if err := json.Unmarshal([]byte(out), &wrap.EngineStatus); err != nil {
		return Status{}, fmt.Errorf("unmarshal engine status: %w (raw=%s)", err, out)
	}
	return wrap, nil
}

// Close stops polling + analytics + manifest, destroys the engine
// handle, and tears down the WASM runtime. Idempotent.
func (c *Client) Close() error {
	c.mu.Lock()
	b, handle, p, a, mr := c.bridge, c.handle, c.polling, c.analytics, c.manifest
	c.bridge = nil
	c.handle = 0
	c.polling = nil
	c.pollingActive = false
	c.analytics = nil
	c.manifest = nil
	c.mu.Unlock()

	if p != nil {
		p.Close()
	}
	if mr != nil {
		mr.Stop()
	}
	if a != nil {
		a.Close()
	}
	if b == nil {
		return nil
	}
	ctx := context.Background()
	var firstErr error
	if handle != 0 {
		if err := b.DestroyEngine(ctx, handle); err != nil {
			firstErr = err
		}
		c.logger.Info("client closed", nil)
	}
	if err := b.CloseRuntime(ctx); err != nil && firstErr == nil {
		firstErr = err
	}
	return firstErr
}

// applyRules loads `rs` into the engine, tracking the active version. Engine
// keeps last-known-good on failure (per the host contract). Snapshots
// `bridge`/`handle` under `c.mu` before dispatching so callers from background
// goroutines (the polling manager) can't race with [Client.Close].
func (c *Client) applyRules(ctx context.Context, rs RuleSetPayload) (LoadRulesResult, error) {
	c.mu.Lock()
	b, handle := c.bridge, c.handle
	c.mu.Unlock()
	if b == nil || handle == 0 {
		return LoadRulesResult{}, errors.New("KLSwitch: client is not initialized (or has been closed)")
	}

	payload, err := json.Marshal(rs)
	if err != nil {
		return LoadRulesResult{}, fmt.Errorf("marshal ruleset: %w", err)
	}
	out, err := b.LoadRules(ctx, handle, string(payload))
	if err != nil {
		return LoadRulesResult{}, err
	}
	var result LoadRulesResult
	if err := json.Unmarshal([]byte(out), &result); err != nil {
		return LoadRulesResult{}, fmt.Errorf("unmarshal load result: %w (raw=%s)", err, out)
	}
	if result.Ok {
		// Build the wrapper-side rules index BEFORE publishing the new
		// version — Layer-3 lookups during the same tick must never
		// see version N alongside version N-1's rules map.
		idx := rulesByID(rs.Rules)
		c.mu.Lock()
		v := result.RulesVersion
		c.activeRulesVersion = &v
		c.rulesByID = idx
		c.mu.Unlock()
		c.logger.Info("rules loaded", map[string]any{
			"rulesVersion": result.RulesVersion,
			"ruleCount":    result.CompiledRuleCount,
			"skipped":      result.SkippedRuleCount,
		})
	} else {
		c.logger.Warn("rules load failed; keeping last known good", map[string]any{
			"errors": result.Errors,
		})
	}
	return result, nil
}

// onRulesFetched is invoked by the polling manager on a successful fetch.
// A poll resolving after Close is safely ignored.
func (c *Client) onRulesFetched(ctx context.Context, rs RuleSetPayload) {
	c.mu.Lock()
	closed := c.bridge == nil
	c.mu.Unlock()
	if closed {
		return
	}
	result, err := c.applyRules(ctx, rs)
	now := time.Now().UnixMilli()
	ok := err == nil && result.Ok
	var errStr *string
	if err != nil {
		s := err.Error()
		errStr = &s
	} else if !result.Ok && len(result.Errors) > 0 {
		s := result.Errors[0].Code + ": " + result.Errors[0].Message
		errStr = &s
	}
	c.mu.Lock()
	c.lastRefreshAt = &now
	c.lastRefreshOk = &ok
	c.lastRefreshError = errStr
	cachePath := c.cachePath
	c.mu.Unlock()
	// Persist to the boot cache only when the engine actually accepted the
	// ruleset — we don't want to overwrite a known-good cache with a
	// broken refresh. Best-effort: writeCachedRuleset logs + swallows IO errors.
	if ok && cachePath != "" {
		writeCachedRuleset(cachePath, rs, c.logger)
	}
}

// onRefreshFailed is invoked by the polling manager on a fetch failure.
// Surfaces the error via [Client.GetStatus] so operators see what went wrong
// without grepping logs.
func (c *Client) onRefreshFailed(err error) {
	c.mu.Lock()
	closed := c.bridge == nil
	c.mu.Unlock()
	if closed {
		return
	}
	now := time.Now().UnixMilli()
	ok := false
	s := err.Error()
	c.mu.Lock()
	c.lastRefreshAt = &now
	c.lastRefreshOk = &ok
	c.lastRefreshError = &s
	// Reserved for the future instance-health channel — refresh
	// failures are an instance-operational signal, no longer stamped onto
	// decision events (which are now pure per-evaluation facts).
	c.pendingErrorCount++
	c.mu.Unlock()
}

func derefInt(p *int) any {
	if p == nil {
		return nil
	}
	return *p
}
