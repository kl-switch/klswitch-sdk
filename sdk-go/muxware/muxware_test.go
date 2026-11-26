package muxware

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gorilla/mux"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
	"github.com/kl-switch/klswitch-sdk/sdk-go/httpmiddleware"
)

const rules = `{
  "schema_version": "1.0",
  "rules_version": 1,
  "rules": [
    {
      "id": "block_large_payment",
      "name": "block large payment",
      "selector": {"kind": "api", "keys": {"method": "POST", "path": "/v1/payments/:id"}},
      "enabled": true, "priority": 100,
      "condition": {"all": [{"field": "body.amount", "operator": "gt", "value": 50000}]},
      "action": {"type": "block", "payload": {"statusCode": 429}}
    },
    {
      "id": "flag_admin",
      "name": "flag admin",
      "selector": {"kind": "api", "keys": {"method": "GET", "path": "/v1/users/:id"}},
      "enabled": true, "priority": 50,
      "condition": {"all": [{"field": "query.role", "operator": "eq", "value": "admin"}]},
      "action": {"type": "flag"}
    }
  ]
}`

func newClient(t *testing.T) *ks.Client {
	t.Helper()
	var rs ks.RuleSetPayload
	if err := json.Unmarshal([]byte(rules), &rs); err != nil {
		t.Fatalf("parse rules: %v", err)
	}
	c, err := ks.Init(context.Background(), ks.WithRules(rs))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

// ─── ToColonStyle ────────────────────────────────────────────────────────

func TestToColonStyle(t *testing.T) {
	cases := map[string]string{
		"/v1/payments/{id}":            "/v1/payments/:id",
		"/v1/items/{id:[0-9]+}":        "/v1/items/:id",
		"/users/{userId}/posts/{pid}":  "/users/:userId/posts/:pid",
		"/v1/health":                   "/v1/health",
		"":                             "",
	}
	for in, want := range cases {
		if got := ToColonStyle(in); got != want {
			t.Errorf("ToColonStyle(%q) = %q, want %q", in, got, want)
		}
	}
}

// ─── Block path ──────────────────────────────────────────────────────────

func TestBlocksMatchingPayment(t *testing.T) {
	r := mux.NewRouter()
	r.Use(New(newClient(t)))
	r.HandleFunc("/v1/payments/{id}", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}).Methods("POST")

	srv := httptest.NewServer(r)
	defer srv.Close()

	resp, err := http.Post(
		srv.URL+"/v1/payments/abc", "application/json",
		strings.NewReader(`{"amount":70000}`),
	)
	if err != nil {
		t.Fatalf("POST: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 429 {
		body, _ := io.ReadAll(resp.Body)
		t.Errorf("got status %d (body=%q), want 429", resp.StatusCode, body)
	}
	var body map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&body)
}

// ─── Pass-through ───────────────────────────────────────────────────────

func TestPassesThroughSmallPayment(t *testing.T) {
	r := mux.NewRouter()
	r.Use(New(newClient(t)))
	r.HandleFunc("/v1/payments/{id}", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("handler ran"))
	}).Methods("POST")

	srv := httptest.NewServer(r)
	defer srv.Close()

	resp, err := http.Post(
		srv.URL+"/v1/payments/abc", "application/json",
		strings.NewReader(`{"amount":5}`),
	)
	if err != nil {
		t.Fatalf("POST: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Errorf("got status %d, want 200", resp.StatusCode)
	}
	got, _ := io.ReadAll(resp.Body)
	if string(got) != "handler ran" {
		t.Errorf("handler should have run; got body %q", got)
	}
}

// ─── Flag attaches result ───────────────────────────────────────────────

func TestFlagActionAttachesResultToContext(t *testing.T) {
	r := mux.NewRouter()
	r.Use(New(newClient(t)))
	r.HandleFunc("/v1/users/{id}", func(w http.ResponseWriter, req *http.Request) {
		result, ok := httpmiddleware.ResultFromContext(req.Context())
		if !ok || len(result.Matched) != 1 || result.Matched[0].ID != "flag_admin" {
			http.Error(w, "result missing", 500)
			return
		}
		w.WriteHeader(http.StatusOK)
	}).Methods("GET")

	srv := httptest.NewServer(r)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/v1/users/42?role=admin")
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		body, _ := io.ReadAll(resp.Body)
		t.Errorf("got status %d (body=%q), want 200", resp.StatusCode, body)
	}
}

// ─── Subrouter scenario ─────────────────────────────────────────────────

func TestSubrouterPathTemplate(t *testing.T) {
	r := mux.NewRouter()
	r.Use(New(newClient(t)))
	api := r.PathPrefix("/v1").Subrouter()
	api.HandleFunc("/payments/{id}", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(200)
	}).Methods("POST")

	srv := httptest.NewServer(r)
	defer srv.Close()

	resp, err := http.Post(
		srv.URL+"/v1/payments/xyz", "application/json",
		strings.NewReader(`{"amount":70000}`),
	)
	if err != nil {
		t.Fatalf("POST: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 429 {
		t.Errorf("subrouter pattern didn't reach rule selector; got %d", resp.StatusCode)
	}
}
