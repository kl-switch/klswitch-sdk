package klswitch

import "strings"

// URL derivation from the config's baseURL (with per-endpoint overrides
// as escape hatches for self-hosted setups).
//
// Centralised so the polling manager and
// analytics reporter (A3) all build their URLs identically.

// DefaultBaseURL is the hosted KLSwitch backend.
const DefaultBaseURL = "https://api.klswitch.dev"

// ResolvedURLs carries the data-plane endpoints, post-resolution.
type ResolvedURLs struct {
	Rules     string
	Decisions string
	Manifest  string
}

// resolveURLs picks each endpoint URL, preferring an explicit override when
// set, otherwise deriving from baseURL (which itself defaults to
// DefaultBaseURL).
func resolveURLs(baseURL, rulesURL, decisionsURL, manifestURL string) ResolvedURLs {
	base := stripTrailingSlash(coalesce(baseURL, DefaultBaseURL))
	return ResolvedURLs{
		Rules:     coalesce(rulesURL, base+"/v1/ruleset"),
		Decisions: coalesce(decisionsURL, base+"/v1/analytics"),
		Manifest:  coalesce(manifestURL, base+"/v1/manifest"),
	}
}

func stripTrailingSlash(s string) string {
	return strings.TrimRight(s, "/")
}

func coalesce(primary, fallback string) string {
	if primary != "" {
		return primary
	}
	return fallback
}
