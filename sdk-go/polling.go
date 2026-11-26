package klswitch

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Default for HTTPFetcher.Timeout when zero (matches Java; closes the Phase 1
// review gap where Node had no default and could hang polling indefinitely).
const DefaultRefreshTimeout = 5 * time.Second

// Cap on the response-body snippet attached to non-2xx fetch errors (matches
// Java's ERROR_BODY_SNIPPET_CHARS and Node's ERROR_BODY_SNIPPET_CHARS).
const errorBodySnippetChars = 4096

// Backoff for poll failures: exponential, doubling each consecutive failure.
// Hard ceiling is MaxBackoffInterval, but the effective cap is
// max(MaxBackoffInterval, currentInterval) so a large server cadence never
// gets retried more aggressively than its success schedule.
const (
	// MaxBackoffInterval bounds the exponential-backoff delay. Per B2
	// acceptance criteria (5 minutes). Exposed so operators can reference
	// the value in alerts / dashboards.
	MaxBackoffInterval = 5 * time.Minute
	maxBackoffExponent = 6 // 2^6 = 64x base before the cap kicks in
)

// Bounds on the server-driven poll cadence (Cache-Control: max-age). Same
// values as the Node / Java wrappers — see sdk-node MIN_POLL_MS / MAX_POLL_MS.
const (
	// MinPollInterval clamps the lower end of the server-driven cadence.
	// Stops a server bug returning `max-age=0` from turning every SDK pod
	// into a tight self-DDoS loop.
	MinPollInterval = 5 * time.Second
	// MaxPollInterval clamps the upper end. Keeps a misconfigured server
	// from silently parking pods for days at a time.
	MaxPollInterval = 1 * time.Hour
)

// RulesFetcher fetches the active ruleset. Injectable so tests can supply
// rules without HTTP (and so non-HTTP transports are pluggable later).
type RulesFetcher interface {
	Fetch(ctx context.Context) (RuleSetPayload, error)
}

// RulesFetcherWithMeta is the v1.1 server-driven polling surface. Fetchers
// that implement it return richer outcomes (304 / HEAD-match / next-poll
// cadence). The polling manager prefers this method when available and
// falls back to plain [RulesFetcher.Fetch] otherwise — operator-injected
// fetchers don't need to opt in.
type RulesFetcherWithMeta interface {
	RulesFetcher
	FetchWithMeta(ctx context.Context) (FetchResult, error)
}

// FetchResult is one poll cycle's outcome. NotModified is true when the
// backend confirmed no change (HEAD ETag match OR 304 conditional GET); in
// that case Payload is the zero value. NextDelay is nil when the response
// carried no Cache-Control max-age; a non-nil zero value means the server
// explicitly said `max-age=0` (the polling manager clamps this to
// MinPollInterval) — distinguishing the two matters so a header-less
// response doesn't accidentally reset the schedule.
type FetchResult struct {
	Payload     RuleSetPayload
	NotModified bool
	ETag        string
	NextDelay   *time.Duration
}

// PollingStatus is the polling manager's observability snapshot. Surfaced
// via Client.GetStatus so operators can see lastEtag / next-poll time
// without grepping logs.
type PollingStatus struct {
	LastEtag        string
	NextPollAt      time.Time     // zero value before the loop is started
	CurrentInterval time.Duration // effective interval (server-driven if available)
}

// HTTPFetcher GETs the ruleset from URL with optional headers and a per-request
// timeout. Used by [WithRulesURL] internally; exported so users can supply a
// pre-configured fetcher via [WithFetcher].
//
// v1.1: implements [RulesFetcherWithMeta] — HEAD-first when an ETag has been
// cached, conditional GET on flip, surfaces Cache-Control: max-age.
type HTTPFetcher struct {
	URL     string
	Headers map[string]string
	Timeout time.Duration // zero → DefaultRefreshTimeout
	Client  *http.Client  // zero → fresh client with Timeout applied

	// Cached ETag from the most recent body-bearing response. Used by
	// FetchWithMeta for HEAD-first version probing.
	lastEtag atomic.Pointer[string]
}

// Fetch is the legacy single-call surface. Always issues a plain GET — the
// ETag / HEAD optimisation lives on [FetchWithMeta]. Kept so operators
// holding a *HTTPFetcher reference get unchanged behaviour.
func (f *HTTPFetcher) Fetch(ctx context.Context) (RuleSetPayload, error) {
	resp, body, err := f.sendGet(ctx, /*conditional=*/ false)
	if err != nil {
		return RuleSetPayload{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return RuleSetPayload{}, statusError(resp.StatusCode, body)
	}
	var rs RuleSetPayload
	if err := json.Unmarshal(body, &rs); err != nil {
		return RuleSetPayload{}, fmt.Errorf("decode rules: %w", err)
	}
	return rs, nil
}

// FetchWithMeta runs the HEAD-then-GET dance. See [RulesFetcherWithMeta].
func (f *HTTPFetcher) FetchWithMeta(ctx context.Context) (FetchResult, error) {
	cached := ""
	if p := f.lastEtag.Load(); p != nil {
		cached = *p
	}

	// Cold start: no cached ETag → straight to GET.
	if cached == "" {
		resp, body, err := f.sendGet(ctx, /*conditional=*/ false)
		if err != nil {
			return FetchResult{}, err
		}
		defer resp.Body.Close()
			if resp.StatusCode >= 400 {
			return FetchResult{}, statusError(resp.StatusCode, body)
		}
		return f.parseBody(resp, body)
	}

	// Warm: HEAD probe first.
	headResp, err := f.sendHead(ctx)
	if err != nil {
		return FetchResult{}, err
	}
	headResp.Body.Close()
	if headResp.StatusCode >= 400 {
		return FetchResult{}, statusError(headResp.StatusCode, nil)
	}
	headEtag := headResp.Header.Get("ETag")
	delay := parseMaxAge(headResp.Header.Get("Cache-Control"))
	if headEtag != "" && headEtag == cached {
		// No change — backend already has our version. Skip the GET.
		return FetchResult{NotModified: true, ETag: cached, NextDelay: delay}, nil
	}

	// ETag flipped (or server omitted it). Do a conditional GET — server
	// may reply 304 if the version flipped back during the HEAD→GET
	// window (race safety).
	resp, body, err := f.sendGet(ctx, /*conditional=*/ true)
	if err != nil {
		return FetchResult{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotModified {
		// Race — backend reverted. Keep the cached ETag, no body to deliver.
		return FetchResult{
			NotModified: true,
			ETag:        cached,
			NextDelay:   parseMaxAge(resp.Header.Get("Cache-Control")),
		}, nil
	}
	if resp.StatusCode >= 400 {
		return FetchResult{}, statusError(resp.StatusCode, body)
	}
	return f.parseBody(resp, body)
}

// ── HTTP helpers ───────────────────────────────────────────────────────────

func (f *HTTPFetcher) sendGet(ctx context.Context, conditional bool) (*http.Response, []byte, error) {
	timeout := f.timeout()
	client := f.httpClient(timeout)
	reqCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, f.URL, nil)
	if err != nil {
		return nil, nil, fmt.Errorf("build rules request: %w", err)
	}
	for k, v := range f.Headers {
		req.Header.Set(k, v)
	}
	if conditional {
		if p := f.lastEtag.Load(); p != nil && *p != "" {
			req.Header.Set("If-None-Match", *p)
		}
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, nil, fmt.Errorf("fetch rules: %w", err)
	}
	// We set `Accept-Encoding: gzip` explicitly (request_headers.go), which
	// disables Go's transport-level transparent decompression — so when the
	// server honored it we must gunzip the response body ourselves. Without
	// this the JSON decode fails on the gzip magic byte (0x1f).
	reader := io.Reader(resp.Body)
	if strings.EqualFold(resp.Header.Get("Content-Encoding"), "gzip") {
		gz, gzErr := gzip.NewReader(resp.Body)
		if gzErr != nil {
			resp.Body.Close()
			return nil, nil, fmt.Errorf("gunzip rules response: %w", gzErr)
		}
		defer gz.Close()
		reader = gz
	}
	// Bounded read (post-decompression) so an oversized body can't OOM the
	// polling goroutine; cap generously since the body might be a real ruleset
	// payload, not an error snippet. 16 MiB matches the practical upper bound.
	body, err := io.ReadAll(io.LimitReader(reader, 16<<20))
	if err != nil {
		resp.Body.Close()
		return nil, nil, fmt.Errorf("read rules response: %w", err)
	}
	return resp, body, nil
}

func (f *HTTPFetcher) sendHead(ctx context.Context) (*http.Response, error) {
	timeout := f.timeout()
	client := f.httpClient(timeout)
	reqCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodHead, f.URL, nil)
	if err != nil {
		return nil, fmt.Errorf("build rules HEAD request: %w", err)
	}
	for k, v := range f.Headers {
		req.Header.Set(k, v)
	}
	return client.Do(req)
}

func (f *HTTPFetcher) parseBody(resp *http.Response, body []byte) (FetchResult, error) {
	var rs RuleSetPayload
	if err := json.Unmarshal(body, &rs); err != nil {
		return FetchResult{}, fmt.Errorf("decode rules: %w", err)
	}
	etag := resp.Header.Get("ETag")
	if etag != "" {
		etagCopy := etag
		f.lastEtag.Store(&etagCopy)
	}
	return FetchResult{
		Payload:   rs,
		ETag:      etag,
		NextDelay: parseMaxAge(resp.Header.Get("Cache-Control")),
	}, nil
}

func (f *HTTPFetcher) timeout() time.Duration {
	if f.Timeout > 0 {
		return f.Timeout
	}
	return DefaultRefreshTimeout
}

func (f *HTTPFetcher) httpClient(timeout time.Duration) *http.Client {
	if f.Client != nil {
		return f.Client
	}
	return &http.Client{Timeout: timeout}
}


// statusError builds the snippet-attached error used for any >= 400 response.
func statusError(status int, body []byte) error {
	if len(body) == 0 {
		return fmt.Errorf("rules API responded with status %d", status)
	}
	snippet := string(body)
	if len(snippet) > errorBodySnippetChars {
		snippet = snippet[:errorBodySnippetChars] + "…"
	}
	return fmt.Errorf("rules API responded with status %d: %s", status, snippet)
}

// parseMaxAge extracts "max-age=N" (seconds) from a Cache-Control header,
// tolerating extra directives like "public, max-age=30, must-revalidate".
// Returns nil when the directive is absent or unparseable so the caller
// can distinguish "server didn't say" from "server explicitly said zero".
func parseMaxAge(headerValue string) *time.Duration {
	if headerValue == "" {
		return nil
	}
	for _, directive := range strings.Split(headerValue, ",") {
		trimmed := strings.TrimSpace(directive)
		if !strings.HasPrefix(strings.ToLower(trimmed), "max-age") {
			continue
		}
		eq := strings.IndexByte(trimmed, '=')
		if eq < 0 {
			continue
		}
		n, err := strconv.ParseInt(strings.TrimSpace(trimmed[eq+1:]), 10, 64)
		if err == nil && n >= 0 {
			d := time.Duration(n) * time.Second
			return &d
		}
	}
	return nil
}

// clampPollInterval brings a server-driven cadence into the [MinPollInterval,
// MaxPollInterval] band. Zero or negative inputs return MinPollInterval.
func clampPollInterval(d time.Duration) time.Duration {
	if d <= 0 {
		return MinPollInterval
	}
	if d < MinPollInterval {
		return MinPollInterval
	}
	if d > MaxPollInterval {
		return MaxPollInterval
	}
	return d
}

// pollingManager runs the periodic refresh goroutine and exposes PollOnce for
// initial fetch + user-triggered refresh. PollOnce is serialised by an internal
// mutex (matching Java's `synchronized pollOnce`) so a manual refresh and the
// recurring timer never overlap.
//
// On consecutive failures, the next-attempt delay grows exponentially up to
// maxPollBackoff so a failing rules API isn't hammered at every refresh.
//
// v1.1: the success-path interval is server-driven via Cache-Control: max-age
// (clamped to [MinPollInterval, MaxPollInterval]). Falls back to the
// configured bootstrap interval when the response omits the header.
type pollingManager struct {
	fetcher  RulesFetcher
	logger   Logger

	onFetched func(ctx context.Context, rs RuleSetPayload)
	onError   func(err error)

	mu                  sync.Mutex
	bootstrapInterval   time.Duration
	currentInterval     time.Duration
	consecutiveFailures int
	lastEtag            string
	nextPollAt          time.Time

	startOnce sync.Once
	cancel    context.CancelFunc
	loopDone  chan struct{}
}

func newPollingManager(
	fetcher RulesFetcher,
	interval time.Duration,
	logger Logger,
	onFetched func(context.Context, RuleSetPayload),
	onError func(error),
) *pollingManager {
	// The bootstrap interval is the operator's explicit choice — don't
	// clamp it. clampPollInterval applies only to server-supplied
	// `Cache-Control: max-age` values, which we don't trust as strongly.
	return &pollingManager{
		fetcher:           fetcher,
		bootstrapInterval: interval,
		currentInterval:   interval,
		logger:            logger,
		onFetched:         onFetched,
		onError:           onError,
	}
}

// PollOnce fetches the ruleset once and dispatches to onFetched/onError. Safe
// to call concurrently (calls are serialised on the fetch; the second caller
// waits its turn rather than racing — same as Java). The callbacks themselves
// run **without** p.mu held, so a callback that re-enters polling (or takes
// any other SDK lock) cannot deadlock against the next PollOnce.
func (p *pollingManager) PollOnce(ctx context.Context) {
	p.mu.Lock()
	var (
		rs            RuleSetPayload
		fetchErr      error
		notModified   bool
		etag          string
		nextDelayHint *time.Duration
	)
	if wm, ok := p.fetcher.(RulesFetcherWithMeta); ok {
		result, err := wm.FetchWithMeta(ctx)
		fetchErr = err
		if err == nil {
			notModified = result.NotModified
			etag = result.ETag
			nextDelayHint = result.NextDelay
			if !notModified {
				rs = result.Payload
			}
		}
	} else {
		rs, fetchErr = p.fetcher.Fetch(ctx)
	}
	if fetchErr != nil {
		p.consecutiveFailures++
	} else {
		p.consecutiveFailures = 0
		if etag != "" {
			p.lastEtag = etag
		}
		if nextDelayHint != nil {
			// `max-age=0` is meaningful (clamp to min); only nil means
			// "server omitted the header — leave schedule alone".
			p.currentInterval = clampPollInterval(*nextDelayHint)
		}
	}
	failures := p.consecutiveFailures
	p.mu.Unlock()

	// Callbacks (and the warn log) intentionally run outside the lock.
	if fetchErr != nil {
		p.logger.Warn("rules refresh failed; keeping last known good",
			map[string]any{"error": fetchErr.Error(), "consecutiveFailures": failures})
		p.onError(fetchErr)
		return
	}
	if notModified {
		// Backend confirmed no change — engine work is a no-op.
		return
	}
	p.onFetched(ctx, rs)
}

// Status returns a snapshot of the polling state. Cheap; safe to call from
// any goroutine.
func (p *pollingManager) Status() PollingStatus {
	p.mu.Lock()
	defer p.mu.Unlock()
	return PollingStatus{
		LastEtag:        p.lastEtag,
		NextPollAt:      p.nextPollAt,
		CurrentInterval: p.currentInterval,
	}
}

// Start launches the background poll loop. Idempotent.
func (p *pollingManager) Start(parentCtx context.Context) {
	p.startOnce.Do(func() {
		ctx, cancel := context.WithCancel(parentCtx)
		p.cancel = cancel
		p.loopDone = make(chan struct{})
		go p.run(ctx)
	})
}

func (p *pollingManager) run(ctx context.Context) {
	defer close(p.loopDone)
	for {
		delay := p.nextDelay()
		p.mu.Lock()
		p.nextPollAt = time.Now().Add(delay)
		p.mu.Unlock()
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
			p.PollOnce(ctx)
		}
	}
}

func (p *pollingManager) nextDelay() time.Duration {
	p.mu.Lock()
	fails := p.consecutiveFailures
	current := p.currentInterval
	p.mu.Unlock()
	if fails <= 0 {
		// Steady state: server-driven cadence (already clamped on apply).
		return current
	}
	exp := fails
	if exp > maxBackoffExponent {
		exp = maxBackoffExponent
	}
	grown := current * (1 << exp)
	// Effective cap is max(MaxBackoffInterval, current) — never poll more
	// frequently on failure than the success cadence (matters when the
	// server sets a long max-age like 5+ min via Cache-Control).
	cap := MaxBackoffInterval
	if current > cap {
		cap = current
	}
	if grown <= 0 || grown > cap {
		grown = cap
	}
	// ±25% jitter so multiple SDK instances retrying against the same upstream
	// don't synchronise their retry waves (thundering-herd avoidance).
	half := int64(grown / 2)
	if half <= 0 {
		return grown
	}
	return grown + time.Duration(rand.Int63n(half)) - grown/4
}

// Close stops the loop and waits for the running goroutine to exit. Safe to
// call even if Start was never invoked.
func (p *pollingManager) Close() {
	if p.cancel != nil {
		p.cancel()
	}
	if p.loopDone != nil {
		<-p.loopDone
	}
}
