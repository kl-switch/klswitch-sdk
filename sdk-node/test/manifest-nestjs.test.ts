import { test } from "node:test";
import assert from "node:assert/strict";

import {
  discoverNestjsRoutes,
  nestjsManifestSource,
  type NestLikeApp,
} from "../src/manifest/nestjs.js";

/**
 * NestJS adapter discovery delegates to the underlying Express or
 * Fastify instance. We hand-craft a mock INestApplication shape — the
 * tests don't depend on Nest itself.
 */

function expressInstance(routes: Array<{ path: string; methods: string[] }>): unknown {
  const stack = routes.map((r) => {
    const methodMap: Record<string, boolean> = {};
    for (const m of r.methods) methodMap[m.toLowerCase()] = true;
    return { route: { path: r.path, methods: methodMap } };
  });
  return { _router: { stack } };
}

function fastifyInstance(tree: string): unknown {
  return { printRoutes: () => tree };
}

function makeApp(adapterType: string | null, instance: unknown): NestLikeApp {
  return {
    getHttpAdapter: () => ({
      getInstance: () => instance,
      getType: adapterType ? () => adapterType : undefined,
    }),
  };
}

test("discoverNestjsRoutes: returns [] when app is null", () => {
  assert.deepEqual(discoverNestjsRoutes(null), []);
});

test("discoverNestjsRoutes: returns [] when getHttpAdapter missing", () => {
  assert.deepEqual(discoverNestjsRoutes({}), []);
});

test("discoverNestjsRoutes: returns [] when getHttpAdapter throws", () => {
  const app: NestLikeApp = {
    getHttpAdapter: () => { throw new Error("not yet initialised"); },
  };
  assert.deepEqual(discoverNestjsRoutes(app), []);
});

test("discoverNestjsRoutes: returns [] when adapter.getInstance missing", () => {
  const app: NestLikeApp = { getHttpAdapter: () => ({}) };
  assert.deepEqual(discoverNestjsRoutes(app), []);
});

test("discoverNestjsRoutes: returns [] when getInstance throws", () => {
  const app: NestLikeApp = {
    getHttpAdapter: () => ({
      getInstance: () => { throw new Error("nope"); },
    }),
  };
  assert.deepEqual(discoverNestjsRoutes(app), []);
});

test("discoverNestjsRoutes: walks express adapter (declared type)", () => {
  const app = makeApp(
    "express",
    expressInstance([
      { path: "/v1/payments",     methods: ["POST"] },
      { path: "/v1/payments/:id", methods: ["GET", "DELETE"] },
    ]),
  );
  const routes = discoverNestjsRoutes(app).sort((a, b) =>
    `${a.method} ${a.path}`.localeCompare(`${b.method} ${b.path}`),
  );
  assert.deepEqual(routes, [
    { method: "DELETE", path: "/v1/payments/:id" },
    { method: "GET",    path: "/v1/payments/:id" },
    { method: "POST",   path: "/v1/payments"     },
  ]);
});

test("discoverNestjsRoutes: walks express adapter (structural detection, no getType)", () => {
  // Some custom adapters don't implement getType — we fall back to
  // detecting `_router` on the instance.
  const app = makeApp(null, expressInstance([{ path: "/healthz", methods: ["GET"] }]));
  assert.deepEqual(discoverNestjsRoutes(app), [
    { method: "GET", path: "/healthz" },
  ]);
});

test("discoverNestjsRoutes: walks fastify adapter (declared type)", () => {
  const tree = ["└── /", "    └── v1/payments (POST)"].join("\n");
  const app = makeApp("fastify", fastifyInstance(tree));
  assert.deepEqual(discoverNestjsRoutes(app), [
    { method: "POST", path: "/v1/payments" },
  ]);
});

test("discoverNestjsRoutes: walks fastify adapter (structural detection)", () => {
  const tree = ["└── /", "    └── healthz (GET)"].join("\n");
  const app = makeApp(null, fastifyInstance(tree));
  assert.deepEqual(discoverNestjsRoutes(app), [
    { method: "GET", path: "/healthz" },
  ]);
});

test("discoverNestjsRoutes: returns [] for an unknown adapter shape", () => {
  // No _router, no printRoutes — we can't introspect.
  const app = makeApp(null, { mystery: true });
  assert.deepEqual(discoverNestjsRoutes(app), []);
});

test("nestjsManifestSource: framework='nestjs' + eager app", () => {
  const app = makeApp("express", expressInstance([{ path: "/x", methods: ["GET"] }]));
  const src = nestjsManifestSource(app);
  assert.equal(src.framework, "nestjs");
  const result = src.collect();
  assert.deepEqual(
    Array.isArray(result) ? result : [],
    [{ method: "GET", path: "/x" }],
  );
});

test("nestjsManifestSource: lazy thunk — defers resolution to collect time", () => {
  // Mimics the realistic boot order: source is constructed before the
  // Nest app exists. The thunk captures the variable; the SDK calls it
  // only when reportManifest fires (post app.init()).
  let app: NestLikeApp | null = null;
  const src = nestjsManifestSource(() => app);

  // Empty before app boots.
  assert.deepEqual(src.collect(), []);

  // Now boot.
  app = makeApp("express", expressInstance([{ path: "/late", methods: ["POST"] }]));
  assert.deepEqual(src.collect(), [{ method: "POST", path: "/late" }]);
});

test("nestjsManifestSource: lazy thunk — undefined / null are treated as 'not ready'", () => {
  const src1 = nestjsManifestSource(() => null);
  const src2 = nestjsManifestSource(() => undefined);
  assert.deepEqual(src1.collect(), []);
  assert.deepEqual(src2.collect(), []);
});

/**
 * The shape that actually arrives.
 *
 * `app.getHttpAdapter().getInstance()` returns the underlying Express app, and
 * an Express app is a *callable* — `typeof` is "function", not "object". The
 * guard here rejected non-objects, so every NestJS application reported zero
 * routes, and did it quietly: the empty manifest was pushed, its hash never
 * changed, and the first-evaluation re-check that exists for this exact case
 * re-ran the same discovery and found the same nothing.
 *
 * Every other test in this file builds its stub with an object literal, which
 * is why eleven of them passed throughout.
 */
test("discoverNestjsRoutes: reads a callable Express instance", () => {
  const expressApp = Object.assign(
    function handler() {},
    {
      _router: {
        stack: [
          { route: { path: "/api/items", methods: { get: true } } },
          { route: { path: "/api/orders/:id", methods: { put: true, delete: true } } },
        ],
      },
    },
  );
  const app = {
    getHttpAdapter: () => ({
      getInstance: () => expressApp,
      getType: () => "express",
    }),
  };

  assert.deepEqual(
    discoverNestjsRoutes(app as never).map((r) => `${r.method} ${r.path}`),
    ["GET /api/items", "PUT /api/orders/:id", "DELETE /api/orders/:id"],
  );
});
