import type { EvaluationInput } from "./types.js";

/**
 * Framework-agnostic request parts. Adapters extract these from their request
 * object (preferring the *templated* route path) and hand them here; this module
 * builds the stable `EvaluationInput`. No framework types leak in.
 */
export interface RequestParts {
  method: string;
  /** Prefer the templated route (e.g. `/v1/payments/:id`), not the concrete path. */
  path: string;
  headers?: Record<string, unknown>;
  query?: Record<string, unknown>;
  params?: Record<string, unknown>;
  body?: unknown;
  meta?: Record<string, unknown>;
}

/** Coerce a value to a plain object; non-objects (or null/arrays) become `{}`. */
function toRecord(value: unknown): Record<string, unknown> {
  if (value !== null && typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return {};
}

/**
 * Lowercase every header key. HTTP headers are case-insensitive but the
 * engine's JSON-fact lookup is case-sensitive (`headers.content-type` ≠
 * `headers.Content-Type`); without this pass, an auth middleware that sets
 * `req.headers["X-User-Id"] = ...` would silently fail to match a rule on
 * `headers.x-user-id`. Matches Java / Go / Python (all four canonically
 * lowercase). See docs/sdk-wrapper-contract.md §request-translation.
 */
function normalizeHeaders(value: unknown): Record<string, unknown> {
  const raw = toRecord(value);
  const out: Record<string, unknown> = {};
  for (const k of Object.keys(raw)) {
    out[k.toLowerCase()] = raw[k];
  }
  return out;
}

/**
 * Collapse repeated query keys to the FIRST value. Express's default `qs`
 * parser produces arrays for repeats (`?role=admin&role=user` →
 * `{role: ["admin","user"]}`), which would silently disagree with Java / Go /
 * Python — they all take the first value. First-value is the cross-language
 * canonical policy (see docs/sdk-wrapper-contract.md §request-translation).
 */
function normalizeQuery(value: unknown): Record<string, unknown> {
  const raw = toRecord(value);
  const out: Record<string, unknown> = {};
  for (const k of Object.keys(raw)) {
    const v = raw[k];
    out[k] = Array.isArray(v) ? (v.length > 0 ? v[0] : undefined) : v;
  }
  return out;
}

/** Strip a query string / fragment from a raw URL, leaving just the path. */
export function pathWithoutQuery(url: string): string {
  const end = url.search(/[?#]/);
  return end === -1 ? url : url.slice(0, end);
}

/**
 * Build a v1 `EvaluationInput` from request parts. Normalizes:
 *   - method → uppercase (engine matches method/path exactly; rule-model §8)
 *   - header keys → lowercase (cross-language parity with Java/Go/Python)
 *   - repeated query keys → first value (cross-language parity)
 *   - all five fact buckets present (empty objects when absent).
 */
/**
 * Accepts any of the three common route-template dialects and returns the engine's.
 *
 * `{id}` (FastAPI/Starlette, Spring), `<id>` / `<int:id>` (Flask/Werkzeug) and `:id` (engine,
 * Express) all become `:id`.
 *
 * Applied automatically by {@link toEvaluationInput}. A host integrating without an adapter
 * otherwise has to know the engine's exact dialect, and the failure when they get it wrong is
 * close to invisible: rules whose selectors carry no path parameter still match, so the
 * integration looks healthy while every parameterised rule silently misses. Braces and angle
 * brackets are not valid in an engine selector, so rewriting them cannot change the meaning of
 * a path that was already correct.
 */
export function canonicalizeTemplate(path: string): string {
  return path
    // <int:id> / <id>  →  :id   (drop any Werkzeug converter prefix)
    .replace(/<(?:[^:>]+:)?([^>]+)>/g, (_m, name: string) => `:${name}`)
    // {file_path:path} / {id}  →  :file_path / :id  (drop any Starlette converter suffix)
    .replace(/\{([^}:]+)(?::[^}]+)?\}/g, (_m, name: string) => `:${name}`);
}

export function toEvaluationInput(parts: RequestParts): EvaluationInput {
  return {
    selector: {
      kind: "api",
      keys: { method: parts.method.toUpperCase(), path: canonicalizeTemplate(parts.path) },
    },
    facts: {
      headers: normalizeHeaders(parts.headers),
      query: normalizeQuery(parts.query),
      params: toRecord(parts.params),
      body: toRecord(parts.body),
      meta: toRecord(parts.meta),
    },
  };
}
