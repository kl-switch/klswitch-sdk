package httpmiddleware

// Option configures [New]. Compose multiple options in a single call.
type Option func(*config)

// DefaultMaxBodyBytes caps the body read for fact extraction by default.
// Bigger bodies are streamed through to the handler without being buffered in
// the middleware — same DoS-protection guarantee as the Java JAX-RS filter.
const DefaultMaxBodyBytes = 1 << 20 // 1 MiB

// config is the assembled middleware configuration.
type config struct {
	pathTemplate  string
	pathFunc      PathFunc
	maxBodyBytes  int64
	resultHandler ResultHandler
	errorHandler  ErrorHandler
}

func buildConfig(opts []Option) *config {
	c := &config{
		maxBodyBytes:  DefaultMaxBodyBytes,
		resultHandler: DefaultResultHandler,
		errorHandler:  DefaultErrorHandler,
	}
	for _, o := range opts {
		if o != nil {
			o(c)
		}
	}
	return c
}

// WithPathTemplate sets a fixed rule-selector path. Use this when your router
// doesn't expose the matched template back to handlers (stdlib net/http or
// Go 1.22 ServeMux), and you mount the middleware per route:
//
//	mux.Handle("POST /v1/payments/{id}",
//	    httpmiddleware.New(ks, httpmiddleware.WithPathTemplate("/v1/payments/:id"))(handler))
//
// Combines with [WithPathFunc]: PathFunc is tried first; if it returns "",
// the template is used; if both are empty, `r.URL.Path` (concrete path) is the
// fallback.
func WithPathTemplate(template string) Option {
	return func(c *config) { c.pathTemplate = template }
}

// WithPathFunc resolves the rule-selector path dynamically per request — for
// routers that track the matched route template (chi, gorilla/mux):
//
//	httpmiddleware.WithPathFunc(func(r *http.Request) string {
//	    return chi.RouteContext(r.Context()).RoutePattern()
//	})
//
// If the PathFunc returns "", the middleware falls back to [WithPathTemplate]
// then to `r.URL.Path`.
func WithPathFunc(fn PathFunc) Option {
	return func(c *config) { c.pathFunc = fn }
}

// WithResultHandler replaces the default block-on-block policy. The handler
// receives the [ks.EvaluationResult] and either writes a short-circuit response
// or calls `next.ServeHTTP(w, r)` to continue.
func WithResultHandler(h ResultHandler) Option {
	return func(c *config) {
		if h != nil {
			c.resultHandler = h
		}
	}
}

// WithErrorHandler replaces the default 500-on-Evaluate-failure handler.
func WithErrorHandler(h ErrorHandler) Option {
	return func(c *config) {
		if h != nil {
			c.errorHandler = h
		}
	}
}

// WithMaxBodyBytes caps the bytes read from the request body for fact
// extraction. Bigger bodies stream through to the handler unbuffered, so the
// middleware can't be DoS'd by an oversized JSON payload. Defaults to
// [DefaultMaxBodyBytes] (1 MiB). A non-positive value is ignored.
func WithMaxBodyBytes(n int64) Option {
	return func(c *config) {
		if n > 0 {
			c.maxBodyBytes = n
		}
	}
}
