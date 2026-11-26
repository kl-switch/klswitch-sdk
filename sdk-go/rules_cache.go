package klswitch

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
)

// Boot-time disk cache for the last-known-good ruleset.
//
// Operator opts in via WithCachePath. After every successful remote fetch
// the wrapper persists the accepted ruleset JSON to that path (atomic:
// tmpfile + os.Rename). When the SDK boots, if the very first fetch
// fails AND a cache file exists, the wrapper loads the cache and serves
// traffic against the last-known-good ruleset while polling keeps
// retrying the real backend in the background — so an outage at customer
// boot time no longer means "no rules".
//
// Pure file IO. The fall-back / persist decisions live in client.go;
// this file just handles read/write.

var unsafeCacheNameChars = regexp.MustCompile(`[^a-zA-Z0-9._-]`)

// DefaultCachePath returns the conventional ${tmpdir}/ks-cache-<env>.json
// path. Operators can supply their own path verbatim — this is just the
// helper for the common case. Unsafe characters in `env` are stripped so
// the result is always a sibling of the OS tmp dir.
func DefaultCachePath(env string) string {
	safe := env
	if safe == "" {
		safe = "default"
	} else {
		safe = unsafeCacheNameChars.ReplaceAllString(safe, "_")
	}
	return filepath.Join(os.TempDir(), fmt.Sprintf("ks-cache-%s.json", safe))
}

// readCachedRuleset reads + parses the cached ruleset from `path`.
// Returns (nil, nil) if the file doesn't exist, is empty, or has the
// wrong shape — a corrupt cache is just "no cache available", never an
// error. Returns the parsed RuleSetPayload on success.
func readCachedRuleset(path string, logger Logger) *RuleSetPayload {
	if path == "" {
		return nil
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			logger.Warn("ruleset cache unreadable; ignoring", map[string]any{
				"path": path, "error": err.Error(),
			})
		}
		return nil
	}
	if len(raw) == 0 {
		logger.Warn("ruleset cache is empty; ignoring", map[string]any{"path": path})
		return nil
	}
	// Minimal shape check — engine will validate fully on LoadRules.
	var probe struct {
		SchemaVersion string          `json:"schema_version"`
		Rules         json.RawMessage `json:"rules"`
	}
	if err := json.Unmarshal(raw, &probe); err != nil || probe.SchemaVersion == "" || len(probe.Rules) == 0 {
		logger.Warn("ruleset cache shape invalid; ignoring", map[string]any{"path": path})
		return nil
	}
	var rs RuleSetPayload
	if err := json.Unmarshal(raw, &rs); err != nil {
		logger.Warn("ruleset cache JSON parse failed; ignoring", map[string]any{
			"path": path, "error": err.Error(),
		})
		return nil
	}
	return &rs
}

// writeCachedRuleset atomically persists `rs` to `path`. Writes to a
// sibling `<path>.tmp` file then renames into place — a crash mid-write
// leaves either the previous good cache OR the new one, never a
// truncated file.
//
// Best-effort: any IO error is logged and swallowed. Disk cache is
// boot-time resilience, not a critical-path dependency.
func writeCachedRuleset(path string, rs RuleSetPayload, logger Logger) {
	if path == "" {
		return
	}
	// Ensure parent dir exists — operators sometimes point at a subdir
	// that hasn't been pre-created.
	if dir := filepath.Dir(path); dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			logger.Warn("ruleset cache parent dir create failed", map[string]any{
				"path": path, "error": err.Error(),
			})
			return
		}
	}
	body, err := json.Marshal(rs)
	if err != nil {
		logger.Warn("ruleset cache serialise failed", map[string]any{
			"path": path, "error": err.Error(),
		})
		return
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, body, 0o644); err != nil {
		logger.Warn("ruleset cache write failed", map[string]any{
			"path": path, "error": err.Error(),
		})
		_ = os.Remove(tmp)
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		logger.Warn("ruleset cache rename failed", map[string]any{
			"path": path, "error": err.Error(),
		})
		_ = os.Remove(tmp)
	}
}
