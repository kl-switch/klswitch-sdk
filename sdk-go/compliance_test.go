package klswitch

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"testing"
)

// Cross-language parity: replay the canonical compliance fixtures (the SAME
// JSON files the Rust engine, Node wrapper, and Java wrapper all match) through
// the Go bridge against the bundled WASM, and assert byte-for-byte equality
// with the frozen expectations.
//
// Two harnesses: single-shot `eval-*` cases (one ruleset, one input, one expected
// result) and multi-step `scenario` cases (load/evaluate/get_status sequences
// against a single engine instance).

func casesDir(t *testing.T) string {
	t.Helper()
	d := filepath.Join("..", "spec", "compliance", "v1", "cases")
	if _, err := os.Stat(d); err != nil {
		t.Fatalf("compliance cases dir not found at %s: %v", d, err)
	}
	return d
}

func listCases(t *testing.T) []string {
	t.Helper()
	d := casesDir(t)
	entries, err := os.ReadDir(d)
	if err != nil {
		t.Fatalf("read cases dir: %v", err)
	}
	var files []string
	for _, e := range entries {
		if !e.IsDir() && filepath.Ext(e.Name()) == ".json" {
			files = append(files, filepath.Join(d, e.Name()))
		}
	}
	sort.Strings(files)
	return files
}

func TestCompliance_EvalFixtures(t *testing.T) {
	ctx := context.Background()
	b, err := newBridge(ctx, nil)
	if err != nil {
		t.Fatalf("newBridge: %v", err)
	}
	t.Cleanup(func() { _ = b.CloseRuntime(ctx) })

	checked := 0
	for _, path := range listCases(t) {
		raw, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read %s: %v", path, err)
		}
		var fx map[string]json.RawMessage
		if err := json.Unmarshal(raw, &fx); err != nil {
			t.Fatalf("parse %s: %v", path, err)
		}
		// Scenario fixtures (multi-step) are handled by the next test.
		if _, isScenario := fx["fixture_kind"]; isScenario {
			continue
		}
		expected, hasExpected := fx["expected_evaluation_result"]
		if !hasExpected {
			continue
		}

		handle, err := b.CreateEngine(ctx)
		if err != nil {
			t.Fatalf("%s: CreateEngine: %v", path, err)
		}
		loadOut, err := b.LoadRules(ctx, handle, string(fx["ruleset"]))
		if err != nil {
			t.Fatalf("%s: LoadRules: %v", path, err)
		}
		var load LoadRulesResult
		if err := json.Unmarshal([]byte(loadOut), &load); err != nil {
			t.Fatalf("%s: parse load result: %v\nbody=%s", path, err, loadOut)
		}
		if !load.Ok {
			t.Fatalf("%s: ruleset failed to compile: %s", path, loadOut)
		}
		evalOut, err := b.Evaluate(ctx, handle, string(fx["evaluation_input"]))
		if err != nil {
			t.Fatalf("%s: Evaluate: %v", path, err)
		}
		if !jsonEqual(t, []byte(evalOut), expected) {
			t.Fatalf("%s: evaluation mismatch\n  got: %s\n  want: %s", path, evalOut, expected)
		}
		_ = b.DestroyEngine(ctx, handle)
		checked++
	}
	// Exact count — catches silent skips and surfaces drift the moment a
	// fixture is added or removed. The canonical set is documented in
	// docs/cross-language-parity.md.
	if checked != 41 {
		t.Fatalf("expected 41 eval-* fixtures; checked %d", checked)
	}
	t.Logf("validated against %d eval fixtures", checked)
}

func TestCompliance_ScenarioFixtures(t *testing.T) {
	ctx := context.Background()
	scenarios, steps := 0, 0
	for _, path := range listCases(t) {
		raw, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read %s: %v", path, err)
		}
		var fx map[string]json.RawMessage
		if err := json.Unmarshal(raw, &fx); err != nil {
			t.Fatalf("parse %s: %v", path, err)
		}
		var kind string
		// A missing fixture_kind is fine (filtered by the kind != "scenario"
		// branch below); a malformed one is a fixture bug we want to surface.
		if rawKind, ok := fx["fixture_kind"]; ok {
			if err := json.Unmarshal(rawKind, &kind); err != nil {
				t.Fatalf("%s: parse fixture_kind: %v", path, err)
			}
		}
		if kind != "scenario" {
			continue
		}
		scenarios++

		// Fresh runtime + engine per scenario so state never bleeds.
		b, err := newBridge(ctx, nil)
		if err != nil {
			t.Fatalf("%s: newBridge: %v", path, err)
		}
		handle, err := b.CreateEngine(ctx)
		if err != nil {
			t.Fatalf("%s: CreateEngine: %v", path, err)
		}

		var stepList []map[string]json.RawMessage
		if err := json.Unmarshal(fx["steps"], &stepList); err != nil {
			t.Fatalf("%s: parse steps: %v", path, err)
		}
		for i, step := range stepList {
			var op string
			if err := json.Unmarshal(step["op"], &op); err != nil {
				t.Fatalf("%s step %d: parse op: %v", path, i, err)
			}
			switch op {
			case "load_rules":
				out, err := b.LoadRules(ctx, handle, string(step["ruleset"]))
				if err != nil {
					t.Fatalf("%s step %d (load_rules): %v", path, i, err)
				}
				if !jsonEqual(t, []byte(out), step["expected_load_rules_result"]) {
					t.Fatalf("%s step %d (load_rules) mismatch\n  got: %s\n  want: %s", path, i, out, step["expected_load_rules_result"])
				}
			case "evaluate":
				out, err := b.Evaluate(ctx, handle, string(step["evaluation_input"]))
				if err != nil {
					t.Fatalf("%s step %d (evaluate): %v", path, i, err)
				}
				if !jsonEqual(t, []byte(out), step["expected_evaluation_result"]) {
					t.Fatalf("%s step %d (evaluate) mismatch\n  got: %s\n  want: %s", path, i, out, step["expected_evaluation_result"])
				}
			case "get_status":
				out, err := b.GetStatus(ctx, handle)
				if err != nil {
					t.Fatalf("%s step %d (get_status): %v", path, i, err)
				}
				if !jsonEqual(t, []byte(out), step["expected_engine_status"]) {
					t.Fatalf("%s step %d (get_status) mismatch\n  got: %s\n  want: %s", path, i, out, step["expected_engine_status"])
				}
			default:
				t.Fatalf("%s step %d: unknown op %q", path, i, op)
			}
			steps++
		}
		_ = b.DestroyEngine(ctx, handle)
		_ = b.CloseRuntime(ctx)
	}
	// Exact counts — see the eval-fixture comment above for rationale.
	if scenarios != 9 || steps != 18 {
		t.Fatalf("expected 9 scenarios / 18 steps; got %d / %d", scenarios, steps)
	}
	t.Logf("replayed %d scenarios / %d steps", scenarios, steps)
}

// jsonEqual compares two JSON documents structurally (key order, whitespace,
// and integer-vs-float-by-value all normalized via decode + re-encode).
func jsonEqual(t *testing.T, a, b []byte) bool {
	t.Helper()
	var av, bv any
	if err := json.Unmarshal(a, &av); err != nil {
		t.Fatalf("invalid JSON (got): %v\nbody=%s", err, a)
	}
	if err := json.Unmarshal(b, &bv); err != nil {
		t.Fatalf("invalid JSON (want): %v\nbody=%s", err, b)
	}
	ab, _ := json.Marshal(av)
	bb, _ := json.Marshal(bv)
	return string(ab) == string(bb)
}
