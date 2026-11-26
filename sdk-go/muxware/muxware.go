// Package muxware is the dedicated gorilla/mux middleware for KLSwitch.
//
// Pre-configures `httpmiddleware` with a path resolver that pulls from
// `mux.CurrentRoute(r).GetPathTemplate()` — operators don't have to
// hand-thread a `WithPathFunc` themselves.
//
// Usage:
//
//	import (
//	    "github.com/gorilla/mux"
//	    "github.com/kl-switch/klswitch-sdk/sdk-go"
//	    "github.com/kl-switch/klswitch-sdk/sdk-go/muxware"
//	)
//
//	ks, _ := klswitch.Init(ctx, klswitch.WithToken(os.Getenv("KS_TOKEN")))
//	r := mux.NewRouter()
//	r.Use(muxware.New(ks))
//	r.HandleFunc("/v1/payments/{id}", paymentHandler).Methods("POST")
//
// gorilla/mux templates use `{id}` / `{id:regex}`; rule selectors use
// `:id`. The middleware canonicalises the live template to match —
// same convention muxmanifest uses for the discovery side.
package muxware

import (
	"net/http"
	"regexp"

	"github.com/gorilla/mux"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
	"github.com/kl-switch/klswitch-sdk/sdk-go/httpmiddleware"
)

var paramPattern = regexp.MustCompile(`\{([^:{}]+)(?::[^{}]+)?\}`)

// New returns a gorilla/mux-aware KLSwitch middleware. Composes with
// the generic [httpmiddleware] options — any option valid there is valid
// here too.
//
// IMPORTANT: register this via `router.Use(...)` AFTER your routes are
// declared, or use `Subrouter()` to scope it — gorilla/mux matches the
// route before middleware runs, so `mux.CurrentRoute(r)` only returns
// the matched route when the middleware is registered against a router
// that already knows the routes.
func New(client *ks.Client, opts ...httpmiddleware.Option) func(http.Handler) http.Handler {
	all := append([]httpmiddleware.Option{httpmiddleware.WithPathFunc(routeTemplate)}, opts...)
	return httpmiddleware.New(client, all...)
}

// routeTemplate reads gorilla/mux's matched route template + converts to
// the rule-selector convention. Returns "" when no route was matched
// (404 path, host-only routes, etc.) — the httpmiddleware fall-back
// chain then takes over.
func routeTemplate(r *http.Request) string {
	route := mux.CurrentRoute(r)
	if route == nil {
		return ""
	}
	tpl, err := route.GetPathTemplate()
	if err != nil || tpl == "" {
		return ""
	}
	return ToColonStyle(tpl)
}

// ToColonStyle converts gorilla/mux's `{id}` / `{id:[0-9]+}` syntax to
// the canonical `:id` form. Mirrors muxmanifest.ToColonStyle (kept
// duplicated so the adapter packages stay independent).
func ToColonStyle(pattern string) string {
	if pattern == "" {
		return pattern
	}
	return paramPattern.ReplaceAllStringFunc(pattern, func(match string) string {
		sub := paramPattern.FindStringSubmatch(match)
		if len(sub) < 2 {
			return match
		}
		return ":" + sub[1]
	})
}
