package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	ks "github.com/kl-switch/klswitch-sdk/sdk-go"
)

// newTestServer boots an in-process HTTP server backed by the demo rules and
// the same routing table the binary uses, so integration tests exercise the
// real KLSwitch flow (Init → middleware → Evaluate → handler) end-to-end.
func newTestServer(t *testing.T) *httptest.Server {
	t.Helper()
	client, err := ks.Init(context.Background(), ks.WithRules(DemoRules))
	if err != nil {
		t.Fatalf("ks.Init: %v", err)
	}
	srv := httptest.NewServer(buildMux(client))
	t.Cleanup(func() {
		srv.Close()
		_ = client.Close()
	})
	return srv
}

func postJSON(t *testing.T, srv *httptest.Server, path, body string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, srv.URL+path, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return res
}

func get(t *testing.T, srv *httptest.Server, path string, headers map[string]string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, srv.URL+path, nil)
	if err != nil {
		t.Fatal(err)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return res
}

// Small payment: no rule matches. 200 OK, empty matched list.
func TestPayment_Small_Allowed(t *testing.T) {
	srv := newTestServer(t)
	res := postJSON(t, srv, "/v1/payments/abc", `{"amount":100}`)
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", res.StatusCode)
	}
	body := decodeBody(t, res)
	if matched := body["matched"].([]any); len(matched) != 0 {
		t.Errorf("expected no matches, got: %v", matched)
	}
	if body["paymentId"] != "abc" {
		t.Errorf("expected paymentId=abc, got: %v", body["paymentId"])
	}
}

// Large payment: block_large_payment fires (gt 50000 → 429).
func TestPayment_Large_Blocked(t *testing.T) {
	srv := newTestServer(t)
	res := postJSON(t, srv, "/v1/payments/abc", `{"amount":70000}`)
	defer res.Body.Close()
	if res.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("expected 429, got %d", res.StatusCode)
	}
	body := decodeBody(t, res)
	if body["ruleId"] != "block_large_payment" {
		t.Errorf("expected ruleId=block_large_payment, got: %v", body["ruleId"])
	}
}

// Restricted region: block_restricted_region fires (country in ["KP","IR"] → 451).
// Also priority-ordered: priority 110 > 100, so this beats block_large_payment when
// both could match (here only restricted-region condition is true since amount < 50k).
func TestPayment_RestrictedRegion_Blocked(t *testing.T) {
	srv := newTestServer(t)
	res := postJSON(t, srv, "/v1/payments/abc", `{"amount":10,"country":"KP"}`)
	defer res.Body.Close()
	if res.StatusCode != http.StatusUnavailableForLegalReasons {
		t.Fatalf("expected 451, got %d", res.StatusCode)
	}
	body := decodeBody(t, res)
	if body["ruleId"] != "block_restricted_region" {
		t.Errorf("expected ruleId=block_restricted_region, got: %v", body["ruleId"])
	}
}

// Admin role via query param: flag_admin_access fires; the request still succeeds
// because `flag` is non-blocking. The matched list surfaces the flag id so a
// downstream handler can act on it.
func TestUser_AdminRole_FlaggedNotBlocked(t *testing.T) {
	srv := newTestServer(t)
	res := get(t, srv, "/v1/users/42?role=admin", nil)
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 (flag is non-blocking), got %d", res.StatusCode)
	}
	body := decodeBody(t, res)
	matched := body["matched"].([]any)
	if len(matched) != 1 {
		t.Fatalf("expected 1 match, got: %v", matched)
	}
	m := matched[0].(map[string]any)
	if m["id"] != "flag_admin_access" || m["action"] != "flag" {
		t.Errorf("expected flag_admin_access (flag), got: %v", m)
	}
}

// x-impersonate header (with no admin query param) also fires flag_admin_access
// via the rule's `any` branch.
func TestUser_ImpersonateHeader_Flagged(t *testing.T) {
	srv := newTestServer(t)
	res := get(t, srv, "/v1/users/42", map[string]string{"X-Impersonate": "ops-user"})
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", res.StatusCode)
	}
	body := decodeBody(t, res)
	matched := body["matched"].([]any)
	if len(matched) != 1 || matched[0].(map[string]any)["id"] != "flag_admin_access" {
		t.Errorf("expected flag_admin_access via header, got: %v", matched)
	}
}

// /health is unguarded by KLSwitch but reports the engine status.
func TestHealth_ReportsEngineStatus(t *testing.T) {
	srv := newTestServer(t)
	res := get(t, srv, "/health", nil)
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", res.StatusCode)
	}
	body := decodeBody(t, res)
	status := body["status"].(map[string]any)
	if status["initialized"] != true || status["last_load_ok"] != true {
		t.Errorf("expected initialized+last_load_ok, got: %v", status)
	}
	if status["rule_count"].(float64) != 3 {
		t.Errorf("expected rule_count=3, got: %v", status["rule_count"])
	}
}

func decodeBody(t *testing.T, res *http.Response) map[string]any {
	t.Helper()
	raw, err := io.ReadAll(res.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	var body map[string]any
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatalf("decode body: %v\nraw=%s", err, raw)
	}
	return body
}
