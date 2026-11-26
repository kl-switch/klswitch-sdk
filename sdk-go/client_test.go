package klswitch

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"
)

func decodeRules(t *testing.T, s string) RuleSetPayload {
	t.Helper()
	var rs RuleSetPayload
	if err := json.Unmarshal([]byte(s), &rs); err != nil {
		t.Fatalf("decode ruleset: %v", err)
	}
	return rs
}

func decodeInput(t *testing.T, s string) EvaluationInput {
	t.Helper()
	var in EvaluationInput
	if err := json.Unmarshal([]byte(s), &in); err != nil {
		t.Fatalf("decode evaluation input: %v", err)
	}
	return in
}

func TestClient_InitEvaluateStatusClose(t *testing.T) {
	ctx := context.Background()
	rs := decodeRules(t, ruleset)
	in := decodeInput(t, inputMatch)

	ks, err := Init(ctx, WithRules(rs))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}

	st, err := ks.GetStatus(ctx)
	if err != nil {
		t.Fatalf("GetStatus: %v", err)
	}
	if !st.Initialized || !st.LastLoadOk {
		t.Errorf("expected initialized + last_load_ok, got %+v", st)
	}
	if st.ActiveRulesVersion == nil || *st.ActiveRulesVersion != 12 {
		t.Errorf("expected active_rules_version=12, got %+v", st.ActiveRulesVersion)
	}

	r, err := ks.Evaluate(ctx, in)
	if err != nil {
		t.Fatalf("Evaluate: %v", err)
	}
	if len(r.Matched) != 1 || r.Matched[0].ID != "block" {
		t.Errorf("expected one match (block), got %+v", r.Matched)
	}

	if err := ks.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	// Closed status must NOT error and must report not-initialized.
	st2, err := ks.GetStatus(ctx)
	if err != nil || st2.Initialized {
		t.Errorf("expected not-initialized status after close (err=%v): %+v", err, st2)
	}
}

func TestClient_InitFailsClearlyOnInvalidRules(t *testing.T) {
	ctx := context.Background()
	bad := decodeRules(t, `{"schema_version":"1.0","rules_version":1,"rules":[
		{"id":"bad","name":"B","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
		 "enabled":true,
		 "condition":{"all":[{"field":"body.x","operator":"bogus_op","value":1}]},
		 "action":{"type":"block"}}]}`)
	_, err := Init(ctx, WithRules(bad))
	if err == nil {
		t.Fatalf("expected init to fail on invalid rules")
	}
	if !strings.Contains(err.Error(), "failed to load") {
		t.Errorf("unexpected error message: %v", err)
	}
}

func TestClient_InitFailsWithNoRulesAndNoToken(t *testing.T) {
	// No inline rules + no token + no custom fetcher → validate() rejects
	// with errTokenRequired.
	_, err := Init(context.Background())
	if err == nil {
		t.Fatalf("expected init to fail with no token + no rules")
	}
	if !strings.Contains(err.Error(), "token is required") {
		t.Errorf("unexpected error message: %v", err)
	}
}

func TestClient_InstanceIDIsExposedAfterInit(t *testing.T) {
	ctx := context.Background()
	rs := decodeRules(t, ruleset)
	ks, err := Init(ctx, WithRules(rs), WithInstanceID("pod-7f4a"))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()
	if ks.InstanceID() != "pod-7f4a" {
		t.Errorf("expected instanceID=pod-7f4a, got %q", ks.InstanceID())
	}
}

func TestClient_URLsAndSDKVersionExposed(t *testing.T) {
	ctx := context.Background()
	rs := decodeRules(t, ruleset)
	ks, err := Init(ctx, WithRules(rs))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()
	urls := ks.URLs()
	if urls.Rules != "https://api.klswitch.dev/v1/ruleset" {
		t.Errorf("expected default rules URL, got %q", urls.Rules)
	}
	if urls.Decisions != "https://api.klswitch.dev/v1/analytics" {
		t.Errorf("expected default decisions URL, got %q", urls.Decisions)
	}
	if ks.SDKVersion() == "" {
		t.Errorf("expected non-empty SDKVersion")
	}
}

func TestClient_BaseURLOverridesEveryEndpoint(t *testing.T) {
	ctx := context.Background()
	rs := decodeRules(t, ruleset)
	ks, err := Init(ctx, WithRules(rs), WithBaseURL("http://self-hosted:8080"))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()
	urls := ks.URLs()
	if urls.Rules != "http://self-hosted:8080/v1/ruleset" {
		t.Errorf("expected baseURL to flow through, got %q", urls.Rules)
	}
}

