package main

import (
	"context"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
	"github.com/kl-switch/klswitch-sdk/sdk-go/httpmiddleware"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	opts := []ks.Option{
		ks.WithRules(DemoRules),
		ks.WithLogger(ks.SlogLogger{L: slog.New(slog.NewTextHandler(os.Stderr, nil))}),
	}
	// When KS_TOKEN is set, point at a real backend (klswitch) and emit analytics;
	// the per-(service,env) API key lets the backend derive service + env.
	if tok := os.Getenv("KS_TOKEN"); tok != "" {
		base := os.Getenv("KS_BASE_URL")
		if base == "" {
			base = "http://localhost:40010"
		}
		// Go enables the HTTP analytics transport automatically when a token is
		// present; WithAnalytics is reserved for custom sinks (its validation
		// requires a Sink), so we don't call it here — events flush on the
		// default interval.
		opts = append(opts,
			ks.WithToken(tok),
			ks.WithBaseURL(base),
			// stdlib http.ServeMux exposes no route registry, so we declare the API surface
			// explicitly (a supported custom ManifestSource) — powers the console's APIs tab.
			ks.WithManifest(ks.ManifestSource{
				Framework: "net/http",
				Collect: func() ([]ks.ManifestRoute, error) {
					return []ks.ManifestRoute{
						{Method: "POST", Path: "/v1/payments/:id"},
						{Method: "GET", Path: "/v1/users/:id"},
					}, nil
				},
			}),
		)
	}
	client, err := ks.Init(ctx, opts...)
	if err != nil {
		log.Fatalf("KLSwitch init failed: %v", err)
	}
	defer client.Close()

	srv := &http.Server{
		Addr:              addrFromEnv(),
		Handler:           buildMux(client),
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		log.Printf("listening on %s — try GET /health", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}()

	<-ctx.Done()
	log.Println("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}

// buildMux is split out so the integration tests can exercise the same routing
// table as the running server.
func buildMux(client *ks.Client) *http.ServeMux {
	mux := http.NewServeMux()

	// The SDK's packaged middleware does what the prototype Guard used to —
	// path-template passed explicitly because Go 1.22's enhanced ServeMux
	// doesn't expose the matched pattern back to handlers.
	paymentsGuard := httpmiddleware.New(client,
		httpmiddleware.WithPathTemplate("/v1/payments/:id"))
	usersGuard := httpmiddleware.New(client,
		httpmiddleware.WithPathTemplate("/v1/users/:id"))

	mux.Handle("POST /v1/payments/{id}", paymentsGuard(http.HandlerFunc(handlePayment)))
	mux.Handle("GET /v1/users/{id}", usersGuard(http.HandlerFunc(handleUser)))
	mux.HandleFunc("GET /health", handleHealth(client))
	return mux
}

func addrFromEnv() string {
	if a := os.Getenv("PORT"); a != "" {
		return ":" + a
	}
	return ":8087"
}
