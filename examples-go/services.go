package main

import (
	"encoding/json"
	"net/http"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
	"github.com/kl-switch/klswitch-sdk/sdk-go/httpmiddleware"
)

// Each resource is a dummy stub — the demo's purpose is to show KLSwitch
// integration over real HTTP semantics, not to model a real domain.

func handlePayment(w http.ResponseWriter, r *http.Request) {
	writeOK(w, r, map[string]any{"paymentId": r.PathValue("id")})
}

func handleUser(w http.ResponseWriter, r *http.Request) {
	writeOK(w, r, map[string]any{"userId": r.PathValue("id")})
}

// handleHealth returns the combined engine status so an operator can verify
// the SDK is wired correctly without poking a real endpoint.
func handleHealth(client *ks.Client) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		status, err := client.GetStatus(r.Context())
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "status": status})
	}
}

// writeOK encodes the standard `{ok: true, matched: [...], ...extra}` response
// shape used by all three example servers (Node / Java / Go) so the curl
// outputs line up across languages.
func writeOK(w http.ResponseWriter, r *http.Request, extra map[string]any) {
	result, _ := httpmiddleware.ResultFromContext(r.Context())
	matched := make([]map[string]string, 0, len(result.Matched))
	for _, m := range result.Matched {
		matched = append(matched, map[string]string{"id": m.ID, "action": m.Action.Type})
	}
	out := map[string]any{"ok": true, "matched": matched}
	for k, v := range extra {
		out[k] = v
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(out)
}

