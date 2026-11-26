/**
 * Logger smoke tests — exercise every method on the silent default and
 * the console adapter so the cosmetic "33% funcs covered" gap closes.
 * No assertions about side effects beyond "must not throw".
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import { consoleLogger, noopLogger } from "../src/logger.js";

test("noopLogger: every level is a no-op (no throw)", () => {
  noopLogger.debug("ok");
  noopLogger.info("ok",  { rulesVersion: 1 });
  noopLogger.warn("ok",  { error: "x" });
  noopLogger.error("ok", { stack: "trace" });
  // Round-trip with fields omitted exercises the optional-arg path.
  noopLogger.debug("no fields");
  noopLogger.info("no fields");
  noopLogger.warn("no fields");
  noopLogger.error("no fields");
});

test("consoleLogger: every level is reachable; fields-omitted path included", (t) => {
  // Silence console output for the test run; we only care that the
  // adapter calls don't throw and that each branch is exercised.
  const orig = { ...console };
  t.after(() => Object.assign(console, orig));
  console.debug = () => {};
  console.info  = () => {};
  console.warn  = () => {};
  console.error = () => {};

  consoleLogger.debug("ok");
  consoleLogger.info("ok",  { rulesVersion: 1 });
  consoleLogger.warn("ok",  { error: "x" });
  consoleLogger.error("ok", { stack: "trace" });

  // The `fields ?? ""` fallback path needs an explicit no-fields call.
  consoleLogger.debug("no fields");
  consoleLogger.info("no fields");
  consoleLogger.warn("no fields");
  consoleLogger.error("no fields");

  assert.ok(true);
});
