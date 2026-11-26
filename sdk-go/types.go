package klswitch

import "encoding/json"

// Public JSON-contract types, mirroring `spec/schemas/`. These are the shapes
// exchanged with the WASM engine; the wrapper does not interpret them.

// Operator is the leaf comparison operator (v1 enum).
type Operator string

const (
	OpEq          Operator = "eq"
	OpNeq         Operator = "neq"
	OpGt          Operator = "gt"
	OpGte         Operator = "gte"
	OpLt          Operator = "lt"
	OpLte         Operator = "lte"
	OpContains    Operator = "contains"
	OpNotContains Operator = "not_contains"
	OpIn          Operator = "in"
	OpNotIn       Operator = "not_in"
	OpExists      Operator = "exists"
	OpNotExists   Operator = "not_exists"
)

// Selector — v1 supports only API selectors (`kind: "api"`).
type Selector struct {
	Kind string            `json:"kind"`
	Keys map[string]string `json:"keys"`
}

// Action is an opaque (to the engine) outcome returned to the host for matched rules.
type Action struct {
	Type    string         `json:"type"`
	Payload map[string]any `json:"payload,omitempty"`
}

// ConditionNode is the recursive boolean tree: exactly one of `All`, `Any`,
// `Not`, or the leaf fields (`Field`+`Operator`+`Value`) should be populated.
// JSON tags use `omitempty` so the marshalled shape always picks the right variant.
type ConditionNode struct {
	All []ConditionNode `json:"all,omitempty"`
	Any []ConditionNode `json:"any,omitempty"`
	Not *ConditionNode  `json:"not,omitempty"`
	// Leaf fields:
	Field    string   `json:"field,omitempty"`
	Operator Operator `json:"operator,omitempty"`
	// Value is nil for `exists`/`not_exists`; scalar/array for the others.
	Value any `json:"value,omitempty"`
}

// Rule mirrors a single entry of `ruleset.schema.json` `rules[]`.
type Rule struct {
	ID        string         `json:"id"`
	Name      string         `json:"name"`
	Selector  Selector       `json:"selector"`
	Enabled   bool           `json:"enabled"`
	Priority  *int           `json:"priority,omitempty"`
	Condition ConditionNode  `json:"condition"`
	Action    Action         `json:"action"`
	Tags      []string       `json:"tags,omitempty"`
	Metadata  map[string]any `json:"metadata,omitempty"`

	// Effective is the rule's active window, {"from": epochMs, "to": epochMs}; either bound
	// may be absent.
	//
	// Kept as an opaque map rather than a typed pair because the engine owns the semantics and
	// this wrapper only has to relay it faithfully. That is the point: a fetched ruleset is
	// decoded into this struct and re-encoded before reaching the engine, so a field missing
	// here is silently dropped in transit. It was, and scheduled and expiring rules simply did
	// not work in Go — an expired rule kept firing and a future rule fired early, with no error
	// anywhere. See TestEffectiveWindowSurvivesRoundTrip.
	Effective map[string]any `json:"effective,omitempty"`
}

// RuleSetPayload is the full ruleset passed to the engine (verbatim from a
// remote rules API). `RulesVersion` is `any` because the schema accepts either
// an integer or a string.
type RuleSetPayload struct {
	SchemaVersion string `json:"schema_version"`
	RulesVersion  any    `json:"rules_version"`
	Rules         []Rule `json:"rules"`
}

// Facts are the five fact buckets for condition field paths. All five MUST be
// present (per `evaluation-input.schema.json`); use [NewFacts] to start with
// non-nil empty maps and avoid `null` serialization.
type Facts struct {
	Headers map[string]any `json:"headers"`
	Query   map[string]any `json:"query"`
	Params  map[string]any `json:"params"`
	Body    map[string]any `json:"body"`
	Meta    map[string]any `json:"meta"`
}

// NewFacts returns Facts with every bucket initialized to an empty map. Calling
// this is optional — `Facts.MarshalJSON` already substitutes empty maps for any
// nil bucket — but using it makes the intent obvious at the call site.
func NewFacts() Facts {
	return Facts{
		Headers: map[string]any{},
		Query:   map[string]any{},
		Params:  map[string]any{},
		Body:    map[string]any{},
		Meta:    map[string]any{},
	}
}

// MarshalJSON encodes Facts so that any nil bucket becomes `{}` (not `null`).
// The schema requires every bucket to be a JSON object, and the Rust engine's
// strict deserializer rejects `null`. Doing the fix here means a zero-value
// Facts (or one with only some buckets set) is always engine-acceptable.
func (f Facts) MarshalJSON() ([]byte, error) {
	type facts struct {
		Headers map[string]any `json:"headers"`
		Query   map[string]any `json:"query"`
		Params  map[string]any `json:"params"`
		Body    map[string]any `json:"body"`
		Meta    map[string]any `json:"meta"`
	}
	nn := func(m map[string]any) map[string]any {
		if m == nil {
			return map[string]any{}
		}
		return m
	}
	return json.Marshal(facts{
		Headers: nn(f.Headers),
		Query:   nn(f.Query),
		Params:  nn(f.Params),
		Body:    nn(f.Body),
		Meta:    nn(f.Meta),
	})
}

// EvaluationInput is the JSON-in shape for `Client.Evaluate`.
type EvaluationInput struct {
	Selector Selector `json:"selector"`
	Facts    Facts    `json:"facts"`
	// Now is the trusted current time (epoch millis) used to gate rules with an
	// effective window. Zero => Evaluate fills it with time.Now().UnixMilli();
	// set it explicitly only to pin time (e.g. tests).
	Now int64 `json:"now,omitempty"`
}

// MatchedRule is one entry of `EvaluationResult.Matched`.
type MatchedRule struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Priority int    `json:"priority"`
	Action   Action `json:"action"`
}

// EvaluationResult is the JSON-out shape from `Client.Evaluate`.
type EvaluationResult struct {
	Ok        bool          `json:"ok"`
	Matched   []MatchedRule `json:"matched"`
	Evaluated int           `json:"evaluated"`
	Skipped   int           `json:"skipped"`
	Errors    []ErrorInfo   `json:"errors"`
}

// LoadRulesResult is the JSON-out shape from `ks_load_rules`.
type LoadRulesResult struct {
	Ok                 bool        `json:"ok"`
	RulesVersion       int         `json:"rules_version"`
	RuleCount          int         `json:"rule_count"`
	CompiledRuleCount  int         `json:"compiled_rule_count"`
	SkippedRuleCount   int         `json:"skipped_rule_count"`
	Errors             []ErrorInfo `json:"errors"`
}

// EngineStatus is the JSON-out shape from `ks_get_status`.
type EngineStatus struct {
	Initialized        bool       `json:"initialized"`
	SchemaVersion      string     `json:"schema_version"`
	ActiveRulesVersion *int       `json:"active_rules_version"`
	RuleCount          int        `json:"rule_count"`
	LastLoadOk         bool       `json:"last_load_ok"`
	LastError          *ErrorInfo `json:"last_error"`
}

// ErrorInfo is the structured error returned by the engine in load/eval/status results.
type ErrorInfo struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	RuleID  string `json:"rule_id,omitempty"`
	Field   string `json:"field,omitempty"`
}
