package klswitch

import (
	"bytes"
	"context"
	"log/slog"
	"strings"
	"testing"
)

// Logger smoke tests — exercise every method on noopLogger and SlogLogger
// so the cosmetic "0% funcs covered" gap closes. No assertions about
// side effects beyond "must not panic" for noopLogger; SlogLogger
// gets a captured-writer round-trip.

func TestNoopLogger_EveryLevelIsSilent(t *testing.T) {
	n := noopLogger{}
	n.Debug("ok", nil)
	n.Info("ok", map[string]any{"k": "v"})
	n.Warn("ok", map[string]any{"k": "v"})
	n.Error("ok", map[string]any{"k": "v"})
	// noopLogger has no failure mode — getting here is the assertion.
}

func TestSlogLogger_WithCustomLogger_RoundTripsFields(t *testing.T) {
	var buf bytes.Buffer
	// Text handler at DEBUG so every call gets through.
	h := slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug})
	sl := SlogLogger{L: slog.New(h)}

	sl.Debug("debugmsg", map[string]any{"rulesVersion": 1})
	sl.Info("infomsg",  map[string]any{"k": "v"})
	sl.Warn("warnmsg",  map[string]any{"k": "v"})
	sl.Error("errmsg",  map[string]any{"k": "v"})

	out := buf.String()
	for _, want := range []string{"debugmsg", "infomsg", "warnmsg", "errmsg",
		"rulesVersion=1", "k=v"} {
		if !strings.Contains(out, want) {
			t.Errorf("expected %q in output: %s", want, out)
		}
	}
}

func TestSlogLogger_WithNilLogger_FallsBackToSlogDefault(t *testing.T) {
	// SlogLogger{L: nil} should fall through to slog.Default() rather
	// than panic. We redirect slog.Default's handler to capture output
	// then restore it. No assertion on content (slog.Default is
	// process-wide); we just verify no panic.
	prev := slog.Default()
	t.Cleanup(func() { slog.SetDefault(prev) })

	var buf bytes.Buffer
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug})))

	sl := SlogLogger{}  // L is nil
	sl.Debug("ok", nil)
	sl.Info("ok", nil)
	sl.Warn("ok", nil)
	sl.Error("ok", nil)
}

func TestSlogLogger_DisabledLevel_IsSkipped(t *testing.T) {
	// Confirm the `if !l.Enabled(ctx, level)` early-return branch fires:
	// configure a handler that disables Debug. The Debug call should
	// produce zero bytes.
	var buf bytes.Buffer
	h := slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelInfo})
	sl := SlogLogger{L: slog.New(h)}

	sl.Debug("suppressed", map[string]any{"k": "v"})
	if buf.Len() != 0 {
		t.Errorf("disabled Debug must not emit; got %q", buf.String())
	}
	// Info should pass through.
	sl.Info("emitted", map[string]any{"k": "v"})
	if !strings.Contains(buf.String(), "emitted") {
		t.Errorf("enabled Info must emit; got %q", buf.String())
	}
	_ = context.Background()  // referenced indirectly — keep the import warm
}
