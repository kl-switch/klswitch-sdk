import { test } from "node:test";
import assert from "node:assert/strict";

import {
  discoverExpressRoutes,
  expressManifestSource,
  extractMountPath,
  joinPaths,
  type ExpressLikeApp,
  type ExpressLikeLayer,
} from "../src/manifest/express.js";

/**
 * The parser is the value here — not Express itself. We feed it
 * hand-crafted stack stubs that match what Express v4 produces, so the
 * SDK doesn't need Express as a dev dep.
 *
 * Real Express stack layers as of v4.x are documented at
 * https://github.com/expressjs/express/blob/4.x/lib/router/layer.js
 * and https://github.com/pillarjs/path-to-regexp/tree/v6 .
 */

function routeLayer(path: string, methods: string[]): ExpressLikeLayer {
  const methodMap: Record<string, boolean> = {};
  for (const m of methods) methodMap[m.toLowerCase()] = true;
  return { route: { path, methods: methodMap } };
}

function routerLayer(mountSource: string, stack: ExpressLikeLayer[]): ExpressLikeLayer {
  // Make a regex that matches what path-to-regexp v6 produces for a
  // mount point — `/^\/api\/?(?=\/|$)/i`.
  const regexp = new RegExp(mountSource, "i") as RegExp & {
    fast_slash?: boolean;
    fast_star?: boolean;
  };
  return {
    name:   "router",
    regexp,
    handle: { stack },
  };
}

test("joinPaths: handles empty prefix, leading slashes, and trailing slashes", () => {
  assert.equal(joinPaths("",      "/v1/payments"), "/v1/payments");
  assert.equal(joinPaths("/",     "/v1/payments"), "/v1/payments");
  assert.equal(joinPaths("/api",  "/v1/payments"), "/api/v1/payments");
  assert.equal(joinPaths("/api/", "/v1/payments"), "/api/v1/payments");
  assert.equal(joinPaths("/api",  "v1/payments"),  "/api/v1/payments");
});

test("extractMountPath: trivial / (fast_slash) → ''", () => {
  const layer: ExpressLikeLayer = {
    regexp: Object.assign(/^\/?$/, { fast_slash: true }) as RegExp & { fast_slash?: boolean },
  };
  assert.equal(extractMountPath(layer), "");
});

test("extractMountPath: simple static mount /api → '/api'", () => {
  // Express v4 produces: /^\/api\/?(?=\/|$)/i
  const layer: ExpressLikeLayer = {
    regexp: /^\/api\/?(?=\/|$)/i as RegExp & { fast_slash?: boolean },
  };
  assert.equal(extractMountPath(layer), "/api");
});

test("extractMountPath: nested mount /api/v1 → '/api/v1'", () => {
  const layer: ExpressLikeLayer = {
    regexp: /^\/api\/v1\/?(?=\/|$)/i as RegExp & { fast_slash?: boolean },
  };
  assert.equal(extractMountPath(layer), "/api/v1");
});

test("extractMountPath: parametric mount returns null (we don't guess)", () => {
  // /^\/(?:([^\/]+?))\/?(?=\/|$)/i  — what app.use('/:tenant', router) produces
  const layer: ExpressLikeLayer = {
    regexp: /^\/(?:([^/]+?))\/?(?=\/|$)/i as RegExp & { fast_slash?: boolean },
  };
  assert.equal(extractMountPath(layer), null);
});

test("discoverExpressRoutes: returns [] when _router.stack is missing", () => {
  assert.deepEqual(discoverExpressRoutes({}), []);
  assert.deepEqual(discoverExpressRoutes({ _router: {} }), []);
});

