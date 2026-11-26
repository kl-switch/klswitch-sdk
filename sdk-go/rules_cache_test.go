package klswitch

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// toIntString prints a numeric value as a decimal integer string, accepting
// both int and float64 (JSON round-trip flattens to float64).
func toIntString(v any) string {
	switch n := v.(type) {
	case int:
		return fmt.Sprintf("%d", n)
	case float64:
		return fmt.Sprintf("%d", int64(n))
	default:
		return fmt.Sprintf("%v", v)
	}
}

// B4 — Boot-time disk-cache tests for the Go wrapper.

func rulesetV(version int) RuleSetPayload {
	return RuleSetPayload{
		SchemaVersion: "1.0",
		RulesVersion:  version,
		Rules: []Rule{{
			ID:        "r",
			Name:      "R",
			Selector:  Selector{Kind: "api", Keys: map[string]string{"method": "POST", "path": "/p"}},
			Enabled:   true,
			Condition: ConditionNode{Field: "body.amount", Operator: "gt", Value: 0},
			Action:    Action{Type: "block"},
		}},
	}
}

// ─── DefaultCachePath ─────────────────────────────────────────────────────

func TestDefaultCachePath_UsesTmpDir(t *testing.T) {
	path := DefaultCachePath("prod")
	if !strings.HasSuffix(path, "ks-cache-prod.json") {
		t.Errorf("got %q, expected suffix ks-cache-prod.json", path)
	}
	if !strings.HasPrefix(path, os.TempDir()) {
		t.Errorf("got %q, expected prefix %q", path, os.TempDir())
	}
}

func TestDefaultCachePath_SanitisesUnsafeChars(t *testing.T) {
	path := DefaultCachePath("prod/west")
	if !strings.HasSuffix(path, "ks-cache-prod_west.json") {
		t.Errorf("got %q", path)
	}
}

func TestDefaultCachePath_EmptyEnvFallback(t *testing.T) {
	path := DefaultCachePath("")
	if !strings.HasSuffix(path, "ks-cache-default.json") {
		t.Errorf("got %q", path)
	}
}

// ─── Round trip ──────────────────────────────────────────────────────────

func TestRulesCache_WriteThenReadRecoversPayload(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cache.json")
	writeCachedRuleset(path, rulesetV(99), noopLogger{})

	got := readCachedRuleset(path, noopLogger{})
	if got == nil {
		t.Fatal("expected non-nil")
	}
	if s := toIntString(got.RulesVersion); s != "99" {
		t.Errorf("got rules_version=%v, want 99", got.RulesVersion)
	}
}

func TestRulesCache_WriteIsAtomic_NoTmpLingers(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cache.json")
	writeCachedRuleset(path, rulesetV(1), noopLogger{})
	if _, err := os.Stat(path + ".tmp"); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("tmpfile lingered: %v", err)
	}
}

func TestRulesCache_WriteCreatesParentDirectory(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "nested", "subdir", "cache.json")
	writeCachedRuleset(path, rulesetV(2), noopLogger{})
	if _, err := os.Stat(path); err != nil {
		t.Errorf("expected cache file to exist, got %v", err)
	}
}

func TestRulesCache_ReadReturnsNilForMissingFile(t *testing.T) {
	if got := readCachedRuleset(filepath.Join(t.TempDir(), "missing.json"), noopLogger{}); got != nil {
		t.Errorf("got %+v, want nil", got)
	}
}

