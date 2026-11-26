/**
 * SDK wrapper surface — /v1/ruleset.
 *
 * v1.1 server-driven polling. Every response carries:
 *   - ETag             : strong content fingerprint of the ruleset bytes
 *   - Cache-Control    : max-age=<seconds> — wrapper's next-poll cadence
 *
 * Wrappers HEAD first (cheap version probe — no body), then GET only when
 * the ETag has changed, sending If-None-Match for race safety (server
 * replies 304 if unchanged).
 *
 * Scoped by query params: ?service=<key>&env=<dev|staging|prod>. The
 * wrapper's existing rulesUrl shape just needs `?service=…&env=…`
 * appended — no new client code.
 *
 * The Cache-Control max-age is a single constant for now (frozen per-tier
 * defaults land later — see `sdk/todo.md` § B1). The handler is shaped so
 * the tier lookup can replace the constant without touching the contract.
 */
import { createHash } from "node:crypto";

import type { Context } from "hono";
import { Hono } from "hono";

import { store } from "../store/index.js";
import type { RuleSetPayload } from "../zod/index.js";

export const wrapper = new Hono();

/** Default server-driven poll cadence in seconds. Per-tier table lands later. */
export const DEFAULT_POLL_MAX_AGE_SECONDS = 30;

interface ResolvedRuleset {
  payload: RuleSetPayload;
  etag:    string;
  maxAge:  number;
}

/**
 * Resolve the (service, env) pair from query params, build the payload,
 * and compute its ETag. Returns null + writes an error response to `c`
 * when the inputs are invalid — the handler short-circuits on null.
 */
function resolve(c: Context): ResolvedRuleset | null {
  const serviceKey = c.req.query("service");
  const envKey     = c.req.query("env");
  if (!serviceKey || !envKey) {
    c.json({ error: "service and env query params required" }, 400);
    return null;
  }

  const svc = store.serviceByKey(serviceKey);
  if (!svc) {
    c.json({ error: `unknown service "${serviceKey}"` }, 404);
    return null;
  }

  const env = store.envForService(svc.id, envKey);
  if (!env) {
    c.json({ error: `unknown env "${envKey}" for service "${serviceKey}"` }, 404);
    return null;
  }

  const published = store.publishedRulesFor(svc.id, env.id);
  const payload: RuleSetPayload = {
    schema_version: "1.0",
    rules_version:  store.rulesVersion(env.id),
    rules:          published.map((p) => p.version.body),
  };
  return {
    payload,
    etag:   computeEtag(payload),
    maxAge: DEFAULT_POLL_MAX_AGE_SECONDS,
  };
}

/**
 * Strong ETag — sha256 of the canonical JSON bytes. Strong because we
 * care about the exact bytes the wrapper feeds the engine; whitespace /
 * key-order changes WOULD break engine identity (the WASM compiler is
 * byte-sensitive in its hash) so they MUST bust the tag.
 */
function computeEtag(payload: RuleSetPayload): string {
  const body = JSON.stringify(payload);
  const hash = createHash("sha256").update(body).digest("hex");
  return `"sha256-${hash}"`;
}

function setVersionHeaders(c: Context, r: ResolvedRuleset): void {
  c.header("ETag",          r.etag);
  c.header("Cache-Control", `max-age=${r.maxAge}`);
}

// ── HEAD: cheap version probe (empty body, headers only) ────────────────

wrapper.on("HEAD", "/ruleset", (c) => {
  const r = resolve(c);
  if (!r) return c.res;          // resolve() already wrote the error
  setVersionHeaders(c, r);
  return c.body(null, 200);
});

// ── GET: full ruleset (or 304 if If-None-Match matches) ────────────────

wrapper.get("/ruleset", (c) => {
  const r = resolve(c);
  if (!r) return c.res;
  setVersionHeaders(c, r);

  // Wrapper's conditional-GET fast path. Honor both quoted and bare
  // forms ("…" and …) since the spec allows either on the wire but
  // canonical is quoted.
  const inm = c.req.header("If-None-Match");
  if (inm && etagMatches(inm, r.etag)) {
    return c.body(null, 304);
  }
  return c.json(r.payload);
});

/**
 * RFC 7232 §3.2: client may send the ETag, or a comma-separated list, or
 * `*`. We only emit a single strong tag, so the only "match" cases worth
 * supporting are equality and `*`. Whitespace tolerance keeps the
 * comparison robust against well-meaning proxies.
 */
function etagMatches(headerValue: string, current: string): boolean {
  for (const candidate of headerValue.split(",")) {
    const trimmed = candidate.trim();
    if (trimmed === "*" || trimmed === current) return true;
  }
  return false;
}
