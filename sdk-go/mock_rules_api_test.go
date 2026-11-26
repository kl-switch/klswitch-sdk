package klswitch

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Drives the wrapper against a real local HTTP rules API (the Java
// MockRulesApiTest analogue). Exercises the [HTTPFetcher], polling loop,
// last-known-good preservation on failure, and error-body propagation.

func buildRuleset(version int) RuleSetPayload {
	return RuleSetPayload{
		SchemaVersion: "1.0",
		RulesVersion:  version,
		Rules: []Rule{
			{
				ID:       "r",
				Name:     "R",
				Selector: Selector{Kind: "api", Keys: map[string]string{"method": "POST", "path": "/p"}},
				Enabled:  true,
				Priority: p(100),
				Condition: ConditionNode{
					All: []ConditionNode{{Field: "body.amount", Operator: OpGt, Value: 100}},
				},
				Action: Action{Type: "block"},
			},
		},
	}
}

func p(n int) *int { return &n }

func evalInput() EvaluationInput {
	return EvaluationInput{
		Selector: Selector{Kind: "api", Keys: map[string]string{"method": "POST", "path": "/p"}},
		Facts:    Facts{Body: map[string]any{"amount": 500}},
	}
}

// mockRulesServer wires an httptest.Server that returns successive rulesets.
// Each call returns the version from currentVersion (atomic, settable).
type mockRulesServer struct {
	srv            *httptest.Server
	currentVersion atomic.Int64
	fails          atomic.Int32 // > 0 → respond 500 instead of rules
	calls          atomic.Int32
}

func newMockRulesServer(t *testing.T) *mockRulesServer {
	m := &mockRulesServer{}
	m.currentVersion.Store(1)
	m.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		m.calls.Add(1)
		if m.fails.Load() > 0 {
			http.Error(w, "simulated upstream failure", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(buildRuleset(int(m.currentVersion.Load())))
	}))
	t.Cleanup(m.srv.Close)
	return m
}

// Regression: the SDK sets `Accept-Encoding: gzip`, which disables Go's
// transparent response decompression — the fetcher must gunzip the ruleset
// body itself. Before the fix the JSON decode failed on the gzip magic byte
// (0x1f) and the SDK fell back to inline/last-known-good rules.
func TestMockAPI_GzippedRulesetIsDecoded(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var buf bytes.Buffer
		gz := gzip.NewWriter(&buf)
		_ = json.NewEncoder(gz).Encode(buildRuleset(7))
		_ = gz.Close()
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Content-Encoding", "gzip")
		_, _ = w.Write(buf.Bytes())
	}))
	defer srv.Close()

	ks, err := Init(context.Background(), WithToken("ks_live_test"), WithRulesURL(srv.URL))
	if err != nil {
		t.Fatalf("Init with gzipped ruleset: %v", err)
	}
	defer ks.Close()

	st, _ := ks.GetStatus(context.Background())
	if st.ActiveRulesVersion == nil || *st.ActiveRulesVersion != 7 {
		t.Fatalf("gzipped ruleset not decoded; expected active v7, got %+v", st.ActiveRulesVersion)
	}
	r, err := ks.Evaluate(context.Background(), evalInput())
	if err != nil || len(r.Matched) != 1 {
		t.Errorf("expected one match after gzipped init, got %v / %+v", err, r)
	}
}

func TestMockAPI_InitFromURLLoadsRules(t *testing.T) {
	m := newMockRulesServer(t)
	m.currentVersion.Store(5)

	ks, err := Init(context.Background(), WithToken("ks_live_test"), WithRulesURL(m.srv.URL))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	st, _ := ks.GetStatus(context.Background())
	if !st.Ready || !st.LastLoadOk {
		t.Fatalf("expected Ready+LastLoadOk, got %+v", st)
	}
	if st.ActiveRulesVersion == nil || *st.ActiveRulesVersion != 5 {
		t.Errorf("expected active version 5, got %+v", st.ActiveRulesVersion)
	}
	if st.LastRefreshOk == nil || !*st.LastRefreshOk {
		t.Errorf("expected LastRefreshOk=true after initial fetch, got %+v", st.LastRefreshOk)
	}

	// Verify it actually evaluates.
	r, err := ks.Evaluate(context.Background(), evalInput())
	if err != nil || len(r.Matched) != 1 {
		t.Errorf("expected one match after init, got %v / %+v", err, r)
	}
}

