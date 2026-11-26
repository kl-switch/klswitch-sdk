/**
 * Boot-time disk cache for the last-known-good ruleset.
 *
 * Operator opts in via `config.cachePath`. After every successful
 * remote fetch the wrapper persists the accepted ruleset to that path
 * (atomic: tmpfile + rename). When the SDK boots, if the very first
 * fetch fails AND a cache file exists, the wrapper loads the cache
 * and serves traffic against last-known-good while polling keeps
 * retrying the real backend in the background — so an outage at
 * customer boot time no longer means "no rules".
 *
 * Pure file IO. The fall-back / persist decisions live in
 * `client.ts`; this module just handles read/write.
 */

import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";

import type { Logger } from "./logger.js";
import type { RuleSetPayload } from "./types.js";

/**
 * Build the conventional cache path for `${tmpdir}/ks-cache-<env>.json`.
 * Operators can supply their own path verbatim — this is just the helper
 * for the common case.
 */
export function defaultCachePath(env: string): string {
  const safe = (env || "default").replace(/[^a-zA-Z0-9._-]/g, "_");
  return join(tmpdir(), `ks-cache-${safe}.json`);
}

/**
 * Read the cached ruleset from `path`. Returns null if the file doesn't
 * exist, is unreadable, or doesn't parse as a valid ruleset payload.
 * Never throws — a corrupt cache is just "no cache available".
 */
export function readCachedRuleset(
  path: string,
  logger: Logger,
): RuleSetPayload | null {
  if (!existsSync(path)) return null;
  let raw: string;
  try {
    raw = readFileSync(path, "utf8");
  } catch (err) {
    logger.warn("ruleset cache unreadable; ignoring", { path, error: String(err) });
    return null;
  }
  if (!raw) {
    logger.warn("ruleset cache is empty; ignoring", { path });
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as RuleSetPayload;
    // Minimal shape check — the engine will validate fully on loadRules.
    if (
      !parsed ||
      typeof parsed !== "object" ||
      !("schema_version" in parsed) ||
      !Array.isArray((parsed as RuleSetPayload).rules)
    ) {
      logger.warn("ruleset cache shape invalid; ignoring", { path });
      return null;
    }
    return parsed;
  } catch (err) {
    logger.warn("ruleset cache JSON parse failed; ignoring", { path, error: String(err) });
    return null;
  }
}

/**
 * Atomically persist `payload` to `path`. Writes to a sibling
 * `<path>.tmp` file then renames into place — so a crash mid-write
 * leaves either the previous good cache OR the new one, never a
 * truncated file.
 *
 * Best-effort: any IO error is logged and swallowed. Disk cache is
 * boot-time resilience, not a critical-path dependency.
 */
export function writeCachedRuleset(
  path: string,
  payload: RuleSetPayload,
  logger: Logger,
): void {
  const tmp = `${path}.tmp`;
  try {
    // Ensure parent directory exists — operators sometimes point at
    // a subdir that hasn't been pre-created.
    const dir = dirname(path);
    if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
    writeFileSync(tmp, JSON.stringify(payload), { encoding: "utf8" });
    renameSync(tmp, path);
  } catch (err) {
    logger.warn("ruleset cache write failed", { path, error: String(err) });
    // Try to clean up the tmpfile so it doesn't linger forever.
    try { if (existsSync(tmp)) unlinkSync(tmp); }
    catch { /* nothing more we can do */ }
  }
}
