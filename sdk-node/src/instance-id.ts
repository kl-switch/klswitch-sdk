import { hostname } from "node:os";

import type { Logger } from "./logger.js";

/**
 * Instance-key resolution.
 *
 * The instance key answers "which runtime is this?". The token answers
 * "which service is this?". They're complementary: one token is shared
 * across every pod of a service; each pod has a distinct instance key.
 *
 * Resolution order (first valid wins):
 *
 *   1. config.instanceId               — operator passed it explicitly
 *   2. KS_INSTANCE_ID env var          — env-only config
 *   3. POD_NAME env var                — k8s downward API convention
 *   4. ${hostname}-${pid}-${startMs}   — deterministic per-process fallback
 *
 * Invalid values (charset / length) are skipped with a warning and the
 * chain continues — never crash, the operator is trying to give a hint
 * not block their app boot.
 */

const MAX_LEN = 128;
const VALID = /^[a-zA-Z0-9_\-.:/]+$/;

export type InstanceIdSource = "config" | "env" | "pod" | "derived";

export interface InstanceIdResolution {
  value:  string;
  source: InstanceIdSource;
}

/** True iff the input is non-empty and matches the soft-validation rules. */
export function isValidInstanceId(v: string | undefined | null): v is string {
  if (typeof v !== "string") return false;
  const trimmed = v.trim();
  if (trimmed.length === 0)      return false;
  if (trimmed.length > MAX_LEN)  return false;
  return VALID.test(trimmed);
}

/**
 * Resolve the instance key, walking the fallback chain. The result and the
 * source it came from are returned together so the caller can log which
 * mechanism won — useful when an operator's k8s-downward POD_NAME isn't
 * arriving in production.
 */
export function resolveInstanceId(
  configValue: string | undefined,
  env:    NodeJS.ProcessEnv = process.env,
  logger: Logger | undefined = undefined,
): InstanceIdResolution {
  const candidates: ReadonlyArray<{ value: string | undefined; source: InstanceIdSource }> = [
    { value: configValue,        source: "config" },
    { value: env.KS_INSTANCE_ID, source: "env"    },
    { value: env.POD_NAME,       source: "pod"    },
  ];

  for (const c of candidates) {
    if (c.value === undefined || c.value === "") continue;
    if (isValidInstanceId(c.value)) {
      return { value: c.value.trim(), source: c.source };
    }
    logger?.warn("instanceId candidate rejected — falling through", {
      source: c.source,
      reason: "value violates charset/length rules",
    });
  }

  return { value: deriveInstanceId(), source: "derived" };
}

/**
 * Deterministic per-process derivation when the operator hasn't supplied
 * a value. `startMs` distinguishes restarts on the same host within the
 * same second (so a quick crash-restart loop doesn't collide).
 */
function deriveInstanceId(): string {
  const host = sanitize(hostname()) || "unknown";
  const pid  = process.pid;
  const startMs = Date.now();
  return `${host}-${pid}-${startMs}`;
}

function sanitize(s: string): string {
  // Hostnames can contain characters outside our validation charset
  // (rarely, but possible). Replace anything we don't allow with `-`.
  return s.replace(/[^a-zA-Z0-9_\-.:/]+/g, "-");
}