func TestMockAPI_RefreshPicksUpNewVersion(t *testing.T) {
	m := newMockRulesServer(t)
	m.currentVersion.Store(1)

	ks, err := Init(context.Background(), WithToken("ks_live_test"), WithRulesURL(m.srv.URL))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	m.currentVersion.Store(9)
	if err := ks.Refresh(context.Background()); err != nil {
		t.Fatalf("Refresh: %v", err)
	}

	st, _ := ks.GetStatus(context.Background())
	if st.ActiveRulesVersion == nil || *st.ActiveRulesVersion != 9 {
		t.Errorf("expected active version 9 after refresh, got %+v", st.ActiveRulesVersion)
	}
}

// The frozen "last known good" guarantee: a failing refresh must NOT replace
// the active ruleset, and the error must be surfaced via GetStatus.
func TestMockAPI_RefreshFailureKeepsLastKnownGood(t *testing.T) {
	m := newMockRulesServer(t)
	m.currentVersion.Store(7)

	ks, err := Init(context.Background(), WithToken("ks_live_test"), WithRulesURL(m.srv.URL))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	m.fails.Store(1)
	if err := ks.Refresh(context.Background()); err != nil {
		t.Fatalf("Refresh must not return error on fetch failure; got %v", err)
	}

	st, _ := ks.GetStatus(context.Background())
	if st.ActiveRulesVersion == nil || *st.ActiveRulesVersion != 7 {
		t.Errorf("active version must stay at last-known-good 7, got %+v", st.ActiveRulesVersion)
	}
	if st.LastRefreshOk == nil || *st.LastRefreshOk {
		t.Errorf("expected LastRefreshOk=false after failed refresh, got %+v", st.LastRefreshOk)
	}
	if st.LastRefreshError == nil || !strings.Contains(*st.LastRefreshError, "500") {
		t.Errorf("expected LastRefreshError mentioning 500, got %+v", st.LastRefreshError)
	}
	// And evaluation still works against the LKG ruleset.
	r, err := ks.Evaluate(context.Background(), evalInput())
	if err != nil || len(r.Matched) != 1 {
		t.Errorf("expected one match against LKG, got %v / %+v", err, r)
	}
}

// Periodic polling actually fires and picks up server-side changes.
func TestMockAPI_RecurringPollingPicksUpChanges(t *testing.T) {
	m := newMockRulesServer(t)
	m.currentVersion.Store(1)

	ks, err := Init(context.Background(),
		WithToken("ks_live_test"),
		WithRulesURL(m.srv.URL),
		WithRefreshInterval(50*time.Millisecond),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	st, _ := ks.GetStatus(context.Background())
	if !st.Polling {
		t.Fatalf("expected Polling=true, got %+v", st)
	}

	m.currentVersion.Store(42)
	if !waitFor(t, 3*time.Second, func() bool {
		s, _ := ks.GetStatus(context.Background())
		return s.ActiveRulesVersion != nil && *s.ActiveRulesVersion == 42
	}) {
		s, _ := ks.GetStatus(context.Background())
		t.Fatalf("recurring poll never picked up v42; status=%+v", s)
	}
}

// Init from a URL where the initial fetch fails must error and release resources.
func TestMockAPI_InitFailsWhenInitialFetchFails(t *testing.T) {
	m := newMockRulesServer(t)
	m.fails.Store(1)
	_, err := Init(context.Background(), WithToken("ks_live_test"), WithRulesURL(m.srv.URL))
	if err == nil {
		t.Fatalf("expected init to fail when initial fetch errors and no inline rules supplied")
	}
}

// Init combining inline rules + a rulesURL: inline loads first; the URL fetch
// can fail without breaking init (LKG = inline). Surface as LastRefreshOk=false.
func TestMockAPI_InlineFallbackForFailingURLFetch(t *testing.T) {
	m := newMockRulesServer(t)
	m.fails.Store(1)
	rs := buildRuleset(3)
	ks, err := Init(context.Background(),
		WithRules(rs),
		WithToken("ks_live_test"),
		WithRulesURL(m.srv.URL),
	)
	if err != nil {
		t.Fatalf("Init should succeed with inline LKG even when URL fetch fails; got %v", err)
	}
	defer ks.Close()

	st, _ := ks.GetStatus(context.Background())
	if st.ActiveRulesVersion == nil || *st.ActiveRulesVersion != 3 {
		t.Errorf("expected inline version 3 as LKG, got %+v", st.ActiveRulesVersion)
	}
	if st.LastRefreshOk == nil || *st.LastRefreshOk {
		t.Errorf("expected LastRefreshOk=false from the failing initial fetch")
	}
}

// Body snippet from a 5xx response must appear in the error surfaced via status.
func TestMockAPI_ErrorBodySnippetInRefreshError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		fmt.Fprint(w, "upstream rules service unavailable: maintenance window")
	}))
	defer srv.Close()

	ks, err := Init(context.Background(),
		WithRules(buildRuleset(1)),
		WithToken("ks_live_test"),
		WithRulesURL(srv.URL),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	st, _ := ks.GetStatus(context.Background())
	if st.LastRefreshError == nil ||
		!strings.Contains(*st.LastRefreshError, "502") ||
		!strings.Contains(*st.LastRefreshError, "maintenance window") {
		t.Errorf("expected refresh error to include status 502 + body snippet, got %+v", st.LastRefreshError)
	}
}

