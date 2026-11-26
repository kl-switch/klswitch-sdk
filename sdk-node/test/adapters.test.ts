import { test } from "node:test";
import assert from "node:assert/strict";

import { klswitchMiddleware, type ExpressLikeRequest } from "../src/adapters/express.js";
import { klswitchHook, type FastifyLikeRequest } from "../src/adapters/fastify.js";
import { KLSwitch } from "../src/client.js";
import { pathWithoutQuery, toEvaluationInput } from "../src/request-context.js";
import type { RuleSetPayload } from "../src/types.js";

function ruleOnPath(path: string): RuleSetPayload {
  return {
    schema_version: "1.0",
    rules_version: 1,
    rules: [
      {
        id: "r",
        name: "R",
        selector: { kind: "api", keys: { method: "POST", path } },
        enabled: true,
        priority: 100,
        condition: { all: [{ field: "body.amount", operator: "gt", value: 100 }] },
        action: { type: "block" },
      },
    ],
  };
}

// --- request-context ---

test("toEvaluationInput: uppercases method and fills all five fact buckets", () => {
  const input = toEvaluationInput({ method: "post", path: "/p", body: { a: 1 } });
  assert.equal(input.selector.keys.method, "POST");
  assert.equal(input.selector.keys.path, "/p");
  assert.deepEqual(input.facts.body, { a: 1 });
  assert.deepEqual(input.facts.headers, {});
  assert.deepEqual(input.facts.query, {});
  assert.deepEqual(input.facts.params, {});
  assert.deepEqual(input.facts.meta, {});
});

test("toEvaluationInput: a non-object body becomes {}", () => {
  assert.deepEqual(toEvaluationInput({ method: "GET", path: "/p", body: "nope" }).facts.body, {});
  assert.deepEqual(toEvaluationInput({ method: "GET", path: "/p", body: [1, 2] }).facts.body, {});
});

test("pathWithoutQuery strips query and fragment", () => {
  assert.equal(pathWithoutQuery("/p?x=1"), "/p");
  assert.equal(pathWithoutQuery("/p#frag"), "/p");
  assert.equal(pathWithoutQuery("/p"), "/p");
});

// --- cross-language parity gates (sdk-wrapper-contract.md §request-translation) ---

test("toEvaluationInput: header keys are lowercased (parity with Java/Go/Python)", () => {
  const input = toEvaluationInput({
    method: "GET",
    path: "/p",
    headers: { "X-User-Id": "u1", "Content-Type": "application/json", lowercase: "ok" },
  });
  assert.deepEqual(input.facts.headers, {
    "x-user-id": "u1",
    "content-type": "application/json",
    lowercase: "ok",
  });
});

test("toEvaluationInput: repeated query keys collapse to the FIRST value (parity)", () => {
  // Express's qs parser produces arrays for repeats; the SDK must collapse.
  const input = toEvaluationInput({
    method: "GET",
    path: "/p",
    query: { role: ["admin", "user"], q: "search" },
  });
  assert.deepEqual(input.facts.query, { role: "admin", q: "search" });
});

test("toEvaluationInput: empty query array becomes undefined (no spurious facts)", () => {
  const input = toEvaluationInput({ method: "GET", path: "/p", query: { stripped: [] } });
  // The field is present but with `undefined`; the engine treats absent + undefined
  // identically (missing-field → false for comparison operators).
  assert.equal((input.facts.query as Record<string, unknown>).stripped, undefined);
});


/** Records what the adapter wrote, so block enforcement is observable in tests. */
function resStub() {
  const sent: { status?: number; body?: unknown } = {};
  const res = {
    status(code: number) { sent.status = code; return res; },
    json(body: unknown) { sent.body = body; return res; },
  };
  return { res, sent };
}

// --- Express adapter ---

test("express: evaluates (templated route + uppercased method) and enforces block", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/p") });
  const mw = klswitchMiddleware(ks);
  const req = { method: "post", route: { path: "/p" }, headers: {}, body: { amount: 500 } } as ExpressLikeRequest;
  const { res, sent } = resStub();
  let nexted = false;
  mw(req, res, (err) => { if (err) throw err; nexted = true; });
  assert.equal(req.klswitch?.matched.length, 1);
  assert.equal(nexted, false, "a matched block short-circuits — the handler never runs");
  assert.equal(sent.status, 403, "default block status");
  assert.deepEqual(sent.body, {
    blocked: true, ruleId: "r", action: { type: "block" },
  }, "canonical body — identical across Node, Go and Java");
  ks.close();
});

