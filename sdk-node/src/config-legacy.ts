/**
 * Rejection of the pre-A1 config shape.
 *
 * A migration aid, not validation — and the distinction is the point. This lived
 * inside `validateConfig`, so every new config field meant editing a function
 * that also carried deprecation handling for a shape that no longer exists. Two
 * reasons for one unit to change.
 *
 * It lives in its own file so that retiring it is deleting this file and one
 * call, rather than surgery on the validator.
 *
 * The old shape had `headers` and was driven by `rulesUrl`. `rulesUrl` survives
 * in A1 as a documented escape hatch for self-hosted setups, so its presence
 * alone proves nothing; what identifies an un-migrated caller is a legacy-only
 * key.
 */

import type { KLSwitchConfig } from "./config.js";

/** The keys that existed only in the pre-A1 shape. */
const LEGACY_KEYS = ["headers", "serviceCode", "environment"] as const;

interface MaybeLegacy {
  headers?: unknown;
  serviceCode?: unknown;
  environment?: unknown;
  token?: unknown;
  rules?: unknown;
}

/**
 * Throws a migration error when the config is the old shape.
 *
 * Two cases are refused, and for the same reason — a legacy key means the caller
 * has not migrated, and silently reinterpreting it would configure something
 * other than what they asked for:
 *
 * - legacy keys and no `token`: an un-migrated caller
 * - legacy keys *and* a `token`: a half-migrated one, where the legacy key is in
 *   the way
 *
 * Inline `rules` is the exception: that is the documented dev-only mode and it
 * bypasses the backend entirely, so a stray `headers` beside it configures
 * nothing.
 */
export function assertNotLegacyShape(config: KLSwitchConfig): void {
  const c = config as MaybeLegacy;
  const usedLegacy = LEGACY_KEYS.some((k) => k in c);
  if (!usedLegacy) return;

  const usedNew = typeof c.token === "string" && c.token.length > 0;
  if (!usedNew && c.rules) return; // dev-only inline rules: nothing to misread

  const present = LEGACY_KEYS.filter((k) => k in c);
  throw new Error(
    "KLSwitch config: the SDK config was refactored. Replace " +
      `{ ${present.join(", ")} } with { token, instanceId?, baseUrl? }. ` +
      "See the README for the migration.",
  );
}
