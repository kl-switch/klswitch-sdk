/**
 * NestJS adapter — `KlswitchModule` + `KlswitchGuard`.
 *
 * Drop the module into your `AppModule.imports[]`, then register the
 * guard either globally (via `APP_GUARD`), per-controller (via
 * `@UseGuards(KlswitchGuard)`), or per-handler. The guard reads the
 * request via NestJS's `ExecutionContext`, evaluates against the
 * KLSwitch engine, and either throws `HttpException` with the
 * action's `statusCode` (block) or attaches the result to
 * `request.klswitch` and lets the handler run.
 *
 * Usage — synchronous Client:
 *
 *   import { KLSwitch } from '@klswitch/sdk-node';
 *   import { KlswitchModule, KlswitchGuard } from '@klswitch/sdk-node/adapters/nestjs';
 *
 *   const client = await KLSwitch.init({ token: process.env.KS_TOKEN! });
 *
 *   @Module({
 *     imports: [KlswitchModule.forRoot({ client })],
 *     providers: [{ provide: APP_GUARD, useClass: KlswitchGuard }],
 *   })
 *   export class AppModule {}
 *
 * Usage — async (factory pulled from ConfigService):
 *
 *   @Module({
 *     imports: [KlswitchModule.forRootAsync({
 *       inject: [ConfigService],
 *       useFactory: (cfg: ConfigService) =>
 *         KLSwitch.init({ token: cfg.get('KS_TOKEN') }),
 *     })],
 *   })
 *
 * The adapter is platform-agnostic — works against `@nestjs/platform-express`
 * and `@nestjs/platform-fastify`. The request shape it reads (`method`,
 * `originalUrl`/`url`, `headers`, `query`, `params`, `body`) is what both
 * platforms expose by default.
 *
 * Path templates: NestJS doesn't expose the matched route on
 * `ExecutionContext` directly, but it does set `Reflector` metadata on
 * the handler. We read `Reflect.getMetadata('path', handler)` +
 * `Reflect.getMetadata('path', controllerClass)` to reconstruct the
 * declared template — e.g. controller `@Controller('payments')` +
 * handler `@Post(':id')` → `/payments/:id`. The `:id`-style is the
 * cross-language convention; NestJS uses the same.
 *
 * IMPORTANT: this module is loaded only when the operator imports it.
 * The base `@klswitch/sdk-node` package doesn't pull NestJS — both
 * `@nestjs/common` and `reflect-metadata` are peer dependencies. The
 * `import` statements in this file resolve only when an operator wires
 * the adapter into their NestJS app, which already has those deps.
 */

import type {
  CanActivate,
  DynamicModule,
  ExecutionContext,
  ModuleMetadata,
  Provider,
  Type,
} from "@nestjs/common";
import {
  HttpException,
  HttpStatus,
  Inject,
  Injectable,
  Module,
} from "@nestjs/common";

import type { KLSwitch } from "../client.js";
import type { EvaluationInput, EvaluationResult } from "../types.js";

/** DI token for the shared KLSwitch client. */
export const KS_CLIENT = Symbol("KS_CLIENT");

/** What an operator passes to [[KlswitchModule.forRoot]]. */
export interface KlswitchModuleOptions {
  client: KLSwitch;
}

/** Async-flavoured options for [[KlswitchModule.forRootAsync]]. */
export interface KlswitchModuleAsyncOptions
  extends Pick<ModuleMetadata, "imports"> {
  useFactory: (...args: unknown[]) => Promise<KLSwitch> | KLSwitch;
  inject?: unknown[];
}

/**
 * The NestJS module — `forRoot` for sync wiring, `forRootAsync` for
 * factory-based (e.g. ConfigService-driven) wiring.
 */
@Module({})
export class KlswitchModule {
  static forRoot(opts: KlswitchModuleOptions): DynamicModule {
    const provider: Provider = { provide: KS_CLIENT, useValue: opts.client };
    return {
      module: KlswitchModule,
      providers: [provider, KlswitchGuard],
      exports: [provider, KlswitchGuard],
    };
  }

  static forRootAsync(opts: KlswitchModuleAsyncOptions): DynamicModule {
    const provider: Provider = {
      provide:    KS_CLIENT,
      useFactory: opts.useFactory,
      inject:     (opts.inject as Type<unknown>[]) ?? [],
    };
    return {
      module:    KlswitchModule,
      imports:   opts.imports ?? [],
      providers: [provider, KlswitchGuard],
      exports:   [provider, KlswitchGuard],
    };
  }
}

/**
 * The Guard — call `client.evaluate` on every guarded request. Throws
 * an HttpException on `block`-action matches (with the action's
 * `statusCode`, default 403); attaches the EvaluationResult to
 * `request.klswitch` and returns true otherwise.
 */
