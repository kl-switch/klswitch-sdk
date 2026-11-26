import { test } from "node:test";
import assert from "node:assert/strict";

import {
  discoverFastifyRoutes,
  fastifyManifestSource,
  parsePrintRoutes,
} from "../src/manifest/fastify.js";

/**
 * Stubs Fastify's `printRoutes({ commonPrefix: false })` output across
 * the v4+ shape. We don't depend on Fastify itself for these tests —
 * the parser only needs the ASCII tree string.
 */

test("parsePrintRoutes: parses a single-method route", () => {
  const tree = [
    "└── /",
    "    └── v1/payments (POST)",
  ].join("\n");
  assert.deepEqual(parsePrintRoutes(tree), [
    { method: "POST", path: "/v1/payments" },
  ]);
});

test("parsePrintRoutes: parses multi-method routes (GET, DELETE)", () => {
  const tree = [
    "└── /",
    "    └── v1/orders/:id (GET, DELETE)",
  ].join("\n");
  assert.deepEqual(parsePrintRoutes(tree), [
    { method: "GET",    path: "/v1/orders/:id" },
    { method: "DELETE", path: "/v1/orders/:id" },
  ]);
});

test("parsePrintRoutes: parses a multi-route tree", () => {
  const tree = [
    "└── /",
    "    ├── v1/payments (POST)",
    "    ├── v1/payments/:id (GET, DELETE)",
    "    └── healthz (GET)",
  ].join("\n");
  assert.deepEqual(parsePrintRoutes(tree).sort((a, b) =>
    `${a.method} ${a.path}`.localeCompare(`${b.method} ${b.path}`)), [
    { method: "DELETE", path: "/v1/payments/:id" },
    { method: "GET",    path: "/healthz" },
    { method: "GET",    path: "/v1/payments/:id" },
    { method: "POST",   path: "/v1/payments" },
  ]);
});

test("parsePrintRoutes: ignores blank lines / non-route lines", () => {
  const tree = "\n   \n └── / \n some stray text\n  ";
  assert.deepEqual(parsePrintRoutes(tree), []);
});

test("parsePrintRoutes: skips unknown HTTP methods", () => {
  const tree = [
    "└── /",
    "    └── chatroom (NOTAVERB)",
  ].join("\n");
  assert.deepEqual(parsePrintRoutes(tree), []);
});

test("parsePrintRoutes: normalises a leading-slash-less path", () => {
  const tree = [
    "└── /",
    "    └── api/foo (GET)",
  ].join("\n");
  assert.deepEqual(parsePrintRoutes(tree), [
    { method: "GET", path: "/api/foo" },
  ]);
});

test("discoverFastifyRoutes: returns [] when printRoutes is absent", () => {
  // E.g. older Fastify or a stripped-down stub.
  const app = {};
  assert.deepEqual(discoverFastifyRoutes(app), []);
});

test("discoverFastifyRoutes: returns [] when printRoutes throws", () => {
  const app = { printRoutes: () => { throw new Error("printRoutes blew up"); } };
  assert.deepEqual(discoverFastifyRoutes(app), []);
});

test("discoverFastifyRoutes: passes commonPrefix:false to Fastify", () => {
  let opts: { commonPrefix?: boolean } | undefined;
  const app = {
    printRoutes: (o?: { commonPrefix?: boolean }) => { opts = o; return ""; },
  };
  discoverFastifyRoutes(app);
  assert.equal(opts?.commonPrefix, false,
    "we must call printRoutes({commonPrefix: false}) so each path is fully qualified");
});

test("fastifyManifestSource: returns a ManifestSource with framework='fastify'", () => {
  const app = {
    printRoutes: () => [
      "└── /",
      "    └── v1/payments (POST)",
    ].join("\n"),
  };
  const src = fastifyManifestSource(app);
  assert.equal(src.framework, "fastify");
  const result = src.collect();
  // collect() may be sync or Promise per the ManifestSource contract.
  assert.deepEqual(
    Array.isArray(result) ? result : [],
    [{ method: "POST", path: "/v1/payments" }],
  );
});

/**
 * The real thing.
 *
 * Every test above feeds a hand-written tree whose children are already
 * fully-qualified, and all ten passed while the adapter was reporting `/:id`
 * and `/status` as top-level endpoints to a live backend. Fastify does not emit
 * that shape: `commonPrefix: false` stops it compressing shared static prefixes
 * into one node, it does not flatten the tree, so each line carries only its own
 * segment. This block is captured verbatim from
 * `app.printRoutes({ commonPrefix: false })` on Fastify v4 and is the reason the
 * parser walks indentation rather than trusting the line.
 */
const REAL_FASTIFY_TREE = [
  "├── /health (GET, HEAD)",
  "├── /api/items (GET, HEAD)",
  "│   └── /:id (GET, HEAD)",
  "│       └── /reviews/:reviewId (GET, HEAD)",
  "└── /api/orders (POST)",
  "    └── /:id (PUT, DELETE)",
  "        └── /status (PATCH)",
].join("\n");

test("parsePrintRoutes: rebuilds nested paths from the real Fastify tree", () => {
  const routes = parsePrintRoutes(REAL_FASTIFY_TREE);
  assert.deepEqual(routes.map((r) => `${r.method} ${r.path}`), [
    "GET /health",
    "GET /api/items",
    "GET /api/items/:id",
    "GET /api/items/:id/reviews/:reviewId",
    "POST /api/orders",
    "PUT /api/orders/:id",
    "DELETE /api/orders/:id",
    "PATCH /api/orders/:id/status",
  ]);
});

test("parsePrintRoutes: drops the HEAD Fastify pairs with every GET", () => {
  // Eight of the twenty-six routes reported against the live backend were these.
  assert.equal(parsePrintRoutes(REAL_FASTIFY_TREE).some((r) => r.method === "HEAD"), false);
});

test("parsePrintRoutes: keeps a route registered HEAD-only", () => {
  const tree = ["└── /probe (HEAD)"].join("\n");
  assert.deepEqual(parsePrintRoutes(tree), [{ method: "HEAD", path: "/probe" }]);
});

test("parsePrintRoutes: a branch node contributes a segment but no route", () => {
  // Fastify emits a parent with no methods when nothing is registered at it.
  const tree = [
    "└── /api",
    "    └── /items (GET)",
  ].join("\n");
  assert.deepEqual(parsePrintRoutes(tree), [{ method: "GET", path: "/api/items" }]);
});

test("parsePrintRoutes: a sibling after a deep branch resets the prefix", () => {
  // The failure a naive stack makes: /other must not inherit /api/items/:id.
  const tree = [
    "├── /api/items (GET)",
    "│   └── /:id (GET)",
    "└── /other (GET)",
  ].join("\n");
  assert.deepEqual(parsePrintRoutes(tree).map((r) => r.path), [
    "/api/items",
    "/api/items/:id",
    "/other",
  ]);
});
