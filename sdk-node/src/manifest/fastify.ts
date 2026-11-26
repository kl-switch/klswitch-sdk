import type { ManifestRoute, ManifestSource } from "../manifest.js";

/**
 * Fastify route discovery.
 *
 * Fastify v4+ exposes routes via {@link FastifyLikeInstance.printRoutes}
 * which returns a multi-line ASCII tree. We don't want to depend on a
 * specific Fastify version's internal `_routes`/`_router` structures
 * (they change between v4 and v5), so we parse the ASCII tree —
 * lossy-but-stable across versions.
 *
 * If your Fastify version doesn't expose `printRoutes`, supply your own
 * source: `fastifyManifestSource(app, { collect: () => myRoutes })`.
 */

const HTTP_METHODS = new Set([
  "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS",
]);

/** Structural shape — just what we read. Lets the SDK avoid a Fastify dep. */
export interface FastifyLikeInstance {
  /** Available in Fastify v4+. Returns the route tree as a string. */
  printRoutes?: (opts?: { commonPrefix?: boolean; includeMeta?: boolean }) => string;
}

/**
 * Returns a {@link ManifestSource} that walks Fastify's route registry.
 * Pass the Fastify instance — typically called once at startup, after
 * all routes are registered:
 *
 * ```ts
 * const ks = await KLSwitch.init({
 *   token: process.env.KS_TOKEN!,
 *   manifest: fastifyManifestSource(fastify),
 * });
 * ```
 */
export function fastifyManifestSource(app: FastifyLikeInstance): ManifestSource {
  return {
    framework: "fastify",
    collect: () => discoverFastifyRoutes(app),
  };
}

/** Exposed for tests. Discovery is sync; ManifestSource type allows async too. */
export function discoverFastifyRoutes(app: FastifyLikeInstance): ManifestRoute[] {
  if (typeof app.printRoutes !== "function") {
    // Older Fastify, or a stripped-down stub. We choose to return [] rather
    // than throw — the manifest reporter logs the empty push and the
    // console renders no autocomplete for that instance. No regression.
    return [];
  }
  let raw: string;
  try {
    raw = app.printRoutes({ commonPrefix: false });
  } catch {
    return [];
  }
  return parsePrintRoutes(raw);
}

/**
 * Parse Fastify's `printRoutes({ commonPrefix: false })` ASCII tree.
 *
 * The real shape, from Fastify v4 (`app.printRoutes({ commonPrefix: false })`):
 *
 *     ├── /health (GET, HEAD)
 *     ├── /api/items (GET, HEAD)
 *     │   └── /:id (GET, HEAD)
 *     │       └── /reviews/:reviewId (GET, HEAD)
 *     └── /api/orders (POST)
 *         └── /:id (PUT, DELETE)
 *             └── /status (PATCH)
 *
 * **Each line carries only its own segment, not the full path.** This used to
 * assume the opposite — that `commonPrefix: false` made every line
 * fully-qualified — and reported `/:id`, `/reviews/:reviewId` and `/status` as
 * though they were top-level endpoints. A rule written against
 * `/api/orders/:id` then matched nothing the console offered, and the route
 * picker listed fragments no request could ever have. `commonPrefix: false`
 * only stops Fastify compressing shared *static* prefixes into one node; it
 * does not flatten the tree.
 *
 * So indentation is load-bearing: four spaces per level, and a node's path is
 * its ancestors' segments plus its own. Nodes with no `(METHODS)` are branches
 * rather than routes — they contribute a segment and no entry of their own.
 *
 * Exported for tests; not part of the SDK's public surface.
 */
export function parsePrintRoutes(raw: string): ManifestRoute[] {
  const out: ManifestRoute[] = [];
  /** Segment contributed by the node at each depth, so a child can rebuild its path. */
  const segments: string[] = [];

  for (const line of raw.split("\n")) {
    // The connector marks where this node's own text begins; what precedes it is
    // four spaces (or "│   ") per level of nesting.
    const connector = line.search(/[├└]── /);
    if (connector < 0) continue;
    const depth = Math.floor(connector / 4);
    const stripped = line.slice(connector + 4);
    if (!stripped) continue;

    // Match "<segment> (M1, M2)" for a route, or a bare "<segment>" for a branch.
    const withMethods = stripped.match(/^(\S+)\s+\(([^)]+)\)\s*$/);
    const segment = withMethods ? withMethods[1]! : stripped.trim();
    if (!segment) continue;

    // Record before emitting: a node is a prefix for its children whether or not
    // it is itself a route, and a branch node carries no methods of its own.
    segments.length = depth;
    segments[depth] = segment;

    if (!withMethods) continue;
    const methods = dropPairedHead(
      withMethods[2]!.split(",").map((s) => s.trim().toUpperCase()).filter(
        (s) => HTTP_METHODS.has(s),
      ),
    );
    if (methods.length === 0) continue;
    const path = normalisePath(segments.slice(0, depth + 1).join(""));
    for (const method of methods) {
      out.push({ method, path });
    }
  }
  return out;
}

/**
 * Drop the HEAD that Fastify attaches to every GET.
 *
 * Fastify registers a HEAD alongside each GET unless told otherwise, so
 * reporting both doubles the route count with a verb the application never
 * declared — and the console then offers rule authors a HEAD endpoint that
 * exists only because of a framework default. A route registered HEAD-only keeps
 * it: the filter fires on the pair, not on the verb. Matches the Flask and
 * FastAPI adapters, which drop their frameworks' equivalent.
 */
function dropPairedHead(methods: string[]): string[] {
  if (!methods.includes("GET")) return methods;
  return methods.filter((m) => m !== "HEAD");
}

/**
 * Fastify's `printRoutes` emits paths without a leading `/`. Add one so
 * the result matches what `selector.keys.path` carries in rules.
 *
 * (`commonPrefix: false` makes each path full-qualified, so this is a
 * one-character fix rather than a tree-walk.)
 */
function normalisePath(p: string): string {
  if (p === "") return "/";
  return p.startsWith("/") ? p : `/${p}`;
}
