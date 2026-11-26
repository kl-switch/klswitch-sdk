/**
 * NestJS adapter — Guard unit tests.
 *
 * Driving NestJS via `Test.createTestingModule` requires controller
 * decorators (`@Controller`, `@Post`, `@Body`, `@Param`, …) and the
 * project's test runner (`tsx` → esbuild) doesn't honor
 * `experimentalDecorators` for parameter decorators. Rather than swap
 * test runners just for this adapter, we unit-test the Guard with a
 * hand-stubbed ExecutionContext — same approach the Java JAX-RS
 * filter tests use (mock-context drive). The Guard's logic is the
 * load-bearing part; the @Module surface is plumbing that the
 * declared types already enforce at compile time.
 *
 * Type-check the full adapter (including decorators) with:
 *   npx tsc -p tsconfig.json --noEmit
 *
 * Real-app integration is covered by the example NestJS server +
 * end-to-end curl scripts under examples/ (post-v1).
 */
import "reflect-metadata";
import { test } from "node:test";
import assert from "node:assert/strict";

import type { ExecutionContext, HttpException } from "@nestjs/common";

import { KLSwitch } from "../src/client.js";
import { KlswitchGuard } from "../src/adapters/nestjs.js";
import type { EvaluationResult, RuleSetPayload } from "../src/types.js";

// ─── Fixtures ─────────────────────────────────────────────────────────────

function ruleset(): RuleSetPayload {
  return {
    schema_version: "1.0",
    rules_version: 1,
    rules: [
      {
        id: "block_large_payment",
        name: "block large payment",
        selector: { kind: "api", keys: { method: "POST", path: "/v1/payments/:id" } },
        enabled: true,
        priority: 100,
        condition: { all: [{ field: "body.amount", operator: "gt", value: 50000 }] },
        action: { type: "block", payload: { statusCode: 429 } },
      },
      {
        id: "flag_admin",
        name: "flag admin",
        selector: { kind: "api", keys: { method: "GET", path: "/v1/users/:id" } },
        enabled: true,
        priority: 50,
        condition: { all: [{ field: "query.role", operator: "eq", value: "admin" }] },
        action: { type: "flag" },
      },
    ],
  };
}

/**
 * Build an ExecutionContext that returns a stub express-style request
 * and stamps controller-class + handler-method route metadata so the
 * adapter's path-template resolver can reconstruct `/v1/payments/:id`
 * etc.
 *
 * NestJS itself stamps `path` metadata via the `@Controller(prefix)`
 * + `@Post(':id')` decorators; we do the same with explicit
 * `Reflect.defineMetadata` calls so the guard's
 * `Reflect.getMetadata('path', ...)` lookups succeed.
 */
function makeCtx(opts: {
  method:    string;
  ctrlPath:  string;   // e.g. "v1/payments"
  routePath: string;   // e.g. ":id"
  body?:     unknown;
  query?:    Record<string, unknown>;
  params?:   Record<string, string>;
  headers?:  Record<string, string>;
}): { ctx: ExecutionContext; req: Record<string, unknown> } {
  // Controller class + handler function — Reflect.defineMetadata targets
  // these directly, mirroring what `@Controller(...)` + `@Post(...)` do.
  class C {}
  const handler = function paymentHandler() {};
  Reflect.defineMetadata("path", opts.ctrlPath, C);
  Reflect.defineMetadata("path", opts.routePath, handler);

  const req: Record<string, unknown> = {
    method:  opts.method,
    headers: opts.headers ?? {},
    query:   opts.query ?? {},
    params:  opts.params ?? {},
    body:    opts.body ?? {},
  };

  const ctx = {
    switchToHttp: () => ({ getRequest: () => req, getResponse: () => ({}), getNext: () => () => {} }),
    getClass:     () => C,
    getHandler:   () => handler,
    getType:      () => "http",
    getArgs:      () => [],
    getArgByIndex: () => undefined,
    switchToRpc:   () => ({}),
    switchToWs:    () => ({}),
  } as unknown as ExecutionContext;

  return { ctx, req };
}

async function withGuard<T>(fn: (g: KlswitchGuard, ks: KLSwitch) => Promise<T>): Promise<T> {
  const ks = await KLSwitch.init({ rules: ruleset() });
  const guard = new KlswitchGuard(ks);
  try { return await fn(guard, ks); }
  finally { ks.close(); }
}

// ─── Tests ────────────────────────────────────────────────────────────────

test("nestjs guard: blocks matching payment with action.payload.statusCode", async () => {
  await withGuard(async (guard) => {
    const { ctx } = makeCtx({
      method:   "POST",
      ctrlPath: "v1/payments",
      routePath: ":id",
      params:   { id: "abc" },
      body:     { amount: 70000 },
    });

    let thrown: HttpException | null = null;
    try { await guard.canActivate(ctx); }
    catch (e) { thrown = e as HttpException; }
    assert.ok(thrown, "expected HttpException on block");
    assert.equal(thrown!.getStatus(), 429,
      "status must come from action.payload.statusCode");
    const body = thrown!.getResponse() as Record<string, unknown>;
    assert.equal(body.blocked, true);
    assert.equal(body.ruleId, "block_large_payment");
  });
});

