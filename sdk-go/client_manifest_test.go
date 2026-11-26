package klswitch

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// manifestServer is a tiny in-process backend that records every manifest
// POST it sees. Used by the end-to-end Client tests below.
type manifestServer struct {
	srv         *httptest.Server
	mu          sync.Mutex
	bodies      []map[string]any
	authHeaders []string
}

func newManifestServer() *manifestServer {
	r := &manifestServer{}
	r.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		body, _ := io.ReadAll(req.Body)
		var parsed map[string]any
		_ = json.Unmarshal(body, &parsed)
		r.mu.Lock()
		r.bodies = append(r.bodies, parsed)
		r.authHeaders = append(r.authHeaders, req.Header.Get("Authorization"))
		r.mu.Unlock()
		w.WriteHeader(204)
	}))
	return r
}

func (s *manifestServer) URL() string { return s.srv.URL }
func (s *manifestServer) close()      { s.srv.Close() }

func (s *manifestServer) count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.bodies)
}

// waitForPush blocks (up to 2 s) until at least n bodies have been recorded.
// Fails the test if the deadline expires.
func (s *manifestServer) waitForPush(t *testing.T, n int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if s.count() >= n {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("manifest POST never reached count=%d (got %d)", n, s.count())
}

// waitForReporterSettled waits until the in-process reporter has committed
// lastHash — the recorder appends the body BEFORE the reporter sets
// lastHash, so without this, a subsequent ReportManifest may push again.
func waitForReporterSettled(t *testing.T, ks *Client) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if ks.ManifestStatus().LastHash != nil {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("manifest reporter never recorded a successful push")
}

func staticFetcher(rs RuleSetPayload) RulesFetcherFunc {
	return func(_ context.Context) (RuleSetPayload, error) { return rs, nil }
}

// RulesFetcherFunc adapts a func to the RulesFetcher interface. Defined
// here so manifest tests can inject inline rules without spinning up a
// /v1/ruleset endpoint.
type RulesFetcherFunc func(context.Context) (RuleSetPayload, error)

func (f RulesFetcherFunc) Fetch(ctx context.Context) (RuleSetPayload, error) {
	return f(ctx)
}

func sourceWithRoutes(routes []ManifestRoute) ManifestSource {
	return ManifestSource{
		Framework: "chi",
		Collect:   func() ([]ManifestRoute, error) { return routes, nil },
	}
}

func TestClient_ManifestPushedAtInit(t *testing.T) {
	rec := newManifestServer()
	defer rec.close()
	rs := decodeRules(t, ruleset)

	ks, err := Init(context.Background(),
		WithToken("ks_live_test"),
		WithManifestURL(rec.URL()),
		WithFetcher(staticFetcher(rs)),
		WithRefreshInterval(0),
		WithManifest(sourceWithRoutes([]ManifestRoute{{Method: "POST", Path: "/v1/payments"}})),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	rec.waitForPush(t, 1)
	st := ks.ManifestStatus()
	if !st.Enabled || st.Framework != "chi" || st.RouteCount != 1 {
		t.Errorf("ManifestStatus: %+v", st)
	}
	rec.mu.Lock()
	auth := rec.authHeaders[0]
	rec.mu.Unlock()
	if auth != "Bearer ks_live_test" {
		t.Errorf("Authorization: %q", auth)
	}
}

func TestClient_ManifestStatusDisabledWithoutSource(t *testing.T) {
	rs := decodeRules(t, ruleset)
	ks, err := Init(context.Background(), WithRules(rs))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()
	st := ks.ManifestStatus()
	if st.Enabled {
		t.Errorf("expected disabled, got %+v", st)
	}
}

func TestClient_ReportManifestFalseOptsOut(t *testing.T) {
	rec := newManifestServer()
	defer rec.close()
	rs := decodeRules(t, ruleset)

	ks, err := Init(context.Background(),
		WithToken("ks_live_test"),
		WithManifestURL(rec.URL()),
		WithFetcher(staticFetcher(rs)),
		WithRefreshInterval(0),
		WithManifest(sourceWithRoutes([]ManifestRoute{{Method: "POST", Path: "/v1/payments"}})),
		WithReportManifest(false),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	// Give any rogue push goroutine a beat to (not) fire.
	time.Sleep(100 * time.Millisecond)
	if ks.ManifestStatus().Enabled {
		t.Errorf("expected disabled when opt-out")
	}
	if rec.count() != 0 {
		t.Errorf("expected no POST; got %d", rec.count())
	}
}

func TestClient_ReportManifestRePushesOnChange(t *testing.T) {
	rec := newManifestServer()
	defer rec.close()
	rs := decodeRules(t, ruleset)

	var live []ManifestRoute
	live = append(live, ManifestRoute{Method: "POST", Path: "/v1/payments"})

	ks, err := Init(context.Background(),
		WithToken("ks_live_test"),
		WithManifestURL(rec.URL()),
		WithFetcher(staticFetcher(rs)),
		WithRefreshInterval(0),
		WithManifest(ManifestSource{
			Framework: "chi",
			Collect: func() ([]ManifestRoute, error) {
				cp := make([]ManifestRoute, len(live))
				copy(cp, live)
				return cp, nil
			},
		}),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	rec.waitForPush(t, 1)
	waitForReporterSettled(t, ks)
	before := rec.count()

	// Same routes → no extra POST.
	ks.ReportManifest(context.Background())
	if rec.count() != before {
		t.Errorf("dedup failed: %d → %d", before, rec.count())
	}

	// Mutate the registry → next call pushes again.
	live = append(live, ManifestRoute{Method: "POST", Path: "/v1/refunds"})
	if !ks.ReportManifest(context.Background()) {
		t.Error("expected ReportManifest to return true on push")
	}
	if rec.count() != before+1 {
		t.Errorf("expected one more POST, got %d", rec.count())
	}
}
