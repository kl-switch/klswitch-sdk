package klswitch

import (
	"context"
	"strings"
	"testing"
)

// Exercise the WASM export ABI directly (no Client wrapper): create engine →
// load → status → evaluate → destroy, plus the error paths the host contract
// requires (invalid handle, malformed input, repeated calls).

const ruleset = `{"schema_version":"1.0","rules_version":12,"rules":[
    {"id":"block","name":"Block","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
     "enabled":true,"priority":100,
     "condition":{"all":[{"field":"body.amount","operator":"gt","value":100}]},
     "action":{"type":"block","payload":{"code":"RULE_BLOCKED"}}}]}`

const inputMatch = `{"selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
    "facts":{"headers":{},"query":{},"params":{},"body":{"amount":500},"meta":{}}}`

func TestBridge_FullLifecycle(t *testing.T) {
	ctx := context.Background()
	b, err := newBridge(ctx, nil)
	if err != nil {
		t.Fatalf("newBridge: %v", err)
	}
	t.Cleanup(func() { _ = b.CloseRuntime(ctx) })

	h, err := b.CreateEngine(ctx)
	if err != nil {
		t.Fatalf("CreateEngine: %v", err)
	}
	if h == 0 {
		t.Fatalf("handle is zero (invalid sentinel)")
	}

	// Fresh engine, no load yet.
	status, err := b.GetStatus(ctx, h)
	if err != nil {
		t.Fatalf("GetStatus: %v", err)
	}
	if !strings.Contains(status, `"last_load_ok":false`) {
		t.Errorf("fresh engine should report last_load_ok=false, got: %s", status)
	}

	// Load.
	load, err := b.LoadRules(ctx, h, ruleset)
	if err != nil {
		t.Fatalf("LoadRules: %v", err)
	}
	if !strings.Contains(load, `"ok":true`) || !strings.Contains(load, `"rules_version":12`) {
		t.Errorf("unexpected load result: %s", load)
	}

	// Status now reflects the active ruleset.
	status, _ = b.GetStatus(ctx, h)
	if !strings.Contains(status, `"active_rules_version":12`) ||
		!strings.Contains(status, `"rule_count":1`) ||
		!strings.Contains(status, `"last_load_ok":true`) {
		t.Errorf("status mismatch after load: %s", status)
	}

	// Evaluate a matching request.
	eval, err := b.Evaluate(ctx, h, inputMatch)
	if err != nil {
		t.Fatalf("Evaluate: %v", err)
	}
	if !strings.Contains(eval, `"id":"block"`) {
		t.Errorf("expected block match, got: %s", eval)
	}

	// Destroy → subsequent status returns the not-initialized shape.
	if err := b.DestroyEngine(ctx, h); err != nil {
		t.Fatalf("DestroyEngine: %v", err)
	}
	status, _ = b.GetStatus(ctx, h)
	if !strings.Contains(status, `"initialized":false`) ||
		!strings.Contains(status, `"INVALID_HANDLE"`) {
		t.Errorf("destroyed handle should report INVALID_HANDLE, got: %s", status)
	}
}

func TestBridge_InvalidHandle(t *testing.T) {
	ctx := context.Background()
	b, _ := newBridge(ctx, nil)
	t.Cleanup(func() { _ = b.CloseRuntime(ctx) })

	load, _ := b.LoadRules(ctx, 999, ruleset)
	if !strings.Contains(load, `"INVALID_HANDLE"`) {
		t.Errorf("expected INVALID_HANDLE on bad handle, got: %s", load)
	}
	eval, _ := b.Evaluate(ctx, 999, inputMatch)
	if !strings.Contains(eval, `"INVALID_HANDLE"`) {
		t.Errorf("expected INVALID_HANDLE on bad handle, got: %s", eval)
	}
}

func TestBridge_MalformedInput(t *testing.T) {
	ctx := context.Background()
	b, _ := newBridge(ctx, nil)
	t.Cleanup(func() { _ = b.CloseRuntime(ctx) })

	h, _ := b.CreateEngine(ctx)
	t.Cleanup(func() { _ = b.DestroyEngine(ctx, h) })

	load, _ := b.LoadRules(ctx, h, "{ not json")
	if !strings.Contains(load, `"ok":false`) {
		t.Errorf("malformed JSON should fail load, got: %s", load)
	}

	eval, _ := b.Evaluate(ctx, h, "{}")
	if !strings.Contains(eval, `"INVALID_INPUT"`) {
		t.Errorf("missing selector should be INVALID_INPUT, got: %s", eval)
	}
}

func TestBridge_RepeatedCallsAreStable(t *testing.T) {
	ctx := context.Background()
	b, _ := newBridge(ctx, nil)
	t.Cleanup(func() { _ = b.CloseRuntime(ctx) })

	h, _ := b.CreateEngine(ctx)
	t.Cleanup(func() { _ = b.DestroyEngine(ctx, h) })

	// 1000 round-trips: if any input/result buffer leaks, the WASM allocator
	// will eventually misbehave.
	for i := 0; i < 1000; i++ {
		if _, err := b.LoadRules(ctx, h, ruleset); err != nil {
			t.Fatalf("LoadRules at iter %d: %v", i, err)
		}
		if _, err := b.Evaluate(ctx, h, inputMatch); err != nil {
			t.Fatalf("Evaluate at iter %d: %v", i, err)
		}
	}
}
