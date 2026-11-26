package klswitch

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

// API manifest reporting.
//
// Each SDK instance, at boot, asks its host framework "what routes do you have
// registered?" via a ManifestSource the operator configures, then POSTs the
// discovered route list to POST /v1/manifest. The backend uses this as the
// live truth of what's running, powering:
//
//   - the route picker on /rules/new (autocomplete from real routes)
//   - field autocomplete in the condition editor
//   - "stale route" warnings on the rules list
//   - schema-drift Layer 4 (always-on detection)
//
// Push policy:
//   - One push at client init (fire-and-forget; never blocks startup).
//   - On-demand re-push via Client.ReportManifest. Backend dedups by content
//     hash so calling this on a steady-state app is free.
//
// Failure policy: log + counter bump. A failed push is an operator-visibility
// regression, not an incident.
//
// Opt-out: pass WithReportManifest(false), or simply omit WithManifest.
// Frameworks without a discoverable route registry (raw net/http handlers,
// opaque middleware chains) skip the reporter — the console falls back to
// free-text route entry with no regression.

// braceParam matches {id} and {id:int} — the dialect gorilla/mux, JAX-RS and
// Spring use for a path variable.
var braceParam = regexp.MustCompile(`\{([^:{}]+)(?::[^{}]+)?\}`)

// CanonicalisePath rewrites {id} and {id:int} to :id.
//
// The engine speaks one dialect. This lives in the reporter rather than in each
// adapter because the adapters disagreed: Spring and FastAPI converted,
// gorilla/mux and the hand-written sources did not, so five frameworks published
// /api/items/{id} while eight published /api/items/:id. A rule written from the
// console's route picker then matched nothing, because evaluation normalises to
// the colon form.
//
// Central also means a custom ManifestSource cannot get it wrong — the escape
// hatch for frameworks with no route registry is exactly where someone types a
// path by hand. Idempotent; a converter spec like {id:int} is a routing
// constraint and is dropped.
func CanonicalisePath(path string) string {
	if !strings.Contains(path, "{") {
		return path
	}
	return braceParam.ReplaceAllString(path, ":$1")
}

// DefaultManifestTimeout is the per-request timeout for a manifest POST.
const DefaultManifestTimeout = 5 * time.Second

// ManifestField describes one discovered field on a route — best-effort, only
// when the framework supplies it.
type ManifestField struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

// ManifestRouteFields groups discovered fields by request location.
type ManifestRouteFields struct {
	Body    []ManifestField `json:"body"`
	Headers []ManifestField `json:"headers"`
	Params  []ManifestField `json:"params"`
	Query   []ManifestField `json:"query"`
}

// ManifestRoute is one route discovered from a framework's registry.
//
// Handler and Fields are best-effort — omitted from the wire when nil/empty.
type ManifestRoute struct {
	Method  string               `json:"method"`            // always emitted uppercase
	Path    string               `json:"path"`              // templated, e.g. "/v1/payments/:id"
	Handler string               `json:"handler,omitempty"` // best-effort identifier
	Fields  *ManifestRouteFields `json:"fields,omitempty"`
}

// ManifestSource is what an operator passes to the SDK. Each framework adapter
// ships its own helper (e.g. chiadapter.ManifestSource(router)) that returns one.
type ManifestSource struct {
	// Framework names the host framework — used in the wire payload + logs.
	Framework string
	// Collect runs discovery and returns the current route list. Called at
	// init and on every Client.ReportManifest.
	Collect func() ([]ManifestRoute, error)
}

// ManifestStatus is the reporter's health snapshot, surfaced via
// Client.ManifestStatus.
type ManifestStatus struct {
	Enabled         bool    `json:"enabled"`
	Framework       string  `json:"framework"`
	LastReportedAt  *string `json:"lastReportedAt"`
	LastHash        *string `json:"lastHash"`
	LastPushOk      *bool   `json:"lastPushOk"`
	LastPushError   *string `json:"lastPushError"`
	RouteCount      int     `json:"routeCount"`
	PushAttempts    int64   `json:"pushAttempts"`
	PushesSucceeded int64   `json:"pushesSucceeded"`
	PushesFailed    int64   `json:"pushesFailed"`
}

