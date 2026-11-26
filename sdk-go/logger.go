package klswitch

import (
	"context"
	"log/slog"
)

// Logger is the wrapper-owned logging interface. The engine never logs; the
// wrapper logs lifecycle and refresh events (not every evaluation). Provide one
// via [WithLogger]; the default is silent.
type Logger interface {
	Debug(msg string, fields map[string]any)
	Info(msg string, fields map[string]any)
	Warn(msg string, fields map[string]any)
	Error(msg string, fields map[string]any)
}

// noopLogger drops everything. Used as the default so the SDK never writes to
// stdout uninvited.
type noopLogger struct{}

func (noopLogger) Debug(string, map[string]any) {}
func (noopLogger) Info(string, map[string]any)  {}
func (noopLogger) Warn(string, map[string]any)  {}
func (noopLogger) Error(string, map[string]any) {}

// SlogLogger adapts a Go stdlib [slog.Logger] to this SDK's [Logger] interface.
// Pass either *slog.Logger (default) or one configured for your app.
type SlogLogger struct {
	L *slog.Logger
}

func (s SlogLogger) Debug(msg string, fields map[string]any) { s.log(slog.LevelDebug, msg, fields) }
func (s SlogLogger) Info(msg string, fields map[string]any)  { s.log(slog.LevelInfo, msg, fields) }
func (s SlogLogger) Warn(msg string, fields map[string]any)  { s.log(slog.LevelWarn, msg, fields) }
func (s SlogLogger) Error(msg string, fields map[string]any) { s.log(slog.LevelError, msg, fields) }

func (s SlogLogger) log(level slog.Level, msg string, fields map[string]any) {
	l := s.L
	if l == nil {
		l = slog.Default()
	}
	ctx := context.Background()
	if !l.Enabled(ctx, level) {
		return
	}
	attrs := make([]any, 0, 2*len(fields))
	for k, v := range fields {
		attrs = append(attrs, k, v)
	}
	l.Log(ctx, level, msg, attrs...)
}