func TestRulesCache_ReadReturnsNilForMalformedJSON(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "bad.json")
	if err := os.WriteFile(path, []byte("{ not valid json"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := readCachedRuleset(path, noopLogger{}); got != nil {
		t.Errorf("got %+v, want nil", got)
	}
}

func TestRulesCache_ReadReturnsNilForWrongShape(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "wrong-shape.json")
	if err := os.WriteFile(path, []byte(`{"unrelated":"thing"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := readCachedRuleset(path, noopLogger{}); got != nil {
		t.Errorf("got %+v, want nil", got)
	}
}

func TestRulesCache_ReadReturnsNilForEmptyFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "empty.json")
	if err := os.WriteFile(path, []byte{}, 0o644); err != nil {
		t.Fatal(err)
	}
	if got := readCachedRuleset(path, noopLogger{}); got != nil {
		t.Errorf("got %+v, want nil", got)
	}
}

func TestRulesCache_ReadReturnsNilForEmptyPath(t *testing.T) {
	if got := readCachedRuleset("", noopLogger{}); got != nil {
		t.Errorf("got %+v, want nil", got)
	}
}

// ─── Client integration ──────────────────────────────────────────────────

type errFetcher struct{ msg string }

func (f errFetcher) Fetch(_ context.Context) (RuleSetPayload, error) {
	return RuleSetPayload{}, errors.New(f.msg)
}

type fixedFetcher struct{ rs RuleSetPayload }

func (f fixedFetcher) Fetch(_ context.Context) (RuleSetPayload, error) {
	return f.rs, nil
}

func TestClient_WritesCacheAfterSuccessfulFetch(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cache.json")
	ctx := context.Background()
	ks, err := Init(ctx,
		WithFetcher(fixedFetcher{rs: rulesetV(42)}),
		WithRefreshInterval(0),
		WithCachePath(path),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	if _, err := os.Stat(path); err != nil {
		t.Fatalf("cache file should exist: %v", err)
	}
	raw, _ := os.ReadFile(path)
	var rs RuleSetPayload
	if err := json.Unmarshal(raw, &rs); err != nil {
		t.Fatalf("cache parse: %v", err)
	}
	// RulesVersion is `any` (accepts int or string) → JSON round-trip
	// reifies as float64. Compare via Sprintf so the test doesn't care
	// about the concrete numeric type.
	if got := toIntString(rs.RulesVersion); got != "42" {
		t.Errorf("cache rules_version=%v, want 42", rs.RulesVersion)
	}
}

func TestClient_FallsBackToCacheWhenFetchFails(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cache.json")
	// Seed cache.
	writeCachedRuleset(path, rulesetV(99), noopLogger{})

	ctx := context.Background()
	ks, err := Init(ctx,
		WithFetcher(errFetcher{msg: "backend down"}),
		WithRefreshInterval(0),
		WithCachePath(path),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()

	status, err := ks.GetStatus(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if status.ActiveRulesVersion == nil || *status.ActiveRulesVersion != 99 {
		t.Errorf("got active_rules_version=%v, want 99", status.ActiveRulesVersion)
	}
}

func TestClient_ThrowsWhenNoCacheAndFetchFails(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "absent.json")
	ctx := context.Background()
	_, err := Init(ctx,
		WithFetcher(errFetcher{msg: "backend down"}),
		WithRefreshInterval(0),
		WithCachePath(path),
	)
	if err == nil {
		t.Fatal("expected init failure")
	}
	if !strings.Contains(strings.ToLower(err.Error()), "no rules available") {
		t.Errorf("unexpected error: %v", err)
	}
}

func TestClient_CachePathUnsetNeverReadsDisk(t *testing.T) {
	ctx := context.Background()
	_, err := Init(ctx,
		WithFetcher(errFetcher{msg: "backend down"}),
		WithRefreshInterval(0),
	)
	if err == nil {
		t.Fatal("expected init failure")
	}
}

func TestClient_CorruptCacheIsIgnored(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "corrupt.json")
	if err := os.WriteFile(path, []byte("{not parseable"), 0o644); err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	_, err := Init(ctx,
		WithFetcher(errFetcher{msg: "backend down"}),
		WithRefreshInterval(0),
		WithCachePath(path),
	)
	if err == nil {
		t.Fatal("expected init failure when both fetch and cache fail")
	}
}

func TestClient_FreshFetchOverwritesStaleCache(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cache.json")
	// Seed cache with old version.
	writeCachedRuleset(path, rulesetV(1), noopLogger{})

	ctx := context.Background()
	ks, err := Init(ctx,
		WithFetcher(fixedFetcher{rs: rulesetV(5)}),  // fresh = v5
		WithRefreshInterval(0),
		WithCachePath(path),
	)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer ks.Close()
	status, _ := ks.GetStatus(ctx)
	if status.ActiveRulesVersion == nil || *status.ActiveRulesVersion != 5 {
		t.Errorf("active=%v, want 5", status.ActiveRulesVersion)
	}
	raw, _ := os.ReadFile(path)
	var rs RuleSetPayload
	_ = json.Unmarshal(raw, &rs)
	if got := toIntString(rs.RulesVersion); got != "5" {
		t.Errorf("cache rules_version=%v, want 5", rs.RulesVersion)
	}
}
