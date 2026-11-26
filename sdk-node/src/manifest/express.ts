import type { ManifestRoute, ManifestSource } from "../manifest.js";

/**
 * Express route discovery.
 *
 * Express exposes its routing table via `app._router.stack` — an array
 * of "layer" objects. Each layer is either a route handler (has
 * `layer.route`) or a middleware / mounted sub-router (has
 * `layer.handle`). We walk it recursively, concatenating mount paths
 * along the way.
 *
 * Extracting a mount path from a layer requires reversing the regex
 * that `path-to-regexp` generated. We do this on a best-effort basis
 * — recognised patterns are converted back to the original templated
 * path (e.g. `/users/:id`); unrecognisable regexps are skipped (the
 * SDK chooses partial coverage over a thrown discovery).
 *
 * Targets Express v4 (the dominant version). Express v5 uses a
 * different path-to-regexp output format; v5 support will land when v5
 * ships GA.
 */

const HTTP_METHODS = new Set([
  "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS",
]);

/** Structural shape — just what we read. Lets the SDK avoid an Express dep. */
export interface ExpressLikeApp {
  /** Express ≥ 4.x app. The runtime router is lazy-initialised; calling
   * `app.get`/`app.use` triggers it, so by the time discovery runs the
   * registry is materialised. */
  _router?: ExpressLikeRouter;
}

export interface ExpressLikeRouter {
  stack?: ExpressLikeLayer[];
}

export interface ExpressLikeLayer {
  name?:   string;
  /** Present only on layers created by `app.METHOD()` / `router.METHOD()`. */
  route?: {
    path:    string | string[];
    methods: Record<string, boolean>;
  };
  /** Present on `app.use(...)` layers (middleware or mounted router). */
  handle?: ExpressLikeRouter | ((...args: unknown[]) => unknown);
  /** path-to-regexp output for the mount point (or for parametric routes). */
  regexp?: RegExp & { fast_slash?: boolean; fast_star?: boolean };
  /** Decoded param names, populated by path-to-regexp. */
  keys?: Array<{ name: string | number; optional?: boolean }>;
}

/**
 * Returns a {@link ManifestSource} that walks Express's route registry.
 * Pass the Express instance (or any object with `_router.stack`):
 *
 * ```ts
 * const ks = await KLSwitch.init({
 *   token:    process.env.KS_TOKEN!,
 *   manifest: expressManifestSource(app),
 * });
 * ```
 */
export function expressManifestSource(app: ExpressLikeApp): ManifestSource {
  return {
    framework: "express",
    collect: () => discoverExpressRoutes(app),
  };
}

/** Exposed for tests. */
export function discoverExpressRoutes(app: ExpressLikeApp): ManifestRoute[] {
  const stack = app._router?.stack;
  if (!Array.isArray(stack)) return [];
  const out: ManifestRoute[] = [];
  walk(stack, "", out);
  return out;
}

/**
 * Recursive walk over an Express router stack. `prefix` is the
 * accumulated mount path from outer `app.use()` calls.
 */
function walk(stack: ExpressLikeLayer[], prefix: string, out: ManifestRoute[]): void {
  for (const layer of stack) {
    if (layer.route) {
      // Concrete route — emit one ManifestRoute per (path, method).
      const paths = Array.isArray(layer.route.path) ? layer.route.path : [layer.route.path];
      for (const p of paths) {
        const fullPath = joinPaths(prefix, p);
        for (const [method, enabled] of Object.entries(layer.route.methods)) {
          if (!enabled) continue;
          const m = method.toUpperCase();
          if (!HTTP_METHODS.has(m)) continue;
          out.push({ method: m, path: fullPath });
        }
      }
      continue;
    }
    if (layer.name === "router" && layer.handle && isRouter(layer.handle)) {
      // Mounted sub-router — extract the mount path and recurse.
      const mount = extractMountPath(layer);
      if (mount === null) {
        // Unrecognisable mount regexp — skip rather than emit a wrong path.
        continue;
      }
      walk(layer.handle.stack ?? [], joinPaths(prefix, mount), out);
    }
    // Other middleware layers (no `route`, name !== 'router') are ignored.
  }
}

function isRouter(handle: unknown): handle is ExpressLikeRouter {
  return typeof handle === "object" && handle !== null && Array.isArray((handle as ExpressLikeRouter).stack);
}

/**
 * Extract the mount path of a router layer from its regexp. Returns
 * `""` for the trivial `app.use(router)` case, or `null` if the regexp
 * doesn't look like one Express generated (so the caller knows to skip).
 *
 * Express marks the trivial `/` mount with `regexp.fast_slash = true`.
 * Other mounts have a regexp source like `/^\/api(?=\/|$)/i` (v4) which
 * we strip back to `/api`.
 */
export function extractMountPath(layer: ExpressLikeLayer): string | null {
  const r = layer.regexp;
  if (!r) return "";
  if (r.fast_slash) return "";
  // Express v4 mount regex shape: /^\/<literal>\/?(?=\/|$)/i
  // We strip the anchors and lookahead to recover <literal>.
  // Note: this only handles static mount paths (the common case). Mount
  // paths with params are rare and not handled here — we return null
  // so the caller skips that sub-tree.
  const src = r.source;
  const m = src.match(/^\^\\?\/?(.+?)\\?\/\??\(\?=\\?\/\|\$\)\$?$/);
  if (!m) return null;
  // Un-escape forward slashes and dots that path-to-regexp escaped.
  const literal = m[1]!.replace(/\\\//g, "/").replace(/\\\./g, ".");
  // Reject if there are still regex metacharacters — that means we
  // landed on a parametric mount we don't understand.
  if (/[\\^$+*?(){}|[\]]/.test(literal)) return null;
  return literal.startsWith("/") ? literal : `/${literal}`;
}

/**
 * Concatenate a mount prefix and a route path, normalising slashes.
 * Empty prefix is a no-op so the routes from `app.use(router)` come
 * through unchanged.
 */
export function joinPaths(prefix: string, path: string): string {
  if (!prefix || prefix === "/") return path;
  const left  = prefix.endsWith("/") ? prefix.slice(0, -1) : prefix;
  const right = path.startsWith("/")  ? path                : `/${path}`;
  return `${left}${right}`;
}
