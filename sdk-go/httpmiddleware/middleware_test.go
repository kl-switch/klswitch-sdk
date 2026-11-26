package httpmiddleware_test

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
	"github.com/kl-switch/klswitch-sdk/sdk-go/httpmiddleware"
)

// ──── shared test fixtures ───────────────────────────────────────────────────

func p(n int) *int { return &n }

// rulesBlockLargePayment + flagAdminAccess gives a block and a flag rule on two
// distinct paths so tests can exercise both code paths.
var demoRules = ks.RuleSetPayload{
	SchemaVersion: "1.0",
	RulesVersion:  1,
	Rules: []ks.Rule{
		{
			ID:       "block_large_payment",
			Name:     "Block large payment",
			Selector: ks.Selector{Kind: "api", Keys: map[string]string{"method": "POST", "path": "/v1/payments/:id"}},
			Enabled:  true,
			Priority: p(100),
			Condition: ks.ConditionNode{
				All: []ks.ConditionNode{{Field: "body.amount", Operator: ks.OpGt, Value: 1000}},
			},
			Action: ks.Action{Type: "block", Payload: map[string]any{"statusCode": 429}},
		},
		{
			ID:       "flag_admin_access",
			Name:     "Flag admin access",
			Selector: ks.Selector{Kind: "api", Keys: map[string]string{"method": "GET", "path": "/v1/users/:id"}},
			Enabled:  true,
			Priority: p(50),
			Condition: ks.ConditionNode{
				Any: []ks.ConditionNode{{Field: "query.role", Operator: ks.OpEq, Value: "admin"}},
			},
			Action: ks.Action{Type: "flag"},
		},
	},
}

func newClient(t *testing.T) *ks.Client {
	t.Helper()
	c, err := ks.Init(context.Background(), ks.WithRules(demoRules))
	if err != nil {
		t.Fatalf("ks.Init: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func okHandler(called *atomic.Bool) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called.Store(true)
		w.WriteHeader(http.StatusOK)
	})
}

// ──── default behaviour ──────────────────────────────────────────────────────

// No rules match → request continues, result attached to context.
func TestMiddleware_PassesThroughAndAttachesResult(t *testing.T) {
	client := newClient(t)
	var called atomic.Bool
	mw := httpmiddleware.New(client, httpmiddleware.WithPathTemplate("/v1/payments/:id"))

	var attached ks.EvaluationResult
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called.Store(true)
		r2, _ := httpmiddleware.ResultFromContext(r.Context())
		attached = r2
		w.WriteHeader(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodPost, "/v1/payments/abc", strings.NewReader(`{"amount":50}`))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	mw(handler).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	if !called.Load() {
		t.Errorf("next handler should have been called")
	}
	if len(attached.Matched) != 0 {
		t.Errorf("expected no matches, got %+v", attached.Matched)
	}
}

// Block rule fires → middleware short-circuits with the rule's statusCode.
func TestMiddleware_BlocksOnMatchedBlockAction(t *testing.T) {
	client := newClient(t)
	var called atomic.Bool
	mw := httpmiddleware.New(client, httpmiddleware.WithPathTemplate("/v1/payments/:id"))

	req := httptest.NewRequest(http.MethodPost, "/v1/payments/abc", strings.NewReader(`{"amount":99999}`))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	mw(okHandler(&called)).ServeHTTP(rec, req)

	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("expected 429 from block_large_payment, got %d", rec.Code)
	}
	if called.Load() {
		t.Errorf("next handler must NOT be called on block")
	}
	var body map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &body)
	if body["ruleId"] != "block_large_payment" {
		t.Errorf("expected ruleId=block_large_payment, got %v", body["ruleId"])
	}
}

// Flag rule fires → request continues; matched result includes the flag.
func TestMiddleware_FlagDoesNotBlock(t *testing.T) {
	client := newClient(t)
	var called atomic.Bool
	var matched int
	mw := httpmiddleware.New(client, httpmiddleware.WithPathTemplate("/v1/users/:id"))

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called.Store(true)
		if res, ok := httpmiddleware.ResultFromContext(r.Context()); ok {
			matched = len(res.Matched)
		}
		w.WriteHeader(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodGet, "/v1/users/42?role=admin", nil)
	rec := httptest.NewRecorder()
	mw(handler).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK || !called.Load() {
		t.Fatalf("flag must NOT block; got status=%d called=%v", rec.Code, called.Load())
	}
	if matched != 1 {
		t.Errorf("expected one matched (flag), got %d", matched)
	}
}

