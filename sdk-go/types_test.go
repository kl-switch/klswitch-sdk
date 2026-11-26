package klswitch

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

// Zero-value Facts must marshal with all five buckets present as empty objects
// (not `null`) — the Rust engine's strict deserializer rejects null buckets.
func TestFacts_MarshalJSON_ZeroValueProducesEmptyBuckets(t *testing.T) {
	out, err := json.Marshal(Facts{})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	got := string(out)
	for _, k := range []string{"headers", "query", "params", "body", "meta"} {
		want := `"` + k + `":{}`
		if !strings.Contains(got, want) {
			t.Errorf("missing %q bucket in: %s", want, got)
		}
	}
	if strings.Contains(got, "null") {
		t.Errorf("zero-value Facts must not produce null buckets, got: %s", got)
	}
}

// A partially-constructed Facts (only Body set) must still marshal with all
// five buckets present. This is the actual footgun fix: users routinely
// construct `Facts{Body: m}` and expect it to work.
func TestFacts_MarshalJSON_PartialConstructFillsMissingBuckets(t *testing.T) {
	f := Facts{Body: map[string]any{"amount": 500}}
	out, err := json.Marshal(f)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	got := string(out)
	if !strings.Contains(got, `"body":{"amount":500}`) {
		t.Errorf("body bucket lost: %s", got)
	}
	for _, k := range []string{"headers", "query", "params", "meta"} {
		if !strings.Contains(got, `"`+k+`":{}`) {
			t.Errorf("missing default %q bucket: %s", k, got)
		}
	}
}

// End-to-end: partial Facts must evaluate successfully through the real engine
// rather than failing with INVALID_INPUT (the bug this fix prevents).
func TestFacts_PartialConstructEvaluatesThroughEngine(t *testing.T) {
	ctx := context.Background()
	rs := decodeRules(t, ruleset)
	ks, err := Init(ctx, WithRules(rs))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	// Only Body set — Headers/Query/Params/Meta are nil maps. Without the
	// MarshalJSON fix this would marshal `"headers":null` and the engine
	// would return INVALID_INPUT.
	r, err := ks.Evaluate(ctx, EvaluationInput{
		Selector: Selector{Kind: "api", Keys: map[string]string{"method": "POST", "path": "/p"}},
		Facts:    Facts{Body: map[string]any{"amount": 500}},
	})
	if err != nil {
		t.Fatalf("Evaluate: %v", err)
	}
	if !r.Ok || len(r.Matched) != 1 || r.Matched[0].ID != "block" {
		t.Fatalf("expected one match (block) from partial Facts, got: %+v", r)
	}
}
