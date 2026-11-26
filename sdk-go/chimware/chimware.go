// Package chimware is the dedicated chi middleware for KLSwitch.
//
// Why this exists: chi populates [chi.RouteContext.RoutePattern] AFTER
// routing, but middleware registered via [chi.Router.Use] runs BEFORE
// routing. So a naive `r.Use(httpmiddleware.New(client))` can't read
// the templated route — and the rule selector wouldn't match.
//
// chimware solves this by taking the router itself and calling
// [chi.Routes.Match] inside the middleware. Match pre-resolves the
// route and populates the route context with the template + url params,
// before evaluation runs. Chi's own routing later re-uses the same
// match (idempotent) so there's no wasted work.
//
// Usage:
//
//	import (
//	    "github.com/go-chi/chi/v5"
//	    "github.com/kl-switch/klswitch-sdk/sdk-go"
//	    "github.com/kl-switch/klswitch-sdk/sdk-go/chimware"
//	)
//
//	ks, _ := klswitch.Init(ctx, klswitch.WithToken(os.Getenv("KS_TOKEN")))
//	r := chi.NewRouter()
//	r.Use(chimware.New(r, ks))           // pass the router itself
//	r.Post("/v1/payments/{id}", paymentHandler)
//
// chi templates use `{id}`; rule selectors use `:id`. The middleware
// canonicalises automatically.
package chimware

import (
	"net/http"
	"regexp"

	"github.com/go-chi/chi/v5"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
	"github.com/kl-switch/klswitch-sdk/sdk-go/httpmiddleware"
)

var paramPattern = regexp.MustCompile(`\{([^:{}]+)(?::[^{}]+)?\}`)

// New returns a chi-aware KLSwitch middleware. Pass the same router
// you'll register the middleware on — chimware uses [chi.Routes.Match]
// to read the templated route before evaluation.
//
// Composes with the generic [httpmiddleware] options. Any
// WithPathFunc the operator passes here is overridden (the whole
// point of chimware is the path resolution).
func New(router chi.Routes, client *ks.Client, opts ...httpmiddleware.Option) func(http.Handler) http.Handler {
	// Build the PathFunc bound to THIS router so the middleware doesn't
	// rely on chi.RouteContext being pre-populated.
	pathFunc := func(r *http.Request) string {
		rctx := chi.NewRouteContext()
		if !router.Match(rctx, r.Method, r.URL.Path) {
			return ""
		}
		return ToColonStyle(rctx.RoutePattern())
	}
	all := append([]httpmiddleware.Option{httpmiddleware.WithPathFunc(pathFunc)}, opts...)
	return httpmiddleware.New(client, all...)
}

// ToColonStyle converts chi's `{id}` / `{id:[0-9]+}` syntax to the
// canonical `:id` form used by rule selectors. Mirrors
// chimanifest.ToColonStyle — duplicated to keep adapter packages
// independent.
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
