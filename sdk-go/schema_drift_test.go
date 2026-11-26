package klswitch

import (
	"reflect"
	"testing"
)

// B3 — Schema-drift Layer 3 (missing fields) tests for the Go wrapper.
// Mirrors sdk-node/test/schema-drift.test.ts.

// fixtures ────────────────────────────────────────────────────────────────

func ruleFx(id string, cond ConditionNode) Rule {
	return Rule{
		ID:        id,
		Name:      id,
		Selector:  Selector{Kind: "api", Keys: map[string]string{"method": "POST", "path": "/p"}},
		Enabled:   true,
		Condition: cond,
		Action:    Action{Type: "block"},
	}
}

func inputFx(body, headers, query map[string]any) EvaluationInput {
	return EvaluationInput{
		Selector: Selector{Kind: "api", Keys: map[string]string{"method": "POST", "path": "/p"}},
		Facts: Facts{
			Headers: headers,
			Query:   query,
			Params:  map[string]any{},
			Body:    body,
			Meta:    map[string]any{},
		},
	}
}

func matchedFx(ids ...string) []MatchedRule {
	out := make([]MatchedRule, len(ids))
	for i, id := range ids {
		out[i] = MatchedRule{ID: id, Name: id, Action: Action{Type: "block"}}
	}
	return out
}

// hasField ────────────────────────────────────────────────────────────────

func TestSchemaDrift_HasField_DottedPath(t *testing.T) {
	in := inputFx(map[string]any{
		"transaction": map[string]any{"amount": 100},
	}, nil, nil)
	if !hasField(in, "body.transaction.amount") {
		t.Error("body.transaction.amount should be present")
	}
	if hasField(in, "body.transaction.currency") {
		t.Error("body.transaction.currency should be absent")
	}
	if hasField(in, "body.absent") {
		t.Error("body.absent should be absent")
	}
}

func TestSchemaDrift_HasField_NilBucket(t *testing.T) {
	// Facts with nil body (operator never set a body).
	in := EvaluationInput{
		Selector: Selector{Kind: "api", Keys: map[string]string{"method": "POST", "path": "/p"}},
		Facts:    Facts{},
	}
	if hasField(in, "body.amount") {
		t.Error("nil body bucket should report absent")
	}
}

func TestSchemaDrift_HasField_ZeroAndEmptyStringArePresent(t *testing.T) {
	in := inputFx(map[string]any{"coupon": "", "amount": 0}, nil, nil)
	if !hasField(in, "body.coupon") {
		t.Error("empty string is a real value — should be present")
	}
	if !hasField(in, "body.amount") {
		t.Error("zero is a real value — should be present")
	}
}

func TestSchemaDrift_HasField_NilIsAbsent(t *testing.T) {
	in := inputFx(map[string]any{"coupon": nil}, nil, nil)
	if hasField(in, "body.coupon") {
		t.Error("nil value should be absent (matches engine semantics)")
	}
}

func TestSchemaDrift_HasField_DashedKey(t *testing.T) {
	in := inputFx(nil, map[string]any{"x-api-key": "abc"}, nil)
	if !hasField(in, "headers.x-api-key") {
		t.Error("dashed header key should resolve")
	}
	if hasField(in, "headers.x-missing") {
		t.Error("absent header should be absent")
	}
}

func TestSchemaDrift_HasField_RejectsEmptyPath(t *testing.T) {
	if hasField(inputFx(nil, nil, nil), "") {
		t.Error("empty path should be absent")
	}
}

// computeMissingFields ────────────────────────────────────────────────────

func TestSchemaDrift_NoMatchedRules(t *testing.T) {
	rules := []Rule{ruleFx("r", ConditionNode{Field: "body.amount", Operator: "gt", Value: 0})}
	got := computeMissingFields(nil, rulesByID(rules), inputFx(map[string]any{}, nil, nil))
	if len(got) != 0 {
		t.Errorf("expected empty, got %v", got)
	}
	// Regression: must be a non-nil empty slice so it marshals as `[]`, never
	// `null`. A null missingFields is rejected by the backend (@NotNull) → 422.
	if got == nil {
		t.Error("missingFields must be non-nil ([]) for no-match, got nil (would marshal as null)")
	}
}

// Regression: a rule matches but references no absent field → still non-nil [].
func TestSchemaDrift_MatchedNoMissingFieldsIsNonNil(t *testing.T) {
	rules := []Rule{ruleFx("r", ConditionNode{Field: "body.amount", Operator: "gt", Value: 0})}
	got := computeMissingFields(
		matchedFx("r"),
		rulesByID(rules),
		inputFx(map[string]any{"amount": 500}, nil, nil), // amount present → nothing missing
	)
	if len(got) != 0 {
		t.Errorf("expected empty, got %v", got)
	}
	if got == nil {
		t.Error("missingFields must be non-nil ([]) when nothing is missing, got nil")
	}
}

