package klswitch

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// manifestRecorder is an httptest server capturing every body the reporter POSTs.
type manifestRecorder struct {
	srv         *httptest.Server
	mu          sync.Mutex
	bodies      []map[string]any
	authHeaders []string
	status      int
}

func newManifestRecorder() *manifestRecorder {
	r := &manifestRecorder{status: 204}
	r.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		body, _ := io.ReadAll(req.Body)
		var parsed map[string]any
		_ = json.Unmarshal(body, &parsed)
		r.mu.Lock()
		r.bodies = append(r.bodies, parsed)
		r.authHeaders = append(r.authHeaders, req.Header.Get("Authorization"))
		r.mu.Unlock()
		w.WriteHeader(r.status)
	}))
	return r
}

func (r *manifestRecorder) close() { r.srv.Close() }

func (r *manifestRecorder) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.bodies)
}

func (r *manifestRecorder) last() map[string]any {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.bodies[len(r.bodies)-1]
}

func sourceOf(routes []ManifestRoute) ManifestSource {
	return ManifestSource{
		Framework: "fastify",
		Collect:   func() ([]ManifestRoute, error) { return routes, nil },
	}
}

func newReporter(t *testing.T, rec *manifestRecorder, source ManifestSource) *ManifestReporter {
	t.Helper()
	return NewManifestReporter(ManifestOptions{
		URL:         rec.srv.URL,
		Headers:     map[string]string{"Authorization": "Bearer t", "X-Instance-Key": "pod-1"},
		Source:      source,
		InstanceKey: "pod-1",
	})
}

// ─── Reporter behaviour ──────────────────────────────────────────────────

func TestManifest_ReportPostsNormalisedRoutes(t *testing.T) {
	rec := newManifestRecorder()
	defer rec.close()

	rep := newReporter(t, rec, sourceOf([]ManifestRoute{
		// Out-of-order, mixed-case methods — the reporter normalises.
		{Method: "get", Path: "/v1/healthz"},
		{Method: "POST", Path: "/v1/payments"},
		{Method: "get", Path: "/v1/orders/:id"},
	}))
	if !rep.Report(context.Background()) {
		t.Fatal("Report returned false")
	}
	if got := rec.count(); got != 1 {
		t.Fatalf("expected 1 POST, got %d", got)
	}

	body := rec.last()
	if body["framework"] != "fastify" {
		t.Errorf("framework: %v", body["framework"])
	}
	if body["instanceKey"] != "pod-1" {
		t.Errorf("instanceKey: %v", body["instanceKey"])
	}
	hash, _ := body["hash"].(string)
	if len(hash) != 64 {
		t.Errorf("expected 64-char sha256 hex, got %q", hash)
	}

	routes, _ := body["routes"].([]any)
	if len(routes) != 3 {
		t.Fatalf("expected 3 routes, got %d", len(routes))
	}
	expected := []string{
		"GET /v1/healthz",
		"GET /v1/orders/:id",
		"POST /v1/payments",
	}
	for i, r := range routes {
		m := r.(map[string]any)
		got := m["method"].(string) + " " + m["path"].(string)
		if got != expected[i] {
			t.Errorf("route[%d]: got %q want %q", i, got, expected[i])
		}
	}
	// Sender forwards configured headers.
	rec.mu.Lock()
	if rec.authHeaders[0] != "Bearer t" {
		t.Errorf("Authorization: %q", rec.authHeaders[0])
	}
	rec.mu.Unlock()
}

func TestManifest_DedupByHash(t *testing.T) {
	rec := newManifestRecorder()
	defer rec.close()
	rep := newReporter(t, rec, sourceOf([]ManifestRoute{
		{Method: "POST", Path: "/v1/payments"},
	}))
	rep.Report(context.Background())
	if rec.count() != 1 {
		t.Fatalf("first push: count = %d", rec.count())
	}
	rep.Report(context.Background())
	// Same routes → no extra POST.
	if rec.count() != 1 {
		t.Errorf("dedup failed: count = %d", rec.count())
	}
}

func TestManifest_PushesAgainWhenRoutesChange(t *testing.T) {
	rec := newManifestRecorder()
	defer rec.close()
	var routes []ManifestRoute
	routes = append(routes, ManifestRoute{Method: "POST", Path: "/v1/payments"})
	rep := NewManifestReporter(ManifestOptions{
		URL:    rec.srv.URL,
		Source: ManifestSource{Framework: "fastify", Collect: func() ([]ManifestRoute, error) {
			cp := make([]ManifestRoute, len(routes))
			copy(cp, routes)
			return cp, nil
		}},
		InstanceKey: "pod-1",
	})
	rep.Report(context.Background())
	if rec.count() != 1 {
		t.Fatalf("first push: count = %d", rec.count())
	}
	routes = append(routes, ManifestRoute{Method: "POST", Path: "/v1/refunds"})
	rep.Report(context.Background())
	if rec.count() != 2 {
		t.Errorf("second push: count = %d", rec.count())
	}
	rt, _ := rec.last()["routes"].([]any)
	if len(rt) != 2 {
		t.Errorf("routes len = %d", len(rt))
	}
}

