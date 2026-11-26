package klswitch

import (
	"encoding/json"
	"testing"
)

// The regression: a fetched ruleset is decoded into the typed Rule and re-encoded before it
// reaches the engine, and Rule had no Effective field — so the window was silently dropped in
// transit. Scheduled and expiring rules simply did not work: an expired rule kept firing and a
// future rule fired early, with no error anywhere.
//
// The 50 compliance fixtures cover effective windows and all passed throughout, because they
// build rules through the typed constructor rather than decoding raw JSON. The fetch → decode →
// re-encode path was never exercised. This covers exactly that path.
func TestEffectiveWindowSurvivesRoundTrip(t *testing.T) {
	raw := `{"schema_version":"1.0","rules_version":1,"rules":[{
		"id":"r","name":"R","enabled":true,"priority":10,
		"selector":{"kind":"api","keys":{"method":"POST","path":"/x"}},
		"condition":{"field":"body.u","operator":"exists"},
		"action":{"type":"block"},
		"effective":{"from":1600000000000,"to":1700000000000}}]}`

	var payload RuleSetPayload
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(payload.Rules) != 1 {
		t.Fatalf("expected 1 rule, got %d", len(payload.Rules))
	}
	if payload.Rules[0].Effective == nil {
		t.Fatal("Effective was dropped on decode — scheduling silently stops working")
	}

	out, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	var back map[string]any
	if err := json.Unmarshal(out, &back); err != nil {
		t.Fatalf("re-decode: %v", err)
	}
	rule := back["rules"].([]any)[0].(map[string]any)
	eff, ok := rule["effective"].(map[string]any)
	if !ok {
		t.Fatal("Effective was dropped on re-encode — the engine never sees the window")
	}
	if eff["from"].(float64) != 1600000000000 || eff["to"].(float64) != 1700000000000 {
		t.Fatalf("window corrupted in transit: %v", eff)
	}
}

// A rule with no window must round-trip without gaining an empty one — omitempty keeps the
// payload identical to what the backend published.
func TestRuleWithoutWindowStaysWithoutOne(t *testing.T) {
	raw := `{"schema_version":"1.0","rules_version":1,"rules":[{
		"id":"r","name":"R","enabled":true,
		"selector":{"kind":"api","keys":{"method":"POST","path":"/x"}},
		"condition":{"field":"body.u","operator":"exists"},
		"action":{"type":"block"}}]}`

	var payload RuleSetPayload
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		t.Fatalf("decode: %v", err)
	}
	out, _ := json.Marshal(payload)
	var back map[string]any
	_ = json.Unmarshal(out, &back)
	rule := back["rules"].([]any)[0].(map[string]any)
	if _, present := rule["effective"]; present {
		t.Fatal("a rule with no window must not gain one")
	}
}