test("express: autoBlock:false observes without enforcing", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/p") });
  const mw = klswitchMiddleware(ks, { autoBlock: false });
  const req = { method: "POST", route: { path: "/p" }, headers: {}, body: { amount: 500 } } as ExpressLikeRequest;
  const { res, sent } = resStub();
  let nexted = false;
  mw(req, res, () => { nexted = true; });
  assert.equal(req.klswitch?.matched.length, 1, "still evaluated and attached");
  assert.equal(nexted, true, "request continues");
  assert.equal(sent.status, undefined, "nothing written");
  ks.close();
});

test("express: a non-block action never short-circuits", async () => {
  const rules = ruleOnPath("/p");
  rules.rules[0].action = { type: "flag" };
  const ks = await KLSwitch.init({ rules });
  const mw = klswitchMiddleware(ks);
  const req = { method: "POST", route: { path: "/p" }, headers: {}, body: { amount: 500 } } as ExpressLikeRequest;
  const { res, sent } = resStub();
  let nexted = false;
  mw(req, res, () => { nexted = true; });
  assert.equal(req.klswitch?.matched.length, 1);
  assert.equal(nexted, true, "only `block` is enforced");
  assert.equal(sent.status, undefined);
  ks.close();
});

test("express: onResult takes over the decision, replacing autoBlock", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/p") });
  let matched = -1;
  const mw = klswitchMiddleware(ks, {
    onResult: (result, _req, _res, next) => {
      matched = result.matched.length;
      next();
    },
  });
  const req = { method: "POST", route: { path: "/p" }, headers: {}, body: { amount: 500 } } as ExpressLikeRequest;
  mw(req, resStub().res, () => {});
  assert.equal(matched, 1);
  assert.equal(req.klswitch?.matched.length, 1,
    "the result is attached even when onResult owns the decision");
  ks.close();
});

test("express: an evaluate error is routed to next(err)", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/p") });
  ks.close(); // evaluate now throws
  const mw = klswitchMiddleware(ks);
  let caught: unknown = null;
  mw({ method: "POST", route: { path: "/p" }, headers: {}, body: {} } as ExpressLikeRequest, {}, (err) => {
    caught = err;
  });
  assert.ok(caught instanceof Error);
});

// --- Fastify adapter ---

/** Records what the hook sent, so block enforcement is observable. */
function replyStub() {
  const sent: { status?: number; body?: unknown } = {};
  const reply = {
    code(c: number) { sent.status = c; return reply; },
    send(b: unknown) { sent.body = b; return reply; },
  };
  return { reply, sent };
}

test("fastify: evaluates using the templated routeOptions.url and enforces block", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/p") });
  const hook = klswitchHook(ks);
  const request = {
    method: "POST",
    url: "/p?x=1",
    routeOptions: { url: "/p" },
    headers: {},
    body: { amount: 500 },
  } as FastifyLikeRequest;
  const { reply, sent } = replyStub();
  await hook(request, reply);
  assert.equal(request.klswitch?.matched.length, 1);
  assert.equal(sent.status, 403, "a matched block short-circuits");
  assert.deepEqual(sent.body, { blocked: true, ruleId: "r", action: { type: "block" } },
    "canonical body — identical to Express, Go and Java");
  ks.close();
});

test("fastify: autoBlock:false observes without enforcing", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/p") });
  const hook = klswitchHook(ks, { autoBlock: false });
  const request = {
    method: "POST", url: "/p", routeOptions: { url: "/p" }, headers: {}, body: { amount: 500 },
  } as FastifyLikeRequest;
  const { reply, sent } = replyStub();
  await hook(request, reply);
  assert.equal(request.klswitch?.matched.length, 1, "still evaluated and attached");
  assert.equal(sent.status, undefined, "nothing sent — the handler runs");
  ks.close();
});

test("fastify: falls back to url (query stripped) when no templated route", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/p") });
  const hook = klswitchHook(ks);
  const request = { method: "POST", url: "/p?x=1", headers: {}, body: { amount: 500 } } as FastifyLikeRequest;
  await hook(request, replyStub().reply);
  assert.equal(request.klswitch?.matched.length, 1, "stripped url path matches /p");
  ks.close();
});

