import { test } from "node:test";
import assert from "node:assert/strict";

import { resolveTemplate, type ExpressLikeApp } from "../src/adapters/express-routes.js";

/**
 * The resolver is what makes a single app-wide mount viable: it recovers the
 * templated route Express only exposes *after* routing, so `app.use(guard)`
 * reports `/v1/users/:id` rather than `/v1/users/8123`.
 *
 * As with the manifest parser, the value under test is our walk — not Express
 * itself — so these are hand-built stack stubs matching Express v4's shape. The
 * layer `match()` here mirrors Express's: true when the regexp matches.
 */

function routeLayer(path: string, methods: string[]) {
  const rx = new RegExp(
    "^" + path.replace(/:[^/]+/g, "[^/]+").replace(/\//g, "\\/") + "\\/?$",
    "i",
  );
  const names = (path.match(/:([^/]+)/g) ?? []).map((n) => n.slice(1));
  const capture = new RegExp(
    "^" + path.replace(/:[^/]+/g, "([^/]+)").replace(/\//g, "\\/") + "\\/?$",
    "i",
  );
  const layer: {
    route: { path: string; methods: Record<string, boolean> };
    match: (p: string) => boolean;
    params?: Record<string, string>;
  } = {
    route: { path, methods: Object.fromEntries(methods.map((m) => [m, true])) },
    match(p: string) {
      const m = capture.exec(p);
      // Express's own `match()` sets `layer.params` as a side effect.
      layer.params = m ? Object.fromEntries(names.map((n, i) => [n, m[i + 1]!])) : {};
      return rx.test(p);
    },
  };
  return layer;
}

function mountLayer(prefix: string, stack: unknown[]) {
  return {
    handle: { stack },
    regexp: new RegExp("^" + prefix.replace(/\//g, "\\/") + "(?=\\/|$)", "i"),
  };
}

function app(stack: unknown[]): ExpressLikeApp {
  return { _router: { stack } } as ExpressLikeApp;
}

test("resolves a templated route from a concrete path", () => {
  const a = app([routeLayer("/v1/users/:id", ["get"])]);
  assert.equal(resolveTemplate(a, "GET", "/v1/users/8123")?.template, "/v1/users/:id");
});

test("is method-aware — the same path under a different verb does not match", () => {
  const a = app([routeLayer("/v1/users/:id", ["get"])]);
  assert.equal(resolveTemplate(a, "GET", "/v1/users/8123")?.template, "/v1/users/:id");
  assert.equal(resolveTemplate(a, "POST", "/v1/users/8123"), null);
});

test("keeps the mount prefix for a nested router", () => {
  // This is the case a naive `router.use(guard)` gets wrong: it drops /v1/payments.
  const a = app([
    mountLayer("/v1/payments", [routeLayer("/:id/refund", ["post"])]),
  ]);
  assert.equal(
    resolveTemplate(a, "POST", "/v1/payments/42/refund")?.template,
    "/v1/payments/:id/refund",
  );
});

test("returns null for an unmatched path, so the caller keeps the concrete one", () => {
  const a = app([routeLayer("/v1/users/:id", ["get"])]);
  assert.equal(resolveTemplate(a, "GET", "/nope"), null);
});

test("skips a RegExp route rather than inventing a template", () => {
  const a = app([{ route: { path: /.*/, methods: { get: true } }, match: () => true }]);
  assert.equal(resolveTemplate(a, "GET", "/anything"), null);
});

test("takes the first matching route, mirroring Express's own dispatch order", () => {
  const a = app([
    routeLayer("/v1/users/:id", ["get"]),
    routeLayer("/v1/users/me", ["get"]),
  ]);
  assert.equal(resolveTemplate(a, "GET", "/v1/users/me")?.template, "/v1/users/:id");
});

test("degrades to null on an unrecognised router shape instead of throwing", () => {
  assert.equal(resolveTemplate({} as ExpressLikeApp, "GET", "/x"), null);
  assert.equal(
    resolveTemplate({ _router: { stack: [{}] } } as ExpressLikeApp, "GET", "/x"),
    null,
  );
});

test("survives a cyclic mount graph rather than hanging the request", () => {
  const stack: unknown[] = [];
  const layer = mountLayer("/a", stack);
  stack.push(layer); // self-referential
  assert.equal(resolveTemplate(app([layer]), "GET", "/a/b"), null);
});

test("recovers path params, which Express only fills in during dispatch", () => {
  const a = app([routeLayer("/v1/users/:id", ["get"])]);
  assert.deepEqual(resolveTemplate(a, "GET", "/v1/users/8123")?.params, { id: "8123" });
});

test("merges params across a mounted router's prefix", () => {
  const a = app([
    mountLayer("/v1/payments", [routeLayer("/:id/refund", ["post"])]),
  ]);
  const r = resolveTemplate(a, "POST", "/v1/payments/42/refund");
  assert.equal(r?.template, "/v1/payments/:id/refund");
  assert.deepEqual(r?.params, { id: "42" });
});

test("templatises a parametric mount rather than baking in the tenant value", () => {
  // `app.use("/orgs/:orgId", router)` — the regexp matches `/orgs/acme`, and
  // reporting that verbatim would give a path no rule could target.
  const inner = routeLayer("/members/:userId", ["get"]);
  const mount = {
    handle: { stack: [inner] },
    regexp: /^\/orgs\/([^/]+?)(?=\/|$)/i,
    keys: [{ name: "orgId" }],
    match(p: string) {
      const m = /^\/orgs\/([^/]+?)(?=\/|$)/i.exec(p);
      (mount as { params?: Record<string, string> }).params = m ? { orgId: m[1]! } : {};
      return Boolean(m);
    },
  };
  const r = resolveTemplate(app([mount]), "GET", "/orgs/acme/members/42");
  assert.equal(r?.template, "/orgs/:orgId/members/:userId");
  assert.deepEqual(r?.params, { orgId: "acme", userId: "42" });
});