// disabledManifestStatus returns the "off" snapshot used when no source is
// configured.
func disabledManifestStatus() ManifestStatus {
	return ManifestStatus{Enabled: false}
}

// ManifestOptions configures a ManifestReporter.
type ManifestOptions struct {
	URL         string
	Headers     map[string]string
	Source      ManifestSource
	InstanceKey string
	Timeout     time.Duration // zero → DefaultManifestTimeout
	HTTPClient  *http.Client  // zero → fresh client with Timeout applied
	Logger      Logger        // zero → noopLogger
}

// ManifestReporter pushes the discovered route list to /v1/manifest, deduping
// by content hash. Synchronous — operators that want fire-and-forget startup
// behaviour should dispatch Report in a goroutine (Client does that for the
// initial push).
type ManifestReporter struct {
	opts ManifestOptions

	mu               sync.Mutex
	lastHash         string
	lastReportedAt   string
	lastPushOk       *bool
	lastPushError    string
	routeCount       int
	pushAttempts     int64
	pushesSucceeded  int64
	pushesFailed     int64
	stopped          bool
}

// NewManifestReporter constructs a reporter. Does not push — call Report.
func NewManifestReporter(opts ManifestOptions) *ManifestReporter {
	if opts.Timeout == 0 {
		opts.Timeout = DefaultManifestTimeout
	}
	if opts.HTTPClient == nil {
		opts.HTTPClient = &http.Client{Timeout: opts.Timeout}
	}
	if opts.Logger == nil {
		opts.Logger = noopLogger{}
	}
	return &ManifestReporter{opts: opts}
}

// Report runs discovery once and pushes if the hash has changed. Safe to
// call repeatedly — backend dedups by hash, so a steady-state registry
// produces no traffic. Returns true on success (push landed OR no-op
// because the hash was unchanged); false on push failure.
func (m *ManifestReporter) Report(ctx context.Context) bool {
	m.mu.Lock()
	if m.stopped {
		m.mu.Unlock()
		return false
	}
	m.mu.Unlock()

	raw, err := m.opts.Source.Collect()
	if err != nil {
		m.mu.Lock()
		m.pushAttempts++
		m.pushesFailed++
		m.lastPushError = "manifest source threw: " + err.Error()
		f := false
		m.lastPushOk = &f
		m.mu.Unlock()
		m.opts.Logger.Warn("manifest source threw; skipping report", map[string]any{
			"framework": m.opts.Source.Framework,
			"error":     err.Error(),
		})
		return false
	}

	normalised := normaliseManifestRoutes(raw)
	hash := hashManifestRoutes(normalised)

	m.mu.Lock()
	m.routeCount = len(normalised)
	if m.lastHash == hash {
		// Backend already has this exact set — no-op.
		m.mu.Unlock()
		return true
	}
	m.pushAttempts++
	m.mu.Unlock()

	ok := m.push(ctx, normalised, hash)

	m.mu.Lock()
	if ok {
		m.lastHash = hash
		m.lastReportedAt = time.Now().UTC().Format(time.RFC3339)
		t := true
		m.lastPushOk = &t
		m.lastPushError = ""
		m.pushesSucceeded++
	} else {
		f := false
		m.lastPushOk = &f
		m.pushesFailed++
	}
	m.mu.Unlock()
	return ok
}

// Stop makes subsequent Report calls a no-op. Idempotent.
func (m *ManifestReporter) Stop() {
	m.mu.Lock()
	m.stopped = true
	m.mu.Unlock()
}

// Status snapshots the reporter's counters + last-push state.
func (m *ManifestReporter) Status() ManifestStatus {
	m.mu.Lock()
	defer m.mu.Unlock()
	st := ManifestStatus{
		Enabled:         true,
		Framework:       m.opts.Source.Framework,
		RouteCount:      m.routeCount,
		PushAttempts:    m.pushAttempts,
		PushesSucceeded: m.pushesSucceeded,
		PushesFailed:    m.pushesFailed,
	}
	if m.lastReportedAt != "" {
		v := m.lastReportedAt
		st.LastReportedAt = &v
	}
	if m.lastHash != "" {
		v := m.lastHash
		st.LastHash = &v
	}
	if m.lastPushOk != nil {
		v := *m.lastPushOk
		st.LastPushOk = &v
	}
	if m.lastPushError != "" {
		v := m.lastPushError
		st.LastPushError = &v
	}
	return st
}

