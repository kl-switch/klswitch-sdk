import { test } from "node:test";
import assert from "node:assert/strict";

import { buildHeaders, resolveEnvHint } from "../src/request-headers.js";

test("buildHeaders: core headers always set", () => {
  const h = buildHeaders({
    token:       "ks_live_test",
    instanceKey: "pod-7f4a",
    sdkVersion:  "0.1.0",
    env:         {},
  });
  assert.equal(h["Authorization"],   "Bearer ks_live_test");
  assert.equal(h["X-Instance-Key"],  "pod-7f4a");
  assert.equal(h["User-Agent"],      "klswitch-node/0.1.0");
  assert.equal(h["Accept-Encoding"], "gzip");
});

test("buildHeaders: extraHeaders are merged but cannot override Authorization", () => {
  const h = buildHeaders({
    token:       "ks_live_test",
    instanceKey: "i",
    sdkVersion:  "0.1.0",
    env:         {},
    extraHeaders: {
      "X-Custom":      "value",
      "Authorization": "Bearer ATTACK",
      "x-instance-key": "ATTACK",   // case-insensitive — SDK still wins
    },
  });
  assert.equal(h["X-Custom"],         "value");
  assert.equal(h["Authorization"],    "Bearer ks_live_test");
  assert.equal(h["X-Instance-Key"],   "i");
});

test("buildHeaders: X-Env-Hint included when KS_ENV is set", () => {
  const h = buildHeaders({
    token: "t",
    instanceKey: "i",
    sdkVersion: "0",
    env: { KS_ENV: "staging" },
  });
  assert.equal(h["X-Env-Hint"], "staging");
});

test("buildHeaders: X-Env-Hint included when only POD_NAMESPACE is set", () => {
  const h = buildHeaders({
    token: "t",
    instanceKey: "i",
    sdkVersion: "0",
    env: { POD_NAMESPACE: "prod" },
  });
  assert.equal(h["X-Env-Hint"], "prod");
});

test("buildHeaders: X-Env-Hint omitted when nothing resolvable", () => {
  const h = buildHeaders({
    token: "t",
    instanceKey: "i",
    sdkVersion: "0",
    env: {},
  });
  assert.ok(!("X-Env-Hint" in h));
});

test("buildHeaders: explicit envHint wins over env vars", () => {
  const h = buildHeaders({
    token: "t",
    instanceKey: "i",
    sdkVersion: "0",
    envHint: "explicit",
    env: { KS_ENV: "from-env" },
  });
  assert.equal(h["X-Env-Hint"], "explicit");
});

test("resolveEnvHint: chain order — KS_ENV beats POD_NAMESPACE beats K_SERVICE_REVISION", () => {
  assert.equal(
    resolveEnvHint({ KS_ENV: "a", POD_NAMESPACE: "b", K_SERVICE_REVISION: "c" }),
    "a",
  );
  assert.equal(resolveEnvHint({ POD_NAMESPACE: "b", K_SERVICE_REVISION: "c" }), "b");
  assert.equal(resolveEnvHint({ K_SERVICE_REVISION: "c" }), "c");
  assert.equal(resolveEnvHint({}), undefined);
});

test("resolveEnvHint: skips empty / whitespace-only values", () => {
  assert.equal(resolveEnvHint({ KS_ENV: "", POD_NAMESPACE: "b" }), "b");
  assert.equal(resolveEnvHint({ KS_ENV: "   ", POD_NAMESPACE: "b" }), "b");
});