// A custom fetcher injects the rules without touching the network. Verifies
// WithFetcher overrides the URL-based default.
func TestMockAPI_WithFetcherInjection(t *testing.T) {
	var mu sync.Mutex
	calls := 0
	customFetcher := fetcherFunc(func(ctx context.Context) (RuleSetPayload, error) {
		mu.Lock()
		defer mu.Unlock()
		calls++
		return buildRuleset(99), nil
	})

	ks, err := Init(context.Background(), WithFetcher(customFetcher))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	st, _ := ks.GetStatus(context.Background())
	if st.ActiveRulesVersion == nil || *st.ActiveRulesVersion != 99 {
		t.Errorf("expected version 99 from custom fetcher, got %+v", st.ActiveRulesVersion)
	}
	mu.Lock()
	defer mu.Unlock()
	if calls != 1 {
		t.Errorf("expected exactly 1 fetcher call at init, got %d", calls)
	}
}

func TestMockAPI_CustomFetcherPathConstructsCleanly(t *testing.T) {
	// The custom fetcher bypasses the default HTTPFetcher entirely — the
	// operator owns their own transport, and no backend URL is required.
	ks, err := Init(context.Background(),
		WithFetcher(funcFetcher(func(context.Context) (RuleSetPayload, error) {
			return buildRuleset(1), nil
		})),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()
	// No assertion target other than: client constructs cleanly without a
	// background goroutines. -race in CI would surface any teardown issues.
	st, _ := ks.GetStatus(context.Background())
	if !st.Ready {
		t.Errorf("expected client Ready")
	}
}

type funcFetcher func(context.Context) (RuleSetPayload, error)

func (f funcFetcher) Fetch(ctx context.Context) (RuleSetPayload, error) { return f(ctx) }

// Negative refresh interval is rejected.
func TestMockAPI_NegativeRefreshIntervalRejected(t *testing.T) {
	_, err := Init(context.Background(),
		WithRules(buildRuleset(1)),
		WithRefreshInterval(-1*time.Second),
	)
	if !errors.Is(err, errInvalidRefreshInterval) {
		t.Fatalf("expected errInvalidRefreshInterval, got %v", err)
	}
}

// A Refresh racing Client.Close must not panic, deadlock, or corrupt state.
// The exact outcome of Refresh is unspecified (succeeds if Refresh wins the
// race; errors "not initialized" if Close wins) — only the safety property
// matters here. -race catches the obvious data-race failure modes.
func TestMockAPI_RefreshDuringCloseIsSafe(t *testing.T) {
	const iterations = 20
	for i := 0; i < iterations; i++ {
		m := newMockRulesServer(t)
		m.currentVersion.Store(1)
		ks, err := Init(context.Background(), WithToken("ks_live_test"), WithRulesURL(m.srv.URL))
		if err != nil {
			t.Fatalf("Init #%d: %v", i, err)
		}

		done := make(chan struct{})
		go func() {
			defer close(done)
			_ = ks.Refresh(context.Background())
		}()
		// Yield briefly so Refresh has a real chance to be mid-fetch.
		time.Sleep(time.Millisecond)
		if err := ks.Close(); err != nil {
			t.Fatalf("Close #%d: %v", i, err)
		}

		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatalf("Refresh #%d hung past Close", i)
		}

		// Status after Close must be a sane closed-state shape.
		st, _ := ks.GetStatus(context.Background())
		if st.Ready {
			t.Fatalf("status #%d still Ready after Close: %+v", i, st)
		}
	}
}

// fetcherFunc lets a closure implement RulesFetcher.
type fetcherFunc func(ctx context.Context) (RuleSetPayload, error)

func (f fetcherFunc) Fetch(ctx context.Context) (RuleSetPayload, error) { return f(ctx) }
