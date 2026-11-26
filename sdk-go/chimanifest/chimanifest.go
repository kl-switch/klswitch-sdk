// Package chimanifest discovers the routes registered on a chi router and
// exposes them as a ks.ManifestSource the SDK can push to /v1/manifest.
//
// Usage:
//
//	import (
//	    "github.com/go-chi/chi/v5"
//	    ks "github.com/kl-switch/klswitch-sdk/sdk-go"
//	    "github.com/kl-switch/klswitch-sdk/sdk-go/chimanifest"
//	)
//
//	r := chi.NewRouter()
//	// ... mount routes ...
//
//	client, _ := ks.Init(ctx,
//	    ks.WithToken(os.Getenv("KS_TOKEN")),
//	    ks.WithManifest(chimanifest.Source(r)),
//	)
//
// chi's path templates use the {name} / {name:regex} syntax — we canonicalise
// to the :name form rule selectors use across wrappers.
package chimanifest

import (
	"net/http"
	"regexp"
	"strings"

	"github.com/go-chi/chi/v5"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
)

// framework is the wire-format identifier sent in the manifest payload.
const framework = "chi"

// Matches "{name}" and "{name:regex}" — we strip the regex spec since rule
// selectors don't track types.
var paramPattern = regexp.MustCompile(`\{([^:{}]+)(?::[^{}]+)?\}`)

// Source builds a ks.ManifestSource that walks the chi router on every call.
// Safe to call multiple times — chi's registry is the live truth and may
// have grown between init and an explicit Client.ReportManifest.
func Source(r chi.Routes) ks.ManifestSource {
	return ks.ManifestSource{
		Framework: framework,
		Collect:   func() ([]ks.ManifestRoute, error) { return Discover(r) },
	}
}

// Discover walks the chi router and returns one ks.ManifestRoute per
// (method, path) pair. Exported so operators can inspect the result.
func Discover(r chi.Routes) ([]ks.ManifestRoute, error) {
	if r == nil {
		return nil, nil
	}
	var out []ks.ManifestRoute
	walk := func(method, route string, handler http.Handler, _ ...func(http.Handler) http.Handler) error {
		out = append(out, ks.ManifestRoute{
			Method: strings.ToUpper(method),
			Path:   ToColonStyle(route),
		})
		return nil
	}
	if err := chi.Walk(r, walk); err != nil {
		return nil, err
	}
	return out, nil
}

// ToColonStyle converts chi's "{id}" / "{id:\\d+}" syntax to the canonical
// ":id" form used by rule selectors. Exposed for tests + operators inspecting
// discovered paths.
func ToColonStyle(path string) string {
	if !strings.ContainsRune(path, '{') {
		return path
	}
	return paramPattern.ReplaceAllStringFunc(path, func(match string) string {
		// Extract the param name (sub-match) — ReplaceAllStringFunc doesn't
		// expose submatches, so reuse the same pattern.
		sub := paramPattern.FindStringSubmatch(match)
		if len(sub) < 2 {
			return match
		}
		return ":" + sub[1]
	})
}
