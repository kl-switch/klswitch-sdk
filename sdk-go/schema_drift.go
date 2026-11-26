package klswitch

import (
	"sort"
	"strings"
)

// Schema-drift detection — Layer 3 (runtime).
//
// After every evaluation, we walk each *matched* rule's condition tree and
// tag the analytics event with the set of fields the rule referenced that
// weren't present in the request input. Operators see these in the console
// as "rule X referenced `body.coupon_code`, but `coupon_code` isn't in this
// route's body" — early warning that a rule was authored against a stale
// request shape.
//
// Pure wrapper-side; the engine doesn't know about this. See
// `docs/schema-drift-detection.md` for the four-layer detection plan.

// rulesByID returns a lookup keyed by rule ID. Cheap; built once per
// successful ruleset swap, not once per evaluate.
func rulesByID(rules []Rule) map[string]Rule {
	out := make(map[string]Rule, len(rules))
	for _, r := range rules {
		out[r.ID] = r
	}
	return out
}

// computeMissingFields walks each matched rule's condition tree and returns
// a deduplicated, sorted list of field paths that weren't present in the
// request input. Empty when no matched rule referenced a missing field
// (common case — keeps the wire format slim).
func computeMissingFields(
	matched []MatchedRule,
	rulesByID map[string]Rule,
	input EvaluationInput,
) []string {
	if len(matched) == 0 || len(rulesByID) == 0 {
		// Empty (not nil) so it marshals as `[]`, never `null` — the wire
		// contract requires missingFields to always be present as a list.
		return []string{}
	}
	missing := make(map[string]struct{})
	for _, m := range matched {
		rule, ok := rulesByID[m.ID]
		if !ok {
			continue
		}
		walkLeaves(rule.Condition, func(leaf ConditionNode) {
			if leaf.Field == "" {
				return
			}
			if !hasField(input, leaf.Field) {
				missing[leaf.Field] = struct{}{}
			}
		})
	}
	if len(missing) == 0 {
		return []string{}
	}
	out := make([]string, 0, len(missing))
	for f := range missing {
		out = append(out, f)
	}
	sort.Strings(out)
	return out
}

// walkLeaves recursively visits every leaf condition in the tree.
// A leaf is a node where `Field` is set; combinators (`All`, `Any`, `Not`)
// are mutually exclusive with leaves per the schema.
func walkLeaves(node ConditionNode, visit func(ConditionNode)) {
	if len(node.All) > 0 {
		for _, child := range node.All {
			walkLeaves(child, visit)
		}
		return
	}
	if len(node.Any) > 0 {
		for _, child := range node.Any {
			walkLeaves(child, visit)
		}
		return
	}
	if node.Not != nil {
		walkLeaves(*node.Not, visit)
		return
	}
	if node.Field != "" {
		visit(node)
	}
}

// hasField returns true iff the dotted path resolves to a defined non-null
// value within the request input. `body.amount` walks `input.Facts.Body["amount"]`;
// `headers.x-api-key` walks `input.Facts.Headers["x-api-key"]`.
//
// Treats nil as "absent" (matching the engine's missing-field semantics:
// a nil body field is the same as not setting it). Empty string and zero
// are PRESENT — they're real values an operator might have explicitly sent.
func hasField(input EvaluationInput, path string) bool {
	if path == "" {
		return false
	}
	segments := strings.Split(path, ".")
	if len(segments) == 0 {
		return false
	}
	bucket := factsBucket(input.Facts, segments[0])
	if bucket == nil {
		return false
	}
	var cursor any = bucket
	for i := 1; i < len(segments); i++ {
		obj, ok := cursor.(map[string]any)
		if !ok {
			return false
		}
		val, present := obj[segments[i]]
		if !present || val == nil {
			return false
		}
		cursor = val
	}
	return cursor != nil
}

// factsBucket returns the named bucket from a Facts struct, or nil if the
// bucket doesn't exist / is empty. Returning the bucket as map[string]any
// keeps the walker uniform across all five field locations.
func factsBucket(facts Facts, name string) map[string]any {
	switch name {
	case "headers":
		if facts.Headers == nil {
			return nil
		}
		return facts.Headers
	case "query":
		if facts.Query == nil {
			return nil
		}
		return facts.Query
	case "params":
		if facts.Params == nil {
			return nil
		}
		return facts.Params
	case "body":
		if facts.Body == nil {
			return nil
		}
		return facts.Body
	case "meta":
		if facts.Meta == nil {
			return nil
		}
		return facts.Meta
	default:
		return nil
	}
}