// ──── pluggable handlers ─────────────────────────────────────────────────────

// Custom ResultHandler gets full control.
func TestMiddleware_WithCustomResultHandler(t *testing.T) {
	client := newClient(t)
	var sawBlock bool
	mw := httpmiddleware.New(client,
		httpmiddleware.WithPathTemplate("/v1/payments/:id"),
		httpmiddleware.WithResultHandler(func(w http.ResponseWriter, r *http.Request, result ks.EvaluationResult, next http.Handler) {
			for _, m := range result.Matched {
				if m.Action.Type == "block" {
					sawBlock = true
					http.Error(w, "custom block: "+m.ID, http.StatusTeapot)
					return
				}
			}
			next.ServeHTTP(w, r)
		}),
	)

	req := httptest.NewRequest(http.MethodPost, "/v1/payments/abc", strings.NewReader(`{"amount":99999}`))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	mw(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {})).ServeHTTP(rec, req)

	if !sawBlock || rec.Code != http.StatusTeapot {
		t.Errorf("expected custom handler to fire 418, got status=%d sawBlock=%v", rec.Code, sawBlock)
	}
}

// Custom ErrorHandler runs when Client.Evaluate fails (here: after Close).
func TestMiddleware_WithCustomErrorHandler(t *testing.T) {
	client := newClient(t)
	_ = client.Close() // evaluate now returns "not initialized"

	var sawErr error
	mw := httpmiddleware.New(client,
		httpmiddleware.WithPathTemplate("/v1/payments/:id"),
		httpmiddleware.WithErrorHandler(func(w http.ResponseWriter, r *http.Request, err error) {
			sawErr = err
			w.WriteHeader(http.StatusServiceUnavailable)
		}),
	)

	req := httptest.NewRequest(http.MethodPost, "/v1/payments/abc", strings.NewReader(`{"amount":99999}`))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	mw(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("next handler must NOT be reached after Evaluate error")
	})).ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable || sawErr == nil {
		t.Errorf("expected 503 + error captured, got status=%d err=%v", rec.Code, sawErr)
	}
}

// ──── path resolution ────────────────────────────────────────────────────────

func TestMiddleware_WithPathFuncOverridesTemplate(t *testing.T) {
	client := newClient(t)
	var called atomic.Bool
	mw := httpmiddleware.New(client,
		httpmiddleware.WithPathTemplate("/wrong/path"),                                            // would never match
		httpmiddleware.WithPathFunc(func(*http.Request) string { return "/v1/payments/:id" }), // wins
	)

	req := httptest.NewRequest(http.MethodPost, "/v1/payments/abc", strings.NewReader(`{"amount":99999}`))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	mw(okHandler(&called)).ServeHTTP(rec, req)

	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("expected PathFunc-provided template to match the rule (429), got %d", rec.Code)
	}
}

// PathFunc returning "" falls back to template, then to concrete path.
func TestMiddleware_PathFuncEmptyFallsBackToTemplate(t *testing.T) {
	client := newClient(t)
	mw := httpmiddleware.New(client,
		httpmiddleware.WithPathTemplate("/v1/payments/:id"),
		httpmiddleware.WithPathFunc(func(*http.Request) string { return "" }), // empty → fall back
	)

	req := httptest.NewRequest(http.MethodPost, "/v1/payments/abc", strings.NewReader(`{"amount":99999}`))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	mw(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {})).ServeHTTP(rec, req)

	if rec.Code != http.StatusTooManyRequests {
		t.Errorf("template should have been used as fallback (expected 429), got %d", rec.Code)
	}
}

// ──── body handling ──────────────────────────────────────────────────────────

// Handler still sees the full body after the middleware reads it for facts.
func TestMiddleware_BodyRestoredForHandler(t *testing.T) {
	client := newClient(t)
	const payload = `{"amount":10,"note":"hello"}`

	var handlerBody string
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		handlerBody = string(b)
		w.WriteHeader(http.StatusOK)
	})

	mw := httpmiddleware.New(client, httpmiddleware.WithPathTemplate("/v1/payments/:id"))
	req := httptest.NewRequest(http.MethodPost, "/v1/payments/abc", strings.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	mw(handler).ServeHTTP(rec, req)

	if handlerBody != payload {
		t.Errorf("handler must see the full body; got %q want %q", handlerBody, payload)
	}
}