@Injectable()
export class KlswitchGuard implements CanActivate {
  constructor(@Inject(KS_CLIENT) private readonly ks: KLSwitch) {}

  async canActivate(ctx: ExecutionContext): Promise<boolean> {
    const http = ctx.switchToHttp();
    const req: Record<string, unknown> = http.getRequest();
    const input = buildEvaluationInput(req, ctx);
    const result: EvaluationResult = await this.ks.evaluate(input);
    // Attach for the handler — same convention as Flask's `flask.g.klswitch`
    // and the Go middleware's request context.
    (req as { klswitch?: EvaluationResult }).klswitch = result;
    for (const m of result.matched) {
      if (m.action.type === "block") {
        const status = pickStatusCode(m.action.payload, HttpStatus.FORBIDDEN);
        throw new HttpException(
          { blocked: true, ruleId: m.id, action: m.action },
          status,
        );
      }
    }
    return true;
  }
}

// ─── internals ─────────────────────────────────────────────────────────────

function buildEvaluationInput(
  req: Record<string, unknown>,
  ctx: ExecutionContext,
): EvaluationInput {
  const method = String(req["method"] ?? "GET").toUpperCase();
  const path = resolvePathTemplate(ctx, req);
  const headers = normalizeHeaders(
    (req["headers"] as Record<string, unknown>) ?? {},
  );
  const rawQuery = (req["query"] as Record<string, unknown>) ?? {};
  const query = firstValuesOnly(rawQuery);
  const params = (req["params"] as Record<string, unknown>) ?? {};
  const body = pickJsonBody(req);

  return {
    selector: { kind: "api", keys: { method, path } },
    facts:    { headers, query, params, body, meta: {} },
  };
}

/**
 * Reconstruct the declared template from the controller + handler
 * metadata that NestJS stamps via `@Controller(prefix)` + the
 * route-decorator (`@Get`, `@Post`, etc.). Falls back to the request
 * URL if metadata is missing (e.g. when the guard runs outside the
 * normal NestJS routing pipeline).
 */
function resolvePathTemplate(
  ctx: ExecutionContext,
  req: Record<string, unknown>,
): string {
  // `Reflect.getMetadata` is provided by `reflect-metadata` (NestJS
  // requires it). Wrap in a typeof check so importing this module
  // doesn't crash in environments without it.
  const R = (globalThis as { Reflect?: { getMetadata?: (k: string, t: unknown) => unknown } }).Reflect;
  if (R?.getMetadata) {
    const handler = ctx.getHandler();
    const cls     = ctx.getClass();
    const ctrlPath = R.getMetadata("path", cls) as string | string[] | undefined;
    const methodPath = R.getMetadata("path", handler) as string | string[] | undefined;
    const prefix = normalizePathPart(Array.isArray(ctrlPath) ? ctrlPath[0] : ctrlPath);
    const suffix = normalizePathPart(Array.isArray(methodPath) ? methodPath[0] : methodPath);
    if (prefix || suffix) {
      const joined = `/${[prefix, suffix].filter(Boolean).join("/")}`.replace(/\/+/g, "/");
      return joined === "" ? "/" : joined;
    }
  }
  // Fallback: concrete URL minus the query string.
  const raw = String(req["originalUrl"] ?? req["url"] ?? "/");
  const qIdx = raw.indexOf("?");
  return qIdx >= 0 ? raw.slice(0, qIdx) : raw;
}

function normalizePathPart(p: string | undefined): string {
  if (!p) return "";
  let s = p.trim().replace(/^\/+|\/+$/g, "");
  // NestJS supports both `:id` and `(.*)` style — leave as-is. The cross-
  // language convention is `:id` which is already what NestJS uses.
  return s;
}

function normalizeHeaders(in_: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(in_)) {
    const lowered = k.toLowerCase();
    // First value wins on repeats — matches the cross-language contract.
    if (Array.isArray(v)) {
      out[lowered] = v.length > 0 ? v[0] : null;
    } else {
      out[lowered] = v;
    }
  }
  return out;
}

function firstValuesOnly(q: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(q)) {
    out[k] = Array.isArray(v) ? (v.length > 0 ? v[0] : null) : v;
  }
  return out;
}

function pickJsonBody(req: Record<string, unknown>): Record<string, unknown> {
  // Both Express and Fastify populate `req.body` to a parsed object
  // when the operator wired a JSON body parser (which is the default
  // for both modern setups). Non-dict bodies → {} (engine treats
  // missing fields as leaf-false).
  const b = req["body"];
  if (b && typeof b === "object" && !Array.isArray(b)) {
    return b as Record<string, unknown>;
  }
  return {};
}

function pickStatusCode(
  payload: Record<string, unknown> | undefined,
  fallback: number,
): number {
  if (!payload) return fallback;
  const sc = payload["statusCode"];
  if (typeof sc === "number" && Number.isFinite(sc)) return sc;
  return fallback;
}
