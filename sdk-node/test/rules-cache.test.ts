/**
 * B4 — Boot-time disk-cache tests.
 *
 * Covers the standalone read/write helpers (atomic write, defensive
 * read of malformed files) and the end-to-end Client fallback: when
 * `cachePath` is set + the first fetch errors + a previous good cache
 * exists, the SDK loads the cache and serves traffic while polling
 * keeps retrying the backend.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, existsSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { KLSwitch } from "../src/client.js";
import { noopLogger } from "../src/logger.js";
import type { FetchLike } from "../src/polling-manager.js";
import {
  defaultCachePath,
  readCachedRuleset,
  writeCachedRuleset,
} from "../src/rules-cache.js";
import type { RuleSetPayload } from "../src/types.js";

function ruleset(version: number): RuleSetPayload {
  return {
    schema_version: "1.0",
    rules_version: version,
    rules: [{
      id: "r",
      name: "R",
      selector: { kind: "api", keys: { method: "POST", path: "/p" } },
      enabled: true,
      priority: 100,
      condition: { all: [{ field: "body.amount", operator: "gt", value: 0 }] },
      action: { type: "block" },
    }],
  };
}

function withTmpDir(fn: (dir: string) => void | Promise<void>): () => Promise<void> {
  return async () => {
    const dir = mkdtempSync(join(tmpdir(), "ks-cache-test-"));
    try { await fn(dir); }
    finally { rmSync(dir, { recursive: true, force: true }); }
  };
}

// ─── defaultCachePath ─────────────────────────────────────────────────────

test("defaultCachePath: uses ${tmpdir}/ks-cache-<env>.json", () => {
  const path = defaultCachePath("prod");
  assert.ok(path.endsWith("ks-cache-prod.json"));
  assert.ok(path.startsWith(tmpdir()));
});

test("defaultCachePath: sanitises unsafe characters", () => {
  // Slashes, spaces, etc. become underscores — keeps the path inside tmpdir.
  const path = defaultCachePath("prod/west");
  assert.ok(/ks-cache-prod_west\.json$/.test(path));
});

test("defaultCachePath: empty env → default fallback", () => {
  const path = defaultCachePath("");
  assert.ok(/ks-cache-default\.json$/.test(path));
});

// ─── writeCachedRuleset / readCachedRuleset (round trip) ──────────────────

test("rules-cache: write then read recovers the same payload", withTmpDir((dir) => {
  const path = join(dir, "cache.json");
  const payload = ruleset(7);
  writeCachedRuleset(path, payload, noopLogger);

  assert.ok(existsSync(path));
  const got = readCachedRuleset(path, noopLogger);
  assert.deepEqual(got, payload);
}));

test("rules-cache: write is atomic (no .tmp file lingers)", withTmpDir((dir) => {
  const path = join(dir, "cache.json");
  writeCachedRuleset(path, ruleset(1), noopLogger);
  assert.equal(existsSync(`${path}.tmp`), false, "tmpfile should be renamed away");
}));

test("rules-cache: write creates parent directory", withTmpDir((dir) => {
  const path = join(dir, "nested", "subdir", "cache.json");
  writeCachedRuleset(path, ruleset(2), noopLogger);
  assert.ok(existsSync(path));
}));

test("rules-cache: read returns null for missing file", () => {
  assert.equal(
    readCachedRuleset("/tmp/__ks_does_not_exist_test__.json", noopLogger),
    null,
  );
});

test("rules-cache: read returns null for malformed JSON", withTmpDir((dir) => {
  const path = join(dir, "bad.json");
  writeFileSync(path, "{ not valid json", "utf8");
  assert.equal(readCachedRuleset(path, noopLogger), null);
}));

test("rules-cache: read returns null for valid JSON but wrong shape", withTmpDir((dir) => {
  const path = join(dir, "wrong-shape.json");
  writeFileSync(path, JSON.stringify({ unrelated: "thing" }), "utf8");
  assert.equal(readCachedRuleset(path, noopLogger), null);
}));

test("rules-cache: read returns null for empty file", withTmpDir((dir) => {
  const path = join(dir, "empty.json");
  writeFileSync(path, "", "utf8");
  assert.equal(readCachedRuleset(path, noopLogger), null);
}));

// ─── Client integration: cache writes on successful fetch ────────────────

test("client.init: writes cache after a successful fetch when cachePath set", withTmpDir(async (dir) => {
  const path = join(dir, "cache.json");

  // Backend that returns a fresh ruleset on the first GET.
  const fetchImpl: FetchLike = async () => ({
    ok: true, status: 200,
    json: async () => ruleset(42),
    text: async () => "",
    headers: { get: () => null },
  });

  const ks = await KLSwitch.init({
    token: "ks_live_test",
    fetchImpl,
    refreshInterval: 0,    // disable recurring; init does one fetch
    cachePath: path,
  });

  assert.ok(existsSync(path), "cache file should exist after init");
  const cached = JSON.parse(readFileSync(path, "utf8")) as RuleSetPayload;
  assert.equal(cached.rules_version, 42);
  ks.close();
}));

// ─── Client integration: boot fallback when first fetch fails ────────────

test("client.init: falls back to cache when first fetch errors and cache exists", withTmpDir(async (dir) => {
  const path = join(dir, "cache.json");
  // Seed the cache with a known good ruleset.
  writeCachedRuleset(path, ruleset(99), noopLogger);

  // Backend that always errors.
  const fetchImpl: FetchLike = async () => {
    throw new Error("backend unreachable");
  };

  const ks = await KLSwitch.init({
    token: "ks_live_test",
    fetchImpl,
    refreshInterval: 0,
    cachePath: path,
  });
  // Boot succeeded against the cached ruleset.
  const st = await ks.getStatus();
  assert.equal(st.active_rules_version, 99);
  ks.close();
}));

test("client.init: throws normally when no cache exists and first fetch fails", withTmpDir(async (dir) => {
  const path = join(dir, "absent.json");      // does NOT exist
  const fetchImpl: FetchLike = async () => {
    throw new Error("backend unreachable");
  };

  await assert.rejects(
    () => KLSwitch.init({
      token: "ks_live_test",
      fetchImpl,
      refreshInterval: 0,
      cachePath: path,
    }),
    /no rules available/i,
  );
}));

test("client.init: cachePath unset → never reads disk, throws on fetch fail", async () => {
  const fetchImpl: FetchLike = async () => {
    throw new Error("backend unreachable");
  };
  await assert.rejects(
    () => KLSwitch.init({
      token: "ks_live_test",
      fetchImpl,
      refreshInterval: 0,
    }),
    /no rules available/i,
  );
});

test("client.init: corrupt cache is ignored and init fails clearly", withTmpDir(async (dir) => {
  const path = join(dir, "corrupt.json");
  writeFileSync(path, "{not parseable", "utf8");

  const fetchImpl: FetchLike = async () => {
    throw new Error("backend unreachable");
  };

  await assert.rejects(
    () => KLSwitch.init({
      token: "ks_live_test",
      fetchImpl,
      refreshInterval: 0,
      cachePath: path,
    }),
    /no rules available/i,
  );
}));

test("client.init: successful fresh fetch overwrites stale cache", withTmpDir(async (dir) => {
  const path = join(dir, "cache.json");
  // Seed cache with old version.
  writeCachedRuleset(path, ruleset(1), noopLogger);

  const fetchImpl: FetchLike = async () => ({
    ok: true, status: 200,
    json: async () => ruleset(5),
    text: async () => "",
    headers: { get: () => null },
  });

  const ks = await KLSwitch.init({
    token: "ks_live_test",
    fetchImpl,
    refreshInterval: 0,
    cachePath: path,
  });
  const st = await ks.getStatus();
  assert.equal(st.active_rules_version, 5);
  // On-disk cache now reflects the fresh fetch, not the stale value.
  const cached = JSON.parse(readFileSync(path, "utf8")) as RuleSetPayload;
  assert.equal(cached.rules_version, 5);
  ks.close();
}));