test("fastify: uses routerPath (Fastify v3) when routeOptions is absent", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/p") });
  const hook = klswitchHook(ks);
  const request = { method: "POST", url: "/p?x=1", routerPath: "/p", headers: {}, body: { amount: 500 } } as FastifyLikeRequest;
  await hook(request, replyStub().reply);
  assert.equal(request.klswitch?.matched.length, 1);
  ks.close();
});

// --- Express path extraction edge cases ---

test("express: composes baseUrl + templated route.path", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/v1/payments/:id") });
  const mw = klswitchMiddleware(ks);
  const req = { method: "POST", baseUrl: "/v1", route: { path: "/payments/:id" }, headers: {}, body: { amount: 500 } } as ExpressLikeRequest;
  mw(req, resStub().res, () => {});
  assert.equal(req.klswitch?.matched.length, 1, "baseUrl + route.path = /v1/payments/:id");
  ks.close();
});

test("express: a RegExp route.path falls back to the concrete path (no garbage match)", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/v1/payments/123") });
  const mw = klswitchMiddleware(ks);
  // Express would set route.path to a RegExp here; the adapter must not compose it.
  const req = { method: "POST", route: { path: /.*/ }, path: "/v1/payments/123", headers: {}, body: { amount: 500 } } as ExpressLikeRequest;
  mw(req, resStub().res, () => {});
  assert.equal(req.klswitch?.matched.length, 1, "used the concrete path, not the stringified regex");
  ks.close();
});

test("express: app-level mount falls back to req.path when no route matched", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/v1/x") });
  const mw = klswitchMiddleware(ks);
  const req = { method: "POST", path: "/v1/x", headers: {}, body: { amount: 500 } } as ExpressLikeRequest;
  mw(req, resStub().res, () => {});
  assert.equal(req.klswitch?.matched.length, 1);
  ks.close();
});

// ── double-mount safety ─────────────────────────────────────────────────────
//
// Mounting the guard twice is an easy mistake — app-wide *and* per-route, or a
// half-finished migration to the app-wide shape. A second evaluation would cost
// latency and, worse, emit a duplicate analytics event: decision counts would
// quietly read double.

test("express: a second mount on the same request does not re-evaluate", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/p") });
  let calls = 0;
  const orig = ks.evaluate.bind(ks);
  (ks as unknown as { evaluate: typeof ks.evaluate }).evaluate = (i) => { calls++; return orig(i); };

  const mw = klswitchMiddleware(ks, { autoBlock: false });
  const req = { method: "POST", route: { path: "/p" }, headers: {}, body: { amount: 500 } } as ExpressLikeRequest;
  const { res } = resStub();
  mw(req, res, () => {});   // first mount
  mw(req, res, () => {});   // second mount, same request

  assert.equal(calls, 1, "the second mount must reuse the first result");
  assert.equal(req.klswitch?.matched.length, 1, "and the result is still attached");
  ks.close();
});

test("express: a second mount still calls next() so the request continues", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/p") });
  const mw = klswitchMiddleware(ks, { autoBlock: false });
  const req = { method: "POST", route: { path: "/p" }, headers: {}, body: { amount: 500 } } as ExpressLikeRequest;
  const { res } = resStub();
  mw(req, res, () => {});
  let nexted = false;
  mw(req, res, () => { nexted = true; });
  assert.equal(nexted, true, "skipping evaluation must not hang the request");
  ks.close();
});

test("fastify: a second hook on the same request does not re-evaluate", async () => {
  const ks = await KLSwitch.init({ rules: ruleOnPath("/p") });
  let calls = 0;
  const orig = ks.evaluate.bind(ks);
  (ks as unknown as { evaluate: typeof ks.evaluate }).evaluate = (i) => { calls++; return orig(i); };

  const hook = klswitchHook(ks, { autoBlock: false });
  const request = {
    method: "POST", url: "/p", routeOptions: { url: "/p" }, headers: {}, body: { amount: 500 },
  } as FastifyLikeRequest;
  await hook(request, replyStub().reply);
  await hook(request, replyStub().reply);

  assert.equal(calls, 1, "the second registration must reuse the first result");
  ks.close();
});

