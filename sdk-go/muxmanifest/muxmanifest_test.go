package muxmanifest

import (
	"net/http"
	"sort"
	"strings"
	"testing"

	"github.com/gorilla/mux"
)

func dummy(http.ResponseWriter, *http.Request) {}

func methodsAndPaths(t *testing.T, r *mux.Router) []string {
	t.Helper()
	routes, err := Discover(r)
	if err != nil {
		t.Fatalf("Discover: %v", err)
	}
	out := make([]string, 0, len(routes))
	for _, ro := range routes {
		out = append(out, ro.Method+" "+ro.Path)
	}
	sort.Strings(out)
	return out
}

func TestToColonStyle_StripsCurlyBraces(t *testing.T) {
	if got := ToColonStyle("/v1/payments/{id}"); got != "/v1/payments/:id" {
		t.Errorf("got %q", got)
	}
	if got := ToColonStyle(`/v1/items/{id:[0-9]+}`); got != "/v1/items/:id" {
		t.Errorf("regex strip failed: %q", got)
	}
	if got := ToColonStyle("/v1/health"); got != "/v1/health" {
		t.Errorf("passthrough failed: %q", got)
	}
}

func TestDiscover_PerMethodRoutes(t *testing.T) {
	r := mux.NewRouter()
	r.HandleFunc("/v1/payments/{id}", dummy).Methods("POST")
	r.HandleFunc("/v1/payments/{id}", dummy).Methods("GET")
	r.HandleFunc("/v1/users/{userId}", dummy).Methods("PUT", "DELETE")

	got := methodsAndPaths(t, r)
	want := []string{
		"DELETE /v1/users/:userId",
		"GET /v1/payments/:id",
		"POST /v1/payments/:id",
		"PUT /v1/users/:userId",
	}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Errorf("got %v, want %v", got, want)
	}
}

func TestDiscover_RouteWithoutMethodMatchEmitsAny(t *testing.T) {
	r := mux.NewRouter()
	// No .Methods() → handles every verb.
	r.HandleFunc("/v1/any", dummy)

	got := methodsAndPaths(t, r)
	if len(got) != 1 || got[0] != "ANY /v1/any" {
		t.Errorf("got %v, expected single ANY entry", got)
	}
}

func TestDiscover_SubroutersWalked(t *testing.T) {
	r := mux.NewRouter()
	api := r.PathPrefix("/api/v1").Subrouter()
	api.HandleFunc("/users/{id}", dummy).Methods("GET")

	got := methodsAndPaths(t, r)
	if !contains(got, "GET /api/v1/users/:id") {
		t.Errorf("subrouter route missing in %v", got)
	}
}

func TestSource_FrameworkIsGorillaMux(t *testing.T) {
	src := Source(mux.NewRouter())
	if src.Framework != "gorilla/mux" {
		t.Errorf("framework: %q", src.Framework)
	}
}

func TestDiscover_NilRouter(t *testing.T) {
	r, err := Discover(nil)
	if err != nil {
		t.Errorf("err: %v", err)
	}
	if len(r) != 0 {
		t.Errorf("expected empty, got %v", r)
	}
}

func contains(haystack []string, needle string) bool {
	for _, h := range haystack {
		if h == needle {
			return true
		}
	}
	return false
}
