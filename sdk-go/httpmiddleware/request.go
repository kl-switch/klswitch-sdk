package httpmiddleware

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"regexp"
	"strings"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
)

// BuildInput translates a request and a route template into an
// [ks.EvaluationInput], doing the same body/header/query extraction the
// middleware would do — exposed for users who want to call Client.Evaluate
// directly (background processing, testing, custom integrations).
//
// Reads up to maxBodyBytes from the body for JSON fact extraction, then
// restores the body stream so the eventual handler still sees the full
// payload. Header keys are lowercased (parity with Node / Java).
//
// `routeTemplate` empty → the concrete `r.URL.Path` is used.
// `maxBodyBytes` zero or negative → no body is read (body facts are skipped).
// CanonicalizeTemplate accepts any of the three common route-template dialects and returns the
// engine's.
//
// "{id}" (chi, gin, Spring, FastAPI), "<id>" / "<int:id>" (Flask/Werkzeug) and ":id" (engine,
// Express, gorilla/mux) all become ":id".
//
// Applied automatically by BuildInput. A host integrating without an adapter otherwise has to
// know the engine's exact dialect, and the failure when they get it wrong is close to invisible:
// rules whose selectors carry no path parameter still match, so the integration looks healthy
// while every parameterised rule silently misses. Braces and angle brackets are not valid in an
// engine selector, so rewriting them cannot change the meaning of a path that was already
// correct.
func CanonicalizeTemplate(path string) string {
	if path == "" {
		return path
	}
	// <int:id> / <id>  ->  :id   (drop any converter prefix)
	path = angleParam.ReplaceAllString(path, ":$1")
	// {file_path:path} / {id}  ->  :file_path / :id   (drop any converter suffix)
	return braceParam.ReplaceAllString(path, ":$1")
}

var (
	angleParam = regexp.MustCompile(`<(?:[^:>]+:)?([^>]+)>`)
	braceParam = regexp.MustCompile(`\{([^}:]+)(?::[^}]+)?\}`)
)

func BuildInput(r *http.Request, routeTemplate string, maxBodyBytes int64) ks.EvaluationInput {
	if routeTemplate == "" {
		routeTemplate = r.URL.Path
	}
	return buildInputCore(r, routeTemplate, maxBodyBytes)
}

func buildEvaluationInput(r *http.Request, cfg *config) ks.EvaluationInput {
	var template string
	if cfg.pathFunc != nil {
		template = cfg.pathFunc(r)
	}
	if template == "" {
		template = cfg.pathTemplate
	}
	if template == "" {
		template = r.URL.Path
	}
	return buildInputCore(r, template, cfg.maxBodyBytes)
}

func buildInputCore(r *http.Request, template string, maxBodyBytes int64) ks.EvaluationInput {
	facts := ks.NewFacts()

	for k, vs := range r.Header {
		if len(vs) > 0 {
			facts.Headers[strings.ToLower(k)] = vs[0]
		}
	}
	for k, vs := range r.URL.Query() {
		if len(vs) > 0 {
			facts.Query[k] = vs[0]
		}
	}

	if maxBodyBytes > 0 && r.Body != nil && isJSONContent(r.Header.Get("Content-Type")) {
		readBodyForFacts(r, &facts, maxBodyBytes)
	}

	return ks.EvaluationInput{
		Selector: ks.Selector{Kind: "api", Keys: map[string]string{
			"method": r.Method,
			"path":   CanonicalizeTemplate(template),
		}},
		Facts: facts,
	}
}

// readBodyForFacts reads up to maxBodyBytes from r.Body, parses it as JSON
// into facts.Body, and restores r.Body so the handler still sees the full
// payload. When the body exceeds the cap, body facts are skipped (engine
// treats missing fields as leaf-false) but the handler still gets the entire
// stream — mirrors the bounded-read pattern in the Java JAX-RS filter.
func readBodyForFacts(r *http.Request, facts *ks.Facts, maxBodyBytes int64) {
	original := r.Body
	buf, _ := io.ReadAll(io.LimitReader(original, maxBodyBytes+1))
	truncated := int64(len(buf)) > maxBodyBytes

	if truncated {
		// Splice the buffered prefix in front of the remaining original stream
		// so downstream readers see every byte. Skip fact extraction — a
		// truncated JSON wouldn't parse anyway.
		r.Body = struct {
			io.Reader
			io.Closer
		}{
			Reader: io.MultiReader(bytes.NewReader(buf), original),
			Closer: original,
		}
		return
	}

	// Body fully buffered — replace with a fresh reader and parse.
	_ = original.Close()
	r.Body = io.NopCloser(bytes.NewReader(buf))
	if len(buf) == 0 {
		return
	}
	var m map[string]any
	if err := json.Unmarshal(buf, &m); err == nil {
		facts.Body = m
	}
}

func isJSONContent(s string) bool {
	return strings.Contains(strings.ToLower(s), "json")
}