func TestClient_EvaluateAfterCloseFails(t *testing.T) {
	ctx := context.Background()
	rs := decodeRules(t, ruleset)
	ks, err := Init(ctx, WithRules(rs))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	_ = ks.Close()
	_, err = ks.Evaluate(ctx, decodeInput(t, inputMatch))
	if err == nil || !strings.Contains(err.Error(), "not initialized") {
		t.Errorf("expected 'not initialized' error after close, got: %v", err)
	}
}

// A shared Client hit by many goroutines (the Spring-singleton equivalent) must
// serialize engine access and never corrupt the single WASM instance.
func TestClient_ConcurrentEvaluateIsSafe(t *testing.T) {
	ctx := context.Background()
	rs := decodeRules(t, ruleset)
	in := decodeInput(t, inputMatch)
	ks, err := Init(ctx, WithRules(rs))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	const (
		goroutines = 8
		perG       = 250
	)
	var wg sync.WaitGroup
	wg.Add(goroutines)
	errs := make(chan error, goroutines*perG)
	for g := 0; g < goroutines; g++ {
		go func() {
			defer wg.Done()
			for i := 0; i < perG; i++ {
				r, err := ks.Evaluate(ctx, in)
				if err != nil {
					errs <- err
					return
				}
				if !r.Ok || len(r.Matched) != 1 || r.Matched[0].ID != "block" {
					errs <- &concurrentMismatch{}
					return
				}
			}
		}()
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		t.Fatalf("concurrent evaluate failure: %v", e)
	}
}

type concurrentMismatch struct{}

func (concurrentMismatch) Error() string { return "unexpected concurrent evaluation result" }

// Every call to Evaluate records exactly one analytics event with the right
// fields. Verifies the integration end-to-end (not just the reporter).
func TestClient_AnalyticsRecordsOneEventPerEvaluate(t *testing.T) {
	ctx := context.Background()
	rs := decodeRules(t, ruleset)
	in := decodeInput(t, inputMatch)

	var (
		mu     sync.Mutex
		events []AnalyticsEvent
	)
	sink := func(batch []AnalyticsEvent) error {
		mu.Lock()
		defer mu.Unlock()
		events = append(events, batch...)
		return nil
	}

	ks, err := Init(ctx,
		WithRules(rs),
		WithAnalytics(AnalyticsOptions{
			Sink:          sink,
			BatchSize:     1, // flush per event so the test sees them promptly
			FlushInterval: 50 * time.Millisecond,
		}),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	for i := 0; i < 4; i++ {
		if _, err := ks.Evaluate(ctx, in); err != nil {
			t.Fatalf("Evaluate: %v", err)
		}
	}

	if !waitFor(t, 2*time.Second, func() bool {
		mu.Lock()
		defer mu.Unlock()
		return len(events) == 4
	}) {
		t.Fatalf("expected 4 analytics events, got %d", len(events))
	}

	mu.Lock()
	defer mu.Unlock()
	e := events[0]
	if e.Type != "evaluation" {
		t.Errorf("expected event type=evaluation, got %q", e.Type)
	}
	if len(e.Matched) != 1 || e.Matched[0].RuleID != "block" {
		t.Errorf("expected matched=[block], got %v", e.Matched)
	}
	if e.Matched[0].ActionType != "block" {
		t.Errorf("expected actionType=block, got %v", e.Matched[0].ActionType)
	}
	if e.Evaluated != 1 {
		t.Errorf("expected evaluated=1, got %d", e.Evaluated)
	}
	if e.RulesVersion != 12 {
		t.Errorf("expected RulesVersion=12, got %v", e.RulesVersion)
	}
	if e.Request.Method != "POST" || e.Request.Path != "/p" {
		t.Errorf("expected request POST /p, got %+v", e.Request)
	}
	if e.LatencyBucket == "" {
		t.Errorf("expected non-empty latency bucket")
	}
	if e.OccurredAt == "" {
		t.Errorf("expected non-empty occurredAt")
	}
}

// A throwing analytics sink doesn't break Evaluate: errors are logged and
// dropped, the evaluation result still comes back cleanly.
func TestClient_AnalyticsSinkErrorDoesNotBreakEvaluate(t *testing.T) {
	ctx := context.Background()
	rs := decodeRules(t, ruleset)
	in := decodeInput(t, inputMatch)
	sink := func([]AnalyticsEvent) error { return assertErr("sink boom") }

	ks, err := Init(ctx,
		WithRules(rs),
		WithAnalytics(AnalyticsOptions{Sink: sink, BatchSize: 1, FlushInterval: 50 * time.Millisecond}),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	r, err := ks.Evaluate(ctx, in)
	if err != nil {
		t.Fatalf("Evaluate must not surface sink errors; got %v", err)
	}
	if len(r.Matched) != 1 {
		t.Errorf("expected one match, got %+v", r.Matched)
	}
}

type assertErr string

func (e assertErr) Error() string { return string(e) }