func TestManifest_Non2xxCountsAsFailure(t *testing.T) {
	rec := newManifestRecorder()
	rec.status = 500
	defer rec.close()
	rep := newReporter(t, rec, sourceOf([]ManifestRoute{
		{Method: "POST", Path: "/v1/payments"},
	}))
	if rep.Report(context.Background()) {
		t.Fatal("expected Report to return false on 500")
	}
	st := rep.Status()
	if st.LastPushOk == nil || *st.LastPushOk {
		t.Errorf("LastPushOk: %+v", st.LastPushOk)
	}
	if st.LastPushError == nil || !strings.Contains(*st.LastPushError, "500") {
		t.Errorf("LastPushError: %+v", st.LastPushError)
	}
	if st.PushesFailed != 1 {
		t.Errorf("PushesFailed = %d", st.PushesFailed)
	}
}

func TestManifest_SourceErrorCountsAsFailure(t *testing.T) {
	rec := newManifestRecorder()
	defer rec.close()
	rep := NewManifestReporter(ManifestOptions{
		URL: rec.srv.URL,
		Source: ManifestSource{Framework: "fastify", Collect: func() ([]ManifestRoute, error) {
			return nil, errors.New("walk boom")
		}},
		InstanceKey: "pod-1",
	})
	if rep.Report(context.Background()) {
		t.Fatal("expected Report to return false on source error")
	}
	if rec.count() != 0 {
		t.Errorf("no POST should fire when source errs; got %d", rec.count())
	}
	st := rep.Status()
	if st.PushesFailed != 1 {
		t.Errorf("PushesFailed = %d", st.PushesFailed)
	}
	if st.LastPushError == nil || !strings.Contains(*st.LastPushError, "walk boom") {
		t.Errorf("LastPushError = %+v", st.LastPushError)
	}
}

func TestManifest_StopMakesReportNoOp(t *testing.T) {
	rec := newManifestRecorder()
	defer rec.close()
	rep := newReporter(t, rec, sourceOf([]ManifestRoute{
		{Method: "POST", Path: "/v1/payments"},
	}))
	rep.Stop()
	if rep.Report(context.Background()) {
		t.Error("expected false after Stop")
	}
	if rec.count() != 0 {
		t.Errorf("no POST should fire after Stop; got %d", rec.count())
	}
}

func TestManifest_RouteFieldsRideAlong(t *testing.T) {
	rec := newManifestRecorder()
	defer rec.close()
	rep := newReporter(t, rec, sourceOf([]ManifestRoute{
		{
			Method:  "POST",
			Path:    "/v1/payments",
			Handler: "createPayment",
			Fields: &ManifestRouteFields{
				Body: []ManifestField{{Name: "amount", Type: "number"}},
			},
		},
	}))
	rep.Report(context.Background())
	route := rec.last()["routes"].([]any)[0].(map[string]any)
	if route["handler"] != "createPayment" {
		t.Errorf("handler: %v", route["handler"])
	}
	fields := route["fields"].(map[string]any)
	body := fields["body"].([]any)
	if len(body) != 1 || body[0].(map[string]any)["name"] != "amount" {
		t.Errorf("body fields wrong: %v", body)
	}
	// Other locations serialise as empty arrays (NOT omitted).
	if q, ok := fields["query"].([]any); !ok || len(q) != 0 {
		t.Errorf("query should be empty array, got %v", fields["query"])
	}
}

func TestManifest_DisabledStatusBlock(t *testing.T) {
	st := disabledManifestStatus()
	if st.Enabled {
		t.Error("expected disabled")
	}
	if st.Framework != "" {
		t.Errorf("framework: %q", st.Framework)
	}
}

// Five frameworks published /api/items/{id} and eight published /api/items/:id,
// so a rule written from the console's route picker matched nothing. The engine
// normalises to the colon form at evaluation; the manifest must agree.
func TestCanonicalisePath(t *testing.T) {
	cases := map[string]string{
		"/api/items/{id}":                    "/api/items/:id",
		"/api/items/{id}/reviews/{reviewId}": "/api/items/:id/reviews/:reviewId",
		"/api/items/{id:int}":                "/api/items/:id", // the spec is a routing constraint
		"/api/items/:id":                     "/api/items/:id", // idempotent
		"/health":                            "/health",
	}
	for in, want := range cases {
		if got := CanonicalisePath(in); got != want {
			t.Errorf("CanonicalisePath(%q) = %q, want %q", in, got, want)
		}
	}
}
