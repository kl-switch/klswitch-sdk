import type { KLSwitch } from "../client.js";
import { pathWithoutQuery, toEvaluationInput } from "../request-context.js";
import type { EvaluationResult, MatchedRule } from "../types.js";
import { resolveTemplate, type ExpressLikeApp } from "./express-routes.js";

/**
 * Minimal structural shape of an Express request — just what the adapter reads.
 * Declared here so the SDK needs no dependency on Express or its types.
 */
export interface ExpressLikeRequest {
  method: string;
  path?: string;
  url?: string;
  baseUrl?: string;
  // Express types `route.path` as string | RegExp | (string | RegExp)[] — only a
  // plain string is a usable templated path; the rest fall back at runtime.
  route?: { path?: string | RegExp | Array<string | RegExp> };
  headers: Record<string, unknown>;
  query?: Record<string, unknown>;
  params?: Record<string, unknown>;
  body?: unknown;
  /** The adapter attaches the result here by default. */
  klswitch?: EvaluationResult;
}

export type ExpressNext = (err?: unknown) => void;

/** Minimal structural shape of an Express response — just what the adapter writes. */
export interface ExpressLikeResponse {
  status: (code: number) => unknown;
  json: (body: unknown) => unknown;
}

export interface ExpressAdapterOptions<Res = unknown> {
  /**
   * The Express app, so an **app-wide** mount can still report templated paths.
   *
   * Without it, `app.use(klswitchMiddleware(ks))` reports the concrete path
   * (`/v1/users/8123`), because Express only fills in `req.route` inside a
   * matched route. With it, the adapter resolves the route from the app's own
   * router before evaluating, so the engine sees `/v1/users/:id` — matching both
   * the rules you write and the routes the manifest reported.
   *
   * Not needed when the guard is attached per route (`app.post(p, guard, h)`),
   * where Express supplies `req.route` itself.
   */
  app?: ExpressLikeApp;

  /**
   * Short-circuit a matched `block` action with its `statusCode` (default 403)
   * and the canonical JSON body. Defaults to **true**, matching the Go, Java and
   * NestJS adapters.
   *
   * Set `false` to observe without enforcing — the result is still attached to
   * `req.klswitch` and the request continues.
   */
  autoBlock?: boolean;

  /**
   * Take full control of the decision, replacing `autoBlock`. If provided, your
   * callback **must** call `next()` (or end the response) itself — otherwise the
   * request hangs.
   */
  onResult?: (result: EvaluationResult, req: ExpressLikeRequest, res: Res, next: ExpressNext) => void;
}

/**
 * The canonical block response, identical across the Node, Go and Java adapters
 * so a blocked request looks the same whichever SDK served it.
 */
function writeBlock(res: ExpressLikeResponse, matched: MatchedRule): void {
  const payload = matched.action?.payload as Record<string, unknown> | undefined;
  const raw = payload?.statusCode;
  const status = typeof raw === "number" ? raw : 403;
  (res.status(status) as ExpressLikeResponse).json({
    blocked: true,
    ruleId: matched.id,
    action: matched.action,
  });
}

/**
 * Express middleware that evaluates every request and enforces `block` actions.
 *
 * Mount it once and never touch an endpoint again:
 *
 * ```ts
 * app.use(express.json());
 * app.use(klswitchMiddleware(ks, { app }));
 * ```
 *
 * Passing `app` lets an app-wide mount report the **templated** path, which is
 * what rules and the reported manifest are both written against. Attaching the
 * guard per route works too and needs no `app`, since Express then supplies
 * `req.route` itself.
 *
 * A matched `block` short-circuits with the action's `statusCode` (default 403)
 * and the canonical body — the same behaviour as the Go, Java and NestJS
 * adapters. Pass `autoBlock: false` to observe without enforcing, or `onResult`
 * to take over the decision entirely. The result is always attached to
 * `req.klswitch`.
 *
 * When chaining directly in a route (`app.post(path, mw, handler)`), annotate the
 * result as Express's `RequestHandler` so this adapter's structural request type
 * does not leak into Express's handler type inference:
 * `const mw: RequestHandler = klswitchMiddleware(ks)`.
 */
export function klswitchMiddleware<Res = unknown>(
  ks: KLSwitch,
  options: ExpressAdapterOptions<Res> = {},
) {
  let warnedDoubleMount = false;

  return (req: ExpressLikeRequest, res: Res, next: ExpressNext): void => {
    // Already evaluated on this request — the guard is mounted twice (commonly
    // app-wide *and* per-route, e.g. a half-finished migration). Evaluating
    // again would double the latency and, worse, emit a second analytics event,
    // silently inflating decision counts. Skip; the first result stands.
    if (req.klswitch !== undefined) {
      if (!warnedDoubleMount) {
        warnedDoubleMount = true;
        ks.log.warn(
          "klswitch middleware is mounted more than once on this request; the extra " +
            "evaluation is skipped. Remove either the app-wide mount or the per-route guard.",
        );
      }
      next();
      return;
    }

    let result: EvaluationResult;
    try {
      const { path, params } = resolveRequest(req, options.app);
      result = ks.evaluate(
        toEvaluationInput({
          method: req.method,
          path,
          headers: req.headers,
          query: req.query,
          params,
          body: req.body,
        }),
      );
    } catch (err) {
      next(err);
      return;
    }

    req.klswitch = result;

    if (options.onResult) {
      options.onResult(result, req, res, next);
      return;
    }

    if (options.autoBlock !== false) {
      const blocked = result.matched.find((m) => m.action?.type === "block");
      if (blocked) {
        writeBlock(res as unknown as ExpressLikeResponse, blocked);
        return;
      }
    }

    next();
  };
}

/**
 * The path and params the engine should see, in preference order:
 *
 *  1. `req.route.path` — set when the guard is attached to a route, and always
 *     the truth when present. `req.params` is populated by then too.
 *  2. resolved from the app's router — lets an app-wide mount report the same
 *     template *and* the same params a per-route mount would. Express fills
 *     `req.params` only during dispatch, which is after app-level middleware,
 *     so without this the `params` bucket would be empty and every `params.*`
 *     condition would read as a missing field.
 *  3. the concrete request path — correct for a 404, a RegExp route, or an
 *     Express version whose internals we do not recognise.
 */
function resolveRequest(
  req: ExpressLikeRequest,
  app?: ExpressLikeApp,
): { path: string; params: Record<string, unknown> | undefined } {
  // Only a plain-string route is a usable templated path; for RegExp / array
  // routes fall back rather than compose a garbage string.
  const routePath = req.route?.path;
  if (typeof routePath === "string") {
    return { path: (req.baseUrl ?? "") + routePath, params: req.params };
  }

  const concrete = req.path ?? pathWithoutQuery(req.url ?? "");
  if (!app) return { path: concrete, params: req.params };

  // `req.originalUrl` is the pre-mount path; inside a mounted router `req.url`
  // has already been rewritten, and resolution must start from the full path.
  const full = pathWithoutQuery(
    (req as { originalUrl?: string }).originalUrl ?? req.url ?? concrete,
  );
  const resolved = resolveTemplate(app, req.method, full);
  if (!resolved) return { path: concrete, params: req.params };

  // Prefer whatever Express already put on the request; fall back to what the
  // resolver recovered.
  const existing = req.params;
  const hasExisting = existing && Object.keys(existing).length > 0;
  return { path: resolved.template, params: hasExisting ? existing : resolved.params };
}
