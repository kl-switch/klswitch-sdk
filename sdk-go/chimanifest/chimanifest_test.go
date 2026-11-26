package chimanifest

import (
	"net/http"
	"sort"
	"strings"
	"testing"

	"github.com/go-chi/chi/v5"
)

func dummy(http.ResponseWriter, *http.Request) {}

func methodsAndPaths(t *testing.T, r chi.Routes) []string {
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
	cases := map[string]string{
		"/v1/payments/{id}":                "/v1/payments/:id",
		"/v1/users/{userId}/posts/{postId}": "/v1/users/:userId/posts/:postId",
		"/v1/health":                       "/v1/health",
	}
	for in, want := range cases {
		if got := ToColonStyle(in); got != want {
			t.Errorf("ToColonStyle(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestToColonStyle_StripsRegexSpec(t *testing.T) {
	if got := ToColonStyle(`/v1/items/{id:\d+}`); got != "/v1/items/:id" {
		t.Errorf("regex strip failed: %q", got)
	}
}

func TestDiscover_EmitsOneRoutePerMethod(t *testing.T) {
	r := chi.NewRouter()
	r.Get("/v1/users/{id}", dummy)
	r.Post("/v1/payments/{id}", dummy)
	r.Delete("/v1/payments/{id}", dummy)

	got := methodsAndPaths(t, r)
	want := []string{
		"DELETE /v1/payments/:id",
		"GET /v1/users/:id",
		"POST /v1/payments/:id",
	}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Errorf("got %v, want %v", got, want)
	}
}

func TestDiscover_NestedRoutes(t *testing.T) {
	r := chi.NewRouter()
	r.Route("/v1", func(s chi.Router) {
		s.Get("/health", dummy)
		s.Route("/users/{userId}", func(u chi.Router) {
			u.Get("/posts/{postId}", dummy)
		})
	})
	got := methodsAndPaths(t, r)
	if !contains(got, "GET /v1/health") {
		t.Errorf("missing GET /v1/health in %v", got)
	}
	if !contains(got, "GET /v1/users/:userId/posts/:postId") {
		t.Errorf("missing nested route in %v", got)
	}
}

func TestSource_FrameworkIsChi(t *testing.T) {
	src := Source(chi.NewRouter())
	if src.Framework != "chi" {
		t.Errorf("framework: %q", src.Framework)
	}
	routes, err := src.Collect()
	if err != nil {
		t.Fatalf("Collect: %v", err)
	}
	if routes != nil && len(routes) != 0 {
		t.Errorf("empty router → got %v", routes)
	}
}

func TestDiscover_NilRouterReturnsEmpty(t *testing.T) {
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
