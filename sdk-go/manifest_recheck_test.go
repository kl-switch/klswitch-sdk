package klswitch

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// The manifest must reflect routes registered *after* Init.
//
// That ordering is what the recommended wiring forces: chimware.New needs the
// client, so the client is built first and routes are registered afterwards.
// The init-time `go mr.Report(...)` therefore races registration. Go's
// scheduler usually runs it late enough to hide this, but forcing the other
// interleaving reports zero of two routes — hence the one-shot re-check on the
// first evaluation.
func TestManifestRecheckPicksUpLateRoutes(t *testing.T) {
	var mu sync.Mutex
	var pushes [][]string
	pushed := make(chan struct{}, 8)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if !strings.HasSuffix(req.URL.Path, "/manifest") {
			w.WriteHeader(204)
			return
		}
		body, _ := io.ReadAll(req.Body)
		var parsed struct {
			Routes []struct {
				Method string `json:"method"`
				Path   string `json:"path"`
			} `json:"routes"`
		}
		_ = json.Unmarshal(body, &parsed)
		got := make([]string, 0, len(parsed.Routes))
		for _, r := range parsed.Routes {
			got = append(got, r.Method+" "+r.Path)
		}
		mu.Lock()
		pushes = append(pushes, got)
		mu.Unlock()
		w.WriteHeader(204)
		pushed <- struct{}{}
	}))
	defer srv.Close()

	// A source whose route list is populated only after Init returns.
	var routesMu sync.Mutex
	var routes []ManifestRoute
	src := ManifestSource{
		Framework: "test",
		Collect: func() ([]ManifestRoute, error) {
			routesMu.Lock()
			defer routesMu.Unlock()
			return append([]ManifestRoute(nil), routes...), nil
		},
	}

	ctx := context.Background()
	client, err := Init(ctx,
		WithRules(decodeRules(t, ruleset)),
		WithToken("kls_test"),
		WithBaseURL(srv.URL),
		WithManifest(src),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer client.Close()

	// Sequence deliberately rather than racing: wait for the init-time push to
	// land, so the "routes registered afterwards" case is guaranteed rather
	// than timing-dependent. Without a re-check, this is the ONLY push the
	// client ever makes, and it carries nothing.
	select {
	case <-pushed:
	case <-time.After(3 * time.Second):
		t.Fatal("init-time manifest push never arrived")
	}

	routesMu.Lock()
	routes = append(routes,
		ManifestRoute{Method: "POST", Path: "/v1/orders/:id"},
		ManifestRoute{Method: "GET", Path: "/v1/users/:id"},
	)
	routesMu.Unlock()

	if _, err := client.Evaluate(ctx, decodeInput(t, inputMatch)); err != nil {
		t.Fatalf("Evaluate: %v", err)
	}

	select {
	case <-pushed:
	case <-time.After(3 * time.Second):
		t.Fatal("no manifest push after the first evaluation — the race is still open")
	}

	mu.Lock()
	defer mu.Unlock()
	last := pushes[len(pushes)-1]
	if len(last) != 2 {
		t.Fatalf("last push carried %v, want the two late routes", last)
	}
}

// The guard should stop us spawning a discovery goroutine per request.
func TestManifestRecheckRunsOnce(t *testing.T) {
	var mu sync.Mutex
	pushes := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		// The client also posts analytics to this base URL; count
		// only manifest pushes.
		if strings.HasSuffix(req.URL.Path, "/manifest") {
			mu.Lock()
			pushes++
			mu.Unlock()
		}
		w.WriteHeader(204)
	}))
	defer srv.Close()

	src := ManifestSource{
		Framework: "test",
		Collect: func() ([]ManifestRoute, error) {
			return []ManifestRoute{{Method: "GET", Path: "/x"}}, nil
		},
	}

	ctx := context.Background()
	client, err := Init(ctx,
		WithRules(decodeRules(t, ruleset)),
		WithToken("kls_test"),
		WithBaseURL(srv.URL),
		WithManifest(src),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer client.Close()

	in := decodeInput(t, inputMatch)
	for i := 0; i < 5; i++ {
		if _, err := client.Evaluate(ctx, in); err != nil {
			t.Fatalf("Evaluate: %v", err)
		}
	}
	time.Sleep(300 * time.Millisecond)

	mu.Lock()
	defer mu.Unlock()
	// Hash dedupe makes repeats cheap regardless; this pins the guard.
	if pushes > 2 {
		t.Fatalf("expected at most init + one re-check, got %d pushes", pushes)
	}
}
