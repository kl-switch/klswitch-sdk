import type { ManifestRoute, ManifestSource } from "../manifest.js";
import { discoverExpressRoutes, type ExpressLikeApp } from "./express.js";
import { discoverFastifyRoutes, type FastifyLikeInstance } from "./fastify.js";

/**
 * NestJS route discovery.
 *
 * NestJS doesn't ship a route registry of its own — every request
 * ultimately lands on the underlying HTTP adapter (Express or
 * Fastify). The runtime route table the operator cares about is the
 * one the adapter holds. So discovery here is "ask Nest for the
 * adapter's underlying instance, then delegate to the
 * express / fastify discoverer."
 *
 * Bootstrapping order matters. The source is constructed BEFORE
 * `NestFactory.create(...)` returns and before `app.init()` runs, but
 * we need routes from the post-`init()` registry. Two patterns work:
 *
 *  1. Lazy reference — pass a thunk:
 *
 *     ```ts
 *     let app: INestApplication;
 *     const ks = await KLSwitch.init({
 *       token:    process.env.KS_TOKEN!,
 *       manifest: nestjsManifestSource(() => app),
 *     });
 *     app = await NestFactory.create(AppModule);
 *     await app.init();
 *     ```
 *
 *     No manual push needed: `app.init()` is async and finishes after the
 *     deferred init-time push, but the client re-checks discovery on the
 *     first evaluation, by which point the route table is complete.
 *
 *  2. Eager (build the client AFTER `app.init()`):
 *
 *     ```ts
 *     const app = await NestFactory.create(AppModule);
 *     await app.init();
 *     const ks = await KLSwitch.init({
 *       token:    process.env.KS_TOKEN!,
 *       manifest: nestjsManifestSource(app),
 *     });
 *     ```
 *
 * Both are valid. Pattern (1) fits when the Client is wired into the
 * Nest container itself (KlswitchModule.forRoot) — the Client must
 * exist before the module graph is built.
 *
 * Discovery is best-effort. If we can't reach the underlying adapter
 * (e.g. operator passed a stub, or app.init() hasn't run yet) we
 * return `[]` — the manifest reporter logs the empty push and the
 * console falls back to free-text route entry. No regression.
 */

/** Structural shape — what we read off an INestApplication. */
export interface NestLikeApp {
  /** NestJS exposes the underlying HTTP adapter via `getHttpAdapter()`. */
  getHttpAdapter?: () => NestLikeHttpAdapter;
}

export interface NestLikeHttpAdapter {
  /** Returns the underlying express or fastify instance. */
  getInstance?: () => unknown;
  /** Adapter type — `"express"` / `"fastify"`. Optional; we fall back to
   *  structural detection (presence of `_router` vs `printRoutes`). */
  getType?: () => string;
}

/**
 * Returns a {@link ManifestSource} that walks the underlying Express or
 * Fastify adapter for a NestJS app.
 *
 * Accepts either an INestApplication directly, or a thunk that returns
 * one — the latter for the boot-ordering case where the Client must be
 * built before the app exists.
 */
export function nestjsManifestSource(
  app: NestLikeApp | (() => NestLikeApp | null | undefined),
): ManifestSource {
  const resolveApp = (): NestLikeApp | null => {
    if (typeof app === "function") {
      const a = app();
      return a ?? null;
    }
    return app;
  };
  return {
    framework: "nestjs",
    collect:   () => discoverNestjsRoutes(resolveApp()),
  };
}

/** Exposed for tests. */
export function discoverNestjsRoutes(app: NestLikeApp | null): ManifestRoute[] {
  if (!app || typeof app.getHttpAdapter !== "function") return [];
  let adapter: NestLikeHttpAdapter;
  try {
    adapter = app.getHttpAdapter();
  } catch {
    return [];
  }
  if (!adapter || typeof adapter.getInstance !== "function") return [];
  let instance: unknown;
  try {
    instance = adapter.getInstance();
  } catch {
    return [];
  }
  // An Express app — and a Fastify instance — is a *callable*: `typeof app` is
  // "function", not "object". Rejecting non-objects here discarded every NestJS
  // route before discovery began, and silently: the reporter pushed an empty
  // manifest, the hash never changed, and the first-evaluation re-check that
  // exists for exactly this case re-ran the same discovery and got the same
  // nothing. Found by running the adapter against a live backend, where NestJS
  // reported 0 routes while its 18 endpoints served traffic.
  if (!instance || (typeof instance !== "object" && typeof instance !== "function")) return [];

  // Prefer the adapter's self-declared type; fall back to structural
  // detection so a custom adapter still works as long as it returns the
  // shape express / fastify expose.
  const declaredType =
    typeof adapter.getType === "function" ? safeGetType(adapter) : null;

  if (declaredType === "express" || hasExpressShape(instance)) {
    return discoverExpressRoutes(instance as ExpressLikeApp);
  }
  if (declaredType === "fastify" || hasFastifyShape(instance)) {
    return discoverFastifyRoutes(instance as FastifyLikeInstance);
  }
  return [];
}

function safeGetType(adapter: NestLikeHttpAdapter): string | null {
  try { return adapter.getType?.() ?? null; }
  catch { return null; }
}

function hasExpressShape(instance: unknown): boolean {
  return (
    isIntrospectable(instance) &&
    "_router" in (instance as Record<string, unknown>)
  );
}

/** Object or callable — see the note in {@link discoverNestjsRoutes}. */
function isIntrospectable(v: unknown): boolean {
  return v !== null && (typeof v === "object" || typeof v === "function");
}

function hasFastifyShape(instance: unknown): boolean {
  return (
    isIntrospectable(instance) &&
    typeof (instance as { printRoutes?: unknown }).printRoutes === "function"
  );
}
