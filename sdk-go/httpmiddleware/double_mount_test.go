package httpmiddleware_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
	"github.com/kl-switch/klswitch-sdk/sdk-go/httpmiddleware"
)

// Two mounts in one chain — r.Use plus a per-route mount, or chimware alongside
// httpmiddleware during a migration. Go has no registration phase to inspect, so
// unlike Flask and Django this cannot be refused at startup; the duplicate has
// to be absorbed per request instead.
func TestSecondMountReusesTheFirstResult(t *testing.T) {
	c, err := ks.Init(context.Background(), ks.WithRules(demoRules))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer c.Close()

	evals := 0
	count := func(w http.ResponseWriter, r *http.Request, res ks.EvaluationResult, next http.Handler) {
		evals++
		next.ServeHTTP(w, r)
	}
	mw := httpmiddleware.New(c,
		httpmiddleware.WithPathTemplate("/v1/payments/:id"),
		httpmiddleware.WithResultHandler(count))

	reached := 0
	h := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reached++
		if _, ok := httpmiddleware.ResultFromContext(r.Context()); !ok {
			t.Error("the handler lost the evaluation result across the second mount")
		}
		w.WriteHeader(200)
	})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/v1/payments/1", strings.NewReader(`{"amount":1}`))
	req.Header.Set("Content-Type", "application/json")
	mw(mw(h)).ServeHTTP(rec, req)

	if evals != 1 {
		t.Fatalf("evaluated %d times, want 1", evals)
	}
	if reached != 1 {
		t.Fatalf("handler reached %d times, want 1", reached)
	}
}