// Oversized body → body facts skipped, but the handler still sees every byte
// via the spliced reader. This is the DoS-protection guarantee.
func TestMiddleware_OversizedBodySkipsFactsButStreamsToHandler(t *testing.T) {
	client := newClient(t)
	// "amount":99999 would normally trigger the block rule; but with the
	// payload padded past the cap, body facts are dropped and the rule's
	// gt-1000 check sees no body.amount → no block, the handler runs.
	large := `{"amount":99999,"padding":"` + strings.Repeat("x", 2000) + `"}`

	var seenLen int
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		seenLen = len(b)
		w.WriteHeader(http.StatusOK)
	})

	mw := httpmiddleware.New(client,
		httpmiddleware.WithPathTemplate("/v1/payments/:id"),
		httpmiddleware.WithMaxBodyBytes(64), // tiny cap → body is "oversized"
	)
	req := httptest.NewRequest(http.MethodPost, "/v1/payments/abc", strings.NewReader(large))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	mw(handler).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 (block_large_payment can't see truncated body), got %d", rec.Code)
	}
	if seenLen != len(large) {
		t.Errorf("handler must still see the full body; got %d bytes want %d", seenLen, len(large))
	}
}

// Non-JSON content type → body is ignored entirely (matches Java/Node).
func TestMiddleware_NonJSONBodyIsIgnoredForFacts(t *testing.T) {
	client := newClient(t)
	mw := httpmiddleware.New(client, httpmiddleware.WithPathTemplate("/v1/payments/:id"))

	req := httptest.NewRequest(http.MethodPost, "/v1/payments/abc", strings.NewReader("not-json"))
	req.Header.Set("Content-Type", "text/plain")
	rec := httptest.NewRecorder()
	mw(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("non-JSON body must not surprise the rule; expected 200, got %d", rec.Code)
	}
}

// ──── BuildInput as a standalone helper ──────────────────────────────────────

func TestBuildInput_StandaloneUsage(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/v1/payments/abc?ref=42",
		strings.NewReader(`{"amount":7}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Trace", "abc")

	in := httpmiddleware.BuildInput(req, "/v1/payments/:id", httpmiddleware.DefaultMaxBodyBytes)
	if in.Selector.Keys["method"] != "POST" || in.Selector.Keys["path"] != "/v1/payments/:id" {
		t.Errorf("selector wrong: %+v", in.Selector)
	}
	if in.Facts.Headers["x-trace"] != "abc" {
		t.Errorf("expected lowercase header x-trace, got %+v", in.Facts.Headers)
	}
	if in.Facts.Query["ref"] != "42" {
		t.Errorf("expected query ref=42, got %+v", in.Facts.Query)
	}
	if v, _ := in.Facts.Body["amount"].(float64); v != 7 {
		t.Errorf("expected body.amount=7, got %+v", in.Facts.Body)
	}
}

func TestBuildInput_EmptyTemplateFallsBackToURLPath(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/concrete/path", nil)
	in := httpmiddleware.BuildInput(req, "", httpmiddleware.DefaultMaxBodyBytes)
	if in.Selector.Keys["path"] != "/concrete/path" {
		t.Errorf("expected concrete path fallback, got %v", in.Selector.Keys["path"])
	}
}

// ──── ResultFromContext on requests that didn't pass through middleware ──────

func TestResultFromContext_AbsentReturnsFalse(t *testing.T) {
	if _, ok := httpmiddleware.ResultFromContext(context.Background()); ok {
		t.Errorf("expected ok=false for context without a stored result")
	}
}

// ──── DefaultErrorHandler shape ──────────────────────────────────────────────

func TestDefaultErrorHandler_Returns500WithJSON(t *testing.T) {
	rec := httptest.NewRecorder()
	httpmiddleware.DefaultErrorHandler(rec, httptest.NewRequest("GET", "/x", nil), errors.New("boom"))
	if rec.Code != http.StatusInternalServerError {
		t.Errorf("expected 500, got %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "boom") {
		t.Errorf("expected error message in body, got %s", rec.Body.String())
	}
}
