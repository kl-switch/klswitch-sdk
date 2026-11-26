// Package httpmiddleware exposes a KLSwitch evaluation middleware compatible
// with the stdlib `net/http` middleware signature (`func(http.Handler) http.Handler`).
//
// Works out of the box with:
//   - stdlib `net/http` and the Go 1.22 enhanced `ServeMux` (via WithPathTemplate)
//   - chi, gorilla/mux, alice, and any router accepting the standard middleware shape
//   - routers that track templated routes (via WithPathFunc)
//
// Lifecycle hooks: a ResultHandler decides what to do with each EvaluationResult
// (the default short-circuits on `block` actions); an ErrorHandler handles a
// failure from Client.Evaluate itself (the default returns 500). Both are
// pluggable so the middleware never "interprets" engine actions on the user's
// behalf — the policy lives in your app.
package httpmiddleware

import (
	"context"
	"encoding/json"
	"net/http"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
)

// resultKey is the unexported context-key for the attached EvaluationResult.
type resultKey struct{}

// New returns a middleware function: `func(http.Handler) http.Handler`. Mount
// it per-route (so the path template is meaningful) or app-wide with WithPathFunc.
//
//	mw := httpmiddleware.New(client, httpmiddleware.WithPathTemplate("/v1/payments/:id"))
//	mux.Handle("POST /v1/payments/{id}", mw(http.HandlerFunc(paymentsHandler)))
//
// On each request: build an EvaluationInput from method + path + headers +
// query + JSON body, call Client.Evaluate, attach the result to the request
// context (read via [ResultFromContext]), then hand off to the ResultHandler.
func New(client *ks.Client, opts ...Option) func(http.Handler) http.Handler {
	cfg := buildConfig(opts)
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Already evaluated on this request: the middleware is in the chain
			// twice (r.Use plus a per-route mount, or chimware alongside
			// httpmiddleware during a migration). Evaluating again costs the
			// latency twice and emits a second analytics event, so decision
			// counts would read double.
			//
			// Unlike Flask and Django, Go cannot refuse this at startup —
			// middleware is plain function composition, so there is no
			// registration phase to inspect and nothing that knows how many
			// times it was wrapped. Reusing the first result is the only place
			// the duplicate can be caught.
			// Hand straight to the next handler rather than re-running the
			// result handler: policy already ran on the first mount, and a
			// custom handler with side effects (metrics, logging) would
			// otherwise fire twice for one request.
			if _, ok := ResultFromContext(r.Context()); ok {
				next.ServeHTTP(w, r)
				return
			}

			input := buildEvaluationInput(r, cfg)
			result, err := client.Evaluate(r.Context(), input)
			if err != nil {
				cfg.errorHandler(w, r, err)
				return
			}
			ctx := context.WithValue(r.Context(), resultKey{}, result)
			r = r.WithContext(ctx)
			cfg.resultHandler(w, r, result, next)
		})
	}
}

// ResultFromContext extracts the [ks.EvaluationResult] attached to the request
// context by the middleware. The bool is true when the request passed through
// the middleware; false otherwise (which lets handlers degrade gracefully when
// they're called outside a KLSwitched route).
func ResultFromContext(ctx context.Context) (ks.EvaluationResult, bool) {
	v, ok := ctx.Value(resultKey{}).(ks.EvaluationResult)
	return v, ok
}

// ResultHandler decides what to do with a successful Evaluate result. It owns
// the response — it must either write to w and return (short-circuit) or call
// `next.ServeHTTP(w, r)` to continue the chain.
type ResultHandler func(w http.ResponseWriter, r *http.Request, result ks.EvaluationResult, next http.Handler)

// ErrorHandler handles a failure from Client.Evaluate itself (e.g., evaluate
// after Close, or a transient WASM error). The default returns 500.
type ErrorHandler func(w http.ResponseWriter, r *http.Request, err error)

// PathFunc resolves the rule-selector path from a request. Useful for routers
// that track the matched route template, e.g.:
//
//	httpmiddleware.WithPathFunc(func(r *http.Request) string {
//	    return chi.RouteContext(r.Context()).RoutePattern()
//	})
type PathFunc func(*http.Request) string

// DefaultResultHandler is applied when no [WithResultHandler] is configured.
// It short-circuits on the first `block` match (writing the rule's
// `action.payload.statusCode`, default 403, with a small JSON body), and lets
// the handler chain continue otherwise.
func DefaultResultHandler(w http.ResponseWriter, r *http.Request, result ks.EvaluationResult, next http.Handler) {
	for _, m := range result.Matched {
		if m.Action.Type == "block" {
			writeBlock(w, m)
			return
		}
	}
	next.ServeHTTP(w, r)
}

// DefaultErrorHandler returns 500 with a JSON error body describing the
// evaluation failure.
func DefaultErrorHandler(w http.ResponseWriter, r *http.Request, err error) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusInternalServerError)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"error":  "klswitch evaluation failed",
		"detail": err.Error(),
	})
}

func writeBlock(w http.ResponseWriter, m ks.MatchedRule) {
	status := http.StatusForbidden
	// JSON numbers decode as float64; tolerate int too in case the action was
	// constructed in-process rather than fetched.
	switch v := m.Action.Payload["statusCode"].(type) {
	case float64:
		status = int(v)
	case int:
		status = v
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"blocked": true,
		"ruleId":  m.ID,
		"action":  m.Action,
	})
}