test("discoverExpressRoutes: emits routes from a flat stack", () => {
  const app: ExpressLikeApp = {
    _router: {
      stack: [
        routeLayer("/healthz",       ["get"]),
        routeLayer("/v1/payments",   ["post"]),
        routeLayer("/v1/orders/:id", ["get", "delete"]),
      ],
    },
  };
  const routes = discoverExpressRoutes(app).sort((a, b) =>
    `${a.method} ${a.path}`.localeCompare(`${b.method} ${b.path}`));
  assert.deepEqual(routes, [
    { method: "DELETE", path: "/v1/orders/:id" },
    { method: "GET",    path: "/healthz" },
    { method: "GET",    path: "/v1/orders/:id" },
    { method: "POST",   path: "/v1/payments" },
  ]);
});

test("discoverExpressRoutes: prepends mount path from nested routers", () => {
  // app.use('/api', router) where router has POST /payments
  const api = routerLayer("^\\/api\\/?(?=\\/|$)", [
    routeLayer("/payments",     ["post"]),
    routeLayer("/orders/:id",   ["get"]),
  ]);
  const app: ExpressLikeApp = { _router: { stack: [api] } };
  const routes = discoverExpressRoutes(app).sort((a, b) =>
    `${a.method} ${a.path}`.localeCompare(`${b.method} ${b.path}`));
  assert.deepEqual(routes, [
    { method: "GET",  path: "/api/orders/:id" },
    { method: "POST", path: "/api/payments" },
  ]);
});

test("discoverExpressRoutes: handles deeply nested routers", () => {
  // app.use('/api', apiRouter)  →  apiRouter.use('/v1', v1Router)  →  v1Router has /payments
  const v1 = routerLayer("^\\/v1\\/?(?=\\/|$)", [
    routeLayer("/payments", ["post"]),
  ]);
  const api = routerLayer("^\\/api\\/?(?=\\/|$)", [v1]);
  const app: ExpressLikeApp = { _router: { stack: [api] } };
  assert.deepEqual(discoverExpressRoutes(app), [
    { method: "POST", path: "/api/v1/payments" },
  ]);
});

test("discoverExpressRoutes: ignores plain middleware (no route, name !== 'router')", () => {
  const app: ExpressLikeApp = {
    _router: {
      stack: [
        // Plain middleware — should be ignored.
        { name: "logger", handle: () => {} },
        routeLayer("/v1/payments", ["post"]),
      ],
    },
  };
  assert.deepEqual(discoverExpressRoutes(app), [
    { method: "POST", path: "/v1/payments" },
  ]);
});

test("discoverExpressRoutes: skips unknown HTTP methods", () => {
  const app: ExpressLikeApp = {
    _router: { stack: [routeLayer("/x", ["get", "trace", "weird"])] },
  };
  assert.deepEqual(discoverExpressRoutes(app), [
    { method: "GET", path: "/x" },
  ]);
});

test("discoverExpressRoutes: expands array-of-paths into one route per path", () => {
  const app: ExpressLikeApp = {
    _router: { stack: [
      { route: { path: ["/old", "/new"], methods: { get: true } } },
    ] },
  };
  const routes = discoverExpressRoutes(app).sort((a, b) => a.path.localeCompare(b.path));
  assert.deepEqual(routes, [
    { method: "GET", path: "/new" },
    { method: "GET", path: "/old" },
  ]);
});

test("discoverExpressRoutes: skips sub-tree with unrecognisable mount regexp", () => {
  // Parametric mount we don't understand → skipped, but sibling routes still come through.
  const param = routerLayer("^\\/(?:([^/]+?))\\/?(?=\\/|$)", [
    routeLayer("/inside",  ["get"]),
  ]);
  const app: ExpressLikeApp = {
    _router: {
      stack: [
        param,
        routeLayer("/healthz", ["get"]),
      ],
    },
  };
  assert.deepEqual(discoverExpressRoutes(app), [
    { method: "GET", path: "/healthz" },
  ]);
});

test("expressManifestSource: framework is 'express'", () => {
  const src = expressManifestSource({
    _router: { stack: [routeLayer("/x", ["get"])] },
  });
  assert.equal(src.framework, "express");
  const out = src.collect();
  assert.deepEqual(
    Array.isArray(out) ? out : [],
    [{ method: "GET", path: "/x" }],
  );
});
