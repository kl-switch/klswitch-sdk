import { test } from "node:test";
import assert from "node:assert/strict";

import { assertNotLegacyShape } from "../src/config-legacy.js";
import type { KLSwitchConfig } from "../src/config.js";

// The migration check is a unit of its own so it can be retired in one piece.
// These tests pin every branch, so whoever retires it can see exactly what
// behaviour goes away with it.

const as = (o: unknown) => o as KLSwitchConfig;

test("config-legacy: the A1 shape passes untouched", () => {
  assertNotLegacyShape(as({ token: "t", instanceId: "i", baseUrl: "http://x" }));
});

test("config-legacy: rulesUrl alone is not legacy — it is a supported escape hatch", () => {
  // The old shape used rulesUrl, but so do self-hosted A1 callers. Its presence
  // must not be read as evidence of an un-migrated config.
  assertNotLegacyShape(as({ token: "t", rulesUrl: "http://self-hosted/rules" }));
});

test("config-legacy: an un-migrated caller is told what to replace", () => {
  assert.throws(
    () => assertNotLegacyShape(as({ headers: { Authorization: "Bearer x" }, rulesUrl: "x" })),
    (e: Error) => /refactored/.test(e.message) && /headers/.test(e.message) && /token/.test(e.message),
  );
});

test("config-legacy: a half-migrated caller is refused, not silently reinterpreted", () => {
  // token + a legacy key: the legacy key is in the way, and guessing which one
  // the caller meant would configure something they did not ask for.
  assert.throws(
    () => assertNotLegacyShape(as({ token: "t", serviceCode: "svc" })),
    /refactored/,
  );
});

test("config-legacy: every legacy key present is named in the error", () => {
  assert.throws(
    () => assertNotLegacyShape(as({ headers: {}, serviceCode: "s", environment: "prod" })),
    (e: Error) =>
      ["headers", "serviceCode", "environment"].every((k) => e.message.includes(k)),
  );
});

test("config-legacy: inline rules bypass the check — dev mode reaches no backend", () => {
  // With inline rules there is no backend call to misconfigure, so a stray
  // legacy key configures nothing and need not block local development.
  assertNotLegacyShape(as({ rules: { schema_version: "1.0", rules_version: 1, rules: [] }, headers: {} }));
});

test("config-legacy: inline rules do NOT excuse a half-migrated config", () => {
  // token present means the caller is talking to a backend after all.
  assert.throws(
    () => assertNotLegacyShape(as({ token: "t", rules: { schema_version: "1.0", rules_version: 1, rules: [] }, environment: "prod" })),
    /refactored/,
  );
});

test("config-legacy: an empty token counts as absent, not as migrated", () => {
  assert.throws(() => assertNotLegacyShape(as({ token: "", headers: {} })), /refactored/);
});
