// Package muxmanifest discovers the routes registered on a gorilla/mux router
// and exposes them as a ks.ManifestSource the SDK can push to /v1/manifest.
//
// Usage:
//
//	import (
//	    "github.com/gorilla/mux"
//	    ks "github.com/kl-switch/klswitch-sdk/sdk-go"
//	    "github.com/kl-switch/klswitch-sdk/sdk-go/muxmanifest"
//	)
//
//	r := mux.NewRouter()
//	// ... mount routes ...
//
//	client, _ := ks.Init(ctx,
//	    ks.WithToken(os.Getenv("KS_TOKEN")),
//	    ks.WithManifest(muxmanifest.Source(r)),
//	)
//
// gorilla/mux templates use {name} / {name:regex} — canonicalised to :name to
// match the rule selector convention.
package muxmanifest

import (
	"regexp"
	"strings"

	"github.com/gorilla/mux"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
)

const framework = "gorilla/mux"

var paramPattern = regexp.MustCompile(`\{([^:{}]+)(?::[^{}]+)?\}`)

// Source builds a ks.ManifestSource that walks the mux router on every call.
// Safe to call multiple times — mux's registry is the live truth.
func Source(r *mux.Router) ks.ManifestSource {
	return ks.ManifestSource{
		Framework: framework,
		Collect:   func() ([]ks.ManifestRoute, error) { return Discover(r) },
	}
}

// Discover walks the mux router and emits one ks.ManifestRoute per (method,
// path) pair. A route without a path template (e.g. host-only matchers,
// subrouters with no GetPathTemplate) is skipped; a route without methods
// emits a single entry with method "ANY" so it still surfaces.
func Discover(r *mux.Router) ([]ks.ManifestRoute, error) {
	if r == nil {
		return nil, nil
	}
	var out []ks.ManifestRoute
	err := r.Walk(func(route *mux.Route, _ *mux.Router, _ []*mux.Route) error {
		path, perr := route.GetPathTemplate()
		if perr != nil || path == "" {
			// Routes without a path template (host-only, etc.) — skip.
			return nil
		}
		canonical := ToColonStyle(path)
		methods, merr := route.GetMethods()
		if merr != nil || len(methods) == 0 {
			// No method matcher → handles every verb. Emit ANY so the
			// console at least sees the path; rule selectors won't match
			// ANY but the operator gets visibility.
			out = append(out, ks.ManifestRoute{Method: "ANY", Path: canonical})
			return nil
		}
		for _, m := range methods {
			out = append(out, ks.ManifestRoute{
				Method: strings.ToUpper(m),
				Path:   canonical,
			})
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return out, nil
}

// ToColonStyle converts gorilla/mux's "{id}" / "{id:[0-9]+}" syntax to ":id".
// Exposed for tests + operators inspecting discovered paths.
func ToColonStyle(path string) string {
	if !strings.ContainsRune(path, '{') {
		return path
	}
	return paramPattern.ReplaceAllStringFunc(path, func(match string) string {
		sub := paramPattern.FindStringSubmatch(match)
		if len(sub) < 2 {
			return match
		}
		return ":" + sub[1]
	})
}