// ── internals ───────────────────────────────────────────────────────────────

// manifestPayload is the wire body — frozen contract.
type manifestPayload struct {
	ReportedAt  string          `json:"reportedAt"`
	InstanceKey string          `json:"instanceKey"`
	Framework   string          `json:"framework"`
	Hash        string          `json:"hash"`
	Routes      []ManifestRoute `json:"routes"`
}

func (m *ManifestReporter) push(ctx context.Context, routes []ManifestRoute, hash string) bool {
	payload := manifestPayload{
		ReportedAt:  time.Now().UTC().Format(time.RFC3339),
		InstanceKey: m.opts.InstanceKey,
		Framework:   m.opts.Source.Framework,
		Hash:        hash,
		Routes:      routes,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		m.mu.Lock()
		m.lastPushError = "manifest serialise failed: " + err.Error()
		m.mu.Unlock()
		m.opts.Logger.Warn("manifest serialise failed", map[string]any{"error": err.Error()})
		return false
	}

	reqCtx, cancel := context.WithTimeout(ctx, m.opts.Timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodPost, m.opts.URL, bytes.NewReader(body))
	if err != nil {
		m.mu.Lock()
		m.lastPushError = err.Error()
		m.mu.Unlock()
		m.opts.Logger.Warn("manifest build request failed", map[string]any{"error": err.Error()})
		return false
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range m.opts.Headers {
		req.Header.Set(k, v)
	}

	resp, err := m.opts.HTTPClient.Do(req)
	if err != nil {
		m.mu.Lock()
		m.lastPushError = err.Error()
		m.mu.Unlock()
		m.opts.Logger.Warn("manifest POST failed", map[string]any{
			"url":   m.opts.URL,
			"error": err.Error(),
		})
		return false
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		m.mu.Lock()
		m.lastPushError = fmt.Sprintf("manifest POST returned status %d", resp.StatusCode)
		m.mu.Unlock()
		m.opts.Logger.Warn("manifest POST rejected", map[string]any{
			"url":        m.opts.URL,
			"status":     resp.StatusCode,
			"routeCount": len(routes),
		})
		return false
	}
	m.opts.Logger.Info("manifest pushed", map[string]any{
		"framework":  m.opts.Source.Framework,
		"routeCount": len(routes),
		"hash":       hash,
	})
	return true
}

// normaliseManifestRoutes uppercases methods, drops invalid entries, and sorts
// by "METHOD path" so the same registry produces the same hash regardless of
// registration order. nil Fields lists are coerced to empty slices so the
// JSON wire form matches the Node / Python wrappers (which emit `[]`, not
// `null`).
func normaliseManifestRoutes(in []ManifestRoute) []ManifestRoute {
	out := make([]ManifestRoute, 0, len(in))
	for _, r := range in {
		if r.Method == "" || r.Path == "" {
			continue
		}
		out = append(out, ManifestRoute{
			Method:  strings.ToUpper(r.Method),
			Path:    CanonicalisePath(r.Path),
			Handler: r.Handler,
			Fields:  normaliseFields(r.Fields),
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Method != out[j].Method {
			return out[i].Method < out[j].Method
		}
		return out[i].Path < out[j].Path
	})
	return out
}

// normaliseFields fills nil sub-lists with empty slices so the JSON wire
// always shows `[]`, not `null`. Returns nil unchanged so omitempty on
// ManifestRoute.Fields still drops the key when the operator left it unset.
func normaliseFields(f *ManifestRouteFields) *ManifestRouteFields {
	if f == nil {
		return nil
	}
	out := *f
	if out.Body == nil {
		out.Body = []ManifestField{}
	}
	if out.Headers == nil {
		out.Headers = []ManifestField{}
	}
	if out.Params == nil {
		out.Params = []ManifestField{}
	}
	if out.Query == nil {
		out.Query = []ManifestField{}
	}
	return &out
}

func hashManifestRoutes(routes []ManifestRoute) string {
	body, _ := json.Marshal(routes)
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}