func TestSchemaDrift_MatchedReferencingMissingField(t *testing.T) {
	rules := []Rule{ruleFx("r", ConditionNode{Field: "body.amount", Operator: "gt", Value: 0})}
	got := computeMissingFields(
		matchedFx("r"),
		rulesByID(rules),
		inputFx(map[string]any{}, nil, nil),
	)
	if !reflect.DeepEqual(got, []string{"body.amount"}) {
		t.Errorf("got %v, want [body.amount]", got)
	}
}

func TestSchemaDrift_MatchedWithFieldPresent(t *testing.T) {
	rules := []Rule{ruleFx("r", ConditionNode{Field: "body.amount", Operator: "gt", Value: 0})}
	got := computeMissingFields(
		matchedFx("r"),
		rulesByID(rules),
		inputFx(map[string]any{"amount": 5}, nil, nil),
	)
	if len(got) != 0 {
		t.Errorf("expected empty, got %v", got)
	}
}

func TestSchemaDrift_WalksNestedAllAnyNot(t *testing.T) {
	rules := []Rule{ruleFx("r", ConditionNode{
		Any: []ConditionNode{
			{All: []ConditionNode{
				{Field: "body.amount", Operator: "gt", Value: 0},
				{Field: "body.country", Operator: "in", Value: []any{"US"}},
			}},
			{Not: &ConditionNode{Field: "headers.x-api-key", Operator: "exists"}},
		},
	})}
	got := computeMissingFields(
		matchedFx("r"),
		rulesByID(rules),
		inputFx(map[string]any{"amount": 5}, map[string]any{}, nil),
	)
	want := []string{"body.country", "headers.x-api-key"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %v, want %v", got, want)
	}
}

func TestSchemaDrift_DedupAcrossRules(t *testing.T) {
	rules := []Rule{
		ruleFx("r1", ConditionNode{Field: "body.amount", Operator: "gt", Value: 0}),
		ruleFx("r2", ConditionNode{Field: "body.amount", Operator: "lt", Value: 10}),
	}
	got := computeMissingFields(
		matchedFx("r1", "r2"),
		rulesByID(rules),
		inputFx(map[string]any{}, nil, nil),
	)
	if !reflect.DeepEqual(got, []string{"body.amount"}) {
		t.Errorf("got %v, want [body.amount]", got)
	}
}

func TestSchemaDrift_SortedAlphabetically(t *testing.T) {
	rules := []Rule{ruleFx("r", ConditionNode{All: []ConditionNode{
		{Field: "headers.x-zeta", Operator: "exists"},
		{Field: "body.alpha", Operator: "exists"},
		{Field: "query.middle", Operator: "exists"},
	}})}
	got := computeMissingFields(
		matchedFx("r"),
		rulesByID(rules),
		inputFx(map[string]any{}, map[string]any{}, map[string]any{}),
	)
	want := []string{"body.alpha", "headers.x-zeta", "query.middle"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %v, want %v", got, want)
	}
}

func TestSchemaDrift_EveryLeafOperatorTreatedTheSame(t *testing.T) {
	rules := []Rule{ruleFx("ops", ConditionNode{Any: []ConditionNode{
		{Field: "body.a", Operator: "eq", Value: 1},
		{Field: "body.b", Operator: "neq", Value: 1},
		{Field: "body.c", Operator: "gt", Value: 0},
		{Field: "body.d", Operator: "lt", Value: 0},
		{Field: "body.e", Operator: "gte", Value: 0},
		{Field: "body.f", Operator: "lte", Value: 0},
		{Field: "body.g", Operator: "in", Value: []any{1}},
		{Field: "body.h", Operator: "not_in", Value: []any{1}},
		{Field: "body.i", Operator: "exists"},
		{Field: "body.j", Operator: "not_exists"},
	}})}
	got := computeMissingFields(
		matchedFx("ops"),
		rulesByID(rules),
		inputFx(map[string]any{}, nil, nil),
	)
	want := []string{
		"body.a", "body.b", "body.c", "body.d", "body.e",
		"body.f", "body.g", "body.h", "body.i", "body.j",
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %v, want %v", got, want)
	}
}

func TestSchemaDrift_MatchedRuleNotInIndex(t *testing.T) {
	got := computeMissingFields(
		matchedFx("ghost"),
		map[string]Rule{},
		inputFx(map[string]any{}, nil, nil),
	)
	if len(got) != 0 {
		t.Errorf("expected empty, got %v", got)
	}
}