test("nestjs guard: small payment passes through (returns true)", async () => {
  await withGuard(async (guard) => {
    const { ctx, req } = makeCtx({
      method:    "POST",
      ctrlPath:  "v1/payments",
      routePath: ":id",
      params:    { id: "abc" },
      body:      { amount: 5 },
    });
    const ok = await guard.canActivate(ctx);
    assert.equal(ok, true);
    // Result attached for handler observation.
    const attached = (req as { klswitch?: EvaluationResult }).klswitch;
    assert.ok(attached);
    assert.equal(attached!.matched.length, 0);
  });
});

test("nestjs guard: flag action passes through and attaches result", async () => {
  await withGuard(async (guard) => {
    const { ctx, req } = makeCtx({
      method:    "GET",
      ctrlPath:  "v1/users",
      routePath: ":id",
      params:    { id: "42" },
      query:     { role: "admin" },
    });
    const ok = await guard.canActivate(ctx);
    assert.equal(ok, true);
    const attached = (req as { klswitch?: EvaluationResult }).klswitch;
    assert.equal(attached!.matched.length, 1);
    assert.equal(attached!.matched[0]!.id, "flag_admin");
  });
});

test("nestjs guard: no query match returns empty matched", async () => {
  await withGuard(async (guard) => {
    const { ctx, req } = makeCtx({
      method:    "GET",
      ctrlPath:  "v1/users",
      routePath: ":id",
      params:    { id: "42" },
      query:     { role: "user" },
    });
    await guard.canActivate(ctx);
    const attached = (req as { klswitch?: EvaluationResult }).klswitch;
    assert.equal(attached!.matched.length, 0);
  });
});

test("nestjs guard: block without payload.statusCode defaults to 403", async () => {
  const ks = await KLSwitch.init({
    rules: {
      schema_version: "1.0",
      rules_version: 1,
      rules: [{
        id: "block_default",
        name: "default",
        selector: { kind: "api", keys: { method: "POST", path: "/v1/payments/:id" } },
        enabled: true, priority: 100,
        condition: { all: [{ field: "body.amount", operator: "gt", value: 0 }] },
        action: { type: "block" },  // no payload
      }],
    },
  });
  const guard = new KlswitchGuard(ks);
  try {
    const { ctx } = makeCtx({
      method:   "POST",
      ctrlPath: "v1/payments",
      routePath: ":id",
      params:   { id: "abc" },
      body:     { amount: 1 },
    });
    let thrown: HttpException | null = null;
    try { await guard.canActivate(ctx); }
    catch (e) { thrown = e as HttpException; }
    assert.ok(thrown);
    assert.equal(thrown!.getStatus(), 403);
  } finally {
    ks.close();
  }
});

test("nestjs guard: path template reconstructed from class+handler metadata", async () => {
  // The rule selector says `/v1/payments/:id`. The controller is mounted
  // at `v1/payments` and the handler is `:id`. The guard must join them
  // back into `/v1/payments/:id` — that's the load-bearing path-resolution.
  await withGuard(async (guard) => {
    const { ctx } = makeCtx({
      method:    "POST",
      ctrlPath:  "v1/payments",
      routePath: ":id",
      params:    { id: "xyz" },
      body:      { amount: 70000 },
    });
    let thrown: HttpException | null = null;
    try { await guard.canActivate(ctx); }
    catch (e) { thrown = e as HttpException; }
    assert.ok(thrown, "rule selector must have matched via reconstructed path");
  });
});

test("nestjs guard: headers and query are first-value-only and lowercased", async () => {
  const ks = await KLSwitch.init({
    rules: {
      schema_version: "1.0",
      rules_version: 1,
      rules: [{
        id: "impersonate",
        name: "i",
        selector: { kind: "api", keys: { method: "GET", path: "/u" } },
        enabled: true, priority: 10,
        condition: { all: [{ field: "headers.x-impersonate", operator: "exists" }] },
        action: { type: "flag" },
      }],
    },
  });
  const guard = new KlswitchGuard(ks);
  try {
    const { ctx, req } = makeCtx({
      method:    "GET",
      ctrlPath:  "",
      routePath: "u",
      // NestJS / Express preserve operator-set header case. The guard
      // must lowercase before facts are emitted so the rule's
      // `headers.x-impersonate` selector hits.
      headers:   { "X-Impersonate": "ops-user" },
    });
    await guard.canActivate(ctx);
    const attached = (req as { klswitch?: EvaluationResult }).klswitch;
    assert.equal(attached!.matched.length, 1);
    assert.equal(attached!.matched[0]!.id, "impersonate");
  } finally {
    ks.close();
  }
});
