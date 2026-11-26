import type { KLSwitch } from "../client.js";
import { pathWithoutQuery, toEvaluationInput } from "../request-context.js";
import type { EvaluationResult, MatchedRule } from "../types.js";

/**
 * Minimal structural shape of a Fastify request — just what the adapter reads.
 * Declared here so the SDK needs no dependency on Fastify or its types.
 */
export interface FastifyLikeRequest {
  method: string;
  url: string;
  /** Templated route in Fastify v4+ (e.g. `/v1/payments/:id`). */
  routeOptions?: { url?: string };
  /** Templated route in Fastify v3. */
  routerPath?: string;
  headers: Record<string, unknown>;
  query?: unknown;
  params?: unknown;
  body?: unknown;
  /** The adapter attaches the result here by default. */
  klswitch?: EvaluationResult;
}

/** Minimal structural shape of a Fastify reply — just what the adapter writes. */
export interface FastifyLikeReply {
  code: (status: number) => FastifyLikeReply;
  send: (body: unknown) => unknown;
}

export interface FastifyAdapterOptions<Reply = unknown> {
  /**
   * Short-circuit a matched `block` action with its `statusCode` (default 403)
   * and the canonical JSON body. Defaults to **true**, matching the Express, Go,
   * Java and NestJS adapters.
   *
   * Set `false` to observe without enforcing — the result is still attached to
   * `request.klswitch` and handling continues.
   */
  autoBlock?: boolean;

  /**
   * Take full control of the decision, replacing `autoBlock`. Send a reply to
   * short-circuit, or return without sending to let the request continue.
   */
  onResult?: (result: EvaluationResult, request: FastifyLikeRequest, reply: Reply) => void | Promise<void>;
}

/**
 * The canonical block response, identical across the Node, Go and Java adapters
 * so a blocked request looks the same whichever SDK served it.
 */
function replyBlocked(reply: FastifyLikeReply, matched: MatchedRule): unknown {
  const payload = matched.action?.payload as Record<string, unknown> | undefined;
  const raw = payload?.statusCode;
  const status = typeof raw === "number" ? raw : 403;
  return reply.code(status).send({
    blocked: true,
    ruleId: matched.id,
    action: matched.action,
  });
}

/**
 * A Fastify hook that evaluates every request and enforces `block` actions.
 *
 * Register it once as a `preHandler` and never touch a route again:
 *
 * ```ts
 * app.addHook("preHandler", klswitchHook(ks));
 * ```
 *
 * Use `preHandler` rather than `onRequest`: the body is parsed by then, so
 * conditions reading `body.*` work. Register it inside a plugin to scope it to
 * that plugin's routes.
 *
 * Fastify supplies the templated route itself (`routeOptions.url` on v4+,
 * `routerPath` on v3), so unlike Express no app reference is needed.
 *
 * A matched `block` short-circuits with the action's `statusCode` (default 403)
 * and the canonical body — the same behaviour as the Express, Go, Java and
 * NestJS adapters. Pass `autoBlock: false` to observe without enforcing, or
 * `onResult` to take over. The result is always attached to `request.klswitch`.
 */
export function klswitchHook<Reply = unknown>(
  ks: KLSwitch,
  options: FastifyAdapterOptions<Reply> = {},
) {
  let warnedDoubleMount = false;

  return async (request: FastifyLikeRequest, reply: Reply): Promise<unknown> => {
    // Already evaluated on this request — the hook is registered twice (e.g. on
    // the root instance and again inside a plugin). A second evaluation costs
    // latency and emits a duplicate analytics event, quietly inflating decision
    // counts. Skip; the first result stands.
    if (request.klswitch !== undefined) {
      if (!warnedDoubleMount) {
        warnedDoubleMount = true;
        ks.log.warn(
          "klswitch hook is registered more than once on this request; the extra " +
            "evaluation is skipped. Register it on the root instance or in the plugin, not both.",
        );
      }
      return undefined;
    }

    const path =
      request.routeOptions?.url ?? request.routerPath ?? pathWithoutQuery(request.url);
    const result = ks.evaluate(
      toEvaluationInput({
        method: request.method,
        path,
        headers: request.headers,
        query: request.query as Record<string, unknown> | undefined,
        params: request.params as Record<string, unknown> | undefined,
        body: request.body,
      }),
    );

    request.klswitch = result;

    if (options.onResult) {
      await options.onResult(result, request, reply);
      return undefined;
    }

    if (options.autoBlock !== false) {
      const blocked = result.matched.find((m) => m.action?.type === "block");
      if (blocked) {
        // Returning the reply is how an async Fastify hook signals "response
        // handled" — the route handler never runs.
        return replyBlocked(reply as unknown as FastifyLikeReply, blocked);
      }
    }

    return undefined;
  };
}
