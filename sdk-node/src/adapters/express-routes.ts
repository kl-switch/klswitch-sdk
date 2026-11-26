/**
 * Templated-route resolution for an app-wide Express mount.
 *
 * Express only populates `req.route` **inside** a matched route, but
 * `app.use()` middleware runs before routing — so an app-wide guard would
 * otherwise report the concrete path (`/v1/users/8123`) and every rule written
 * against a template (`/v1/users/:id`) would silently fail to match.
 *
 * That mismatch is worse than it looks, because the manifest reports templates:
 * the console autocompletes `/v1/users/:id` from the discovered routes, so the
 * rule an operator is nudged to write is exactly the one that would not fire.
 *
 * The fix mirrors `chimware` in the Go SDK: given the app, resolve the route
 * ourselves before evaluating, by walking Express's own router stack and using
 * its own matchers. Express re-matches during dispatch, so this changes no
 * routing behaviour — it only lets us name the route early.
 *
 * Express v4. v5 restructures the router, so `resolveTemplate` returns `null`
 * there and the caller falls back to the concrete path (degrading exactly as an
 * app-wide mount does today, never throwing).
 */

/** The parts of an Express layer we read. Structural, so no Express dependency. */
interface ExpressLayer {
  route?: {
    path?: string | RegExp | Array<string | RegExp>;
    methods?: Record<string, boolean>;
  };
  /** Present on mounted sub-routers. */
  handle?: { stack?: ExpressLayer[] };
  regexp?: RegExp;
  /** Express's own matcher; mutates layer state, which dispatch then redoes. */
  match?: (path: string) => boolean;
  /** Populated by `match()` — the path params captured by this layer. */
  params?: Record<string, string>;
  /** path-to-regexp key metadata, in capture-group order. */
  keys?: Array<{ name?: string | number }>;
}

/** What a successful resolution yields. */
export interface ResolvedRoute {
  /** Templated route, e.g. `/v1/orders/:id/refund`. */
  template: string;
  /**
   * Path params captured along the way, merged across mounted routers.
   *
   * Express only fills `req.params` during route dispatch, which happens *after*
   * app-level middleware — so without this an app-wide mount would evaluate with
   * an empty `params` bucket and every `params.*` condition would read as a
   * missing field. Recovering them here keeps the facts identical to a per-route
   * mount.
   */
  params: Record<string, string>;
}

/**
 * Structural shape of an Express app — deliberately `unknown`-typed.
 *
 * Express's own `Application` types `router` differently across versions, so a
 * precise shape here would refuse the very object callers pass in. We narrow at
 * the point of use instead, where an unexpected shape already degrades to the
 * concrete path.
 */
export interface ExpressLikeApp {
  _router?: unknown;
  router?: unknown;
}

/** Depth guard: a pathological mount graph should degrade, not hang a request. */
const MAX_DEPTH = 32;

/**
 * Rebuild a mount prefix as a template: `/orgs/acme` → `/orgs/:orgId`.
 *
 * A mounted router can itself capture params (`app.use("/orgs/:orgId", r)`), and
 * the text a regexp matched is the *concrete* prefix. Reporting that verbatim
 * would produce `/orgs/acme/members/:userId` — a path that is neither the
 * template a rule is written against nor stable across tenants.
 *
 * Capture indices (the `d` flag) give the exact span of each group, so each is
 * replaced by name rather than by searching for its value — a value that also
 * occurs as a literal elsewhere in the prefix would otherwise be rewritten too.
 * Returns the concrete prefix unchanged when there is nothing to substitute.
 */
function templatiseMount(layer: ExpressLayer, matched: string): string {
  const keys = layer.keys;
  if (!layer.regexp || !keys?.length) return matched;
  try {
    const withIndices = new RegExp(layer.regexp.source, layer.regexp.flags + "d");
    const m = withIndices.exec(matched) as (RegExpExecArray & {
      indices?: Array<[number, number] | undefined>;
    }) | null;
    if (!m?.indices) return matched;

    let out = "";
    let cursor = 0;
    for (let i = 0; i < keys.length; i++) {
      const span = m.indices[i + 1];
      const name = keys[i]?.name;
      if (!span || name === undefined) continue;
      out += matched.slice(cursor, span[0]) + ":" + String(name);
      cursor = span[1];
    }
    return out + matched.slice(cursor);
  } catch {
    return matched;
  }
}

function rootStack(app: ExpressLikeApp): ExpressLayer[] {
  const router = (app._router ?? app.router) as { stack?: unknown } | undefined;
  const stack = router?.stack;
  return Array.isArray(stack) ? (stack as ExpressLayer[]) : [];
}

function walk(
  stack: ExpressLayer[],
  path: string,
  method: string,
  prefix: string,
  params: Record<string, string>,
  depth: number,
): ResolvedRoute | null {
  if (depth > MAX_DEPTH) return null;

  for (const layer of stack) {
    if (layer.route) {
      // Only a plain-string route path is a usable template; a RegExp route has
      // no template to report, so leave it to the concrete-path fallback.
      const routePath = layer.route.path;
      if (typeof routePath !== "string") continue;
      if (layer.route.methods && layer.route.methods[method] !== true) continue;
      if (typeof layer.match === "function" && layer.match(path)) {
        return {
          template: prefix + routePath,
          params: { ...params, ...(layer.params ?? {}) },
        };
      }
      continue;
    }

    // Mounted sub-router: consume its prefix and recurse on the remainder. The
    // mount itself can capture params too (`app.use("/orgs/:orgId", r)`), so
    // match it rather than only testing the regexp.
    const substack = layer.handle?.stack;
    if (!substack || !layer.regexp) continue;
    const m = layer.regexp.exec(path);
    if (!m) continue;
    if (typeof layer.match === "function") layer.match(path);
    const consumed = m[0].endsWith("/") ? m[0].slice(0, -1) : m[0];
    const rest = path.slice(consumed.length) || "/";
    const found = walk(
      substack,
      rest,
      method,
      prefix + templatiseMount(layer, consumed),
      { ...params, ...(layer.params ?? {}) },
      depth + 1,
    );
    if (found) return found;
  }

  return null;
}

/**
 * Resolve the templated route and path params for `method` + `path`, or `null`
 * when no route matches (a 404, an unsupported Express version, or a RegExp
 * route).
 *
 * `path` must be the full request path with the query string already stripped.
 */
export function resolveTemplate(
  app: ExpressLikeApp,
  method: string,
  path: string,
): ResolvedRoute | null {
  try {
    return walk(rootStack(app), path, method.toLowerCase(), "", {}, 0);
  } catch {
    // Router internals are not a public API. If a future Express reshapes them,
    // degrade to the concrete path rather than breaking the customer's request.
    return null;
  }
}
