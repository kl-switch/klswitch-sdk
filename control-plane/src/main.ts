/**
 * KLSwitch control-plane — Hono + node-server.
 *
 * Two surfaces in one process: /api/control/* for the operator console
 * and /v1/ruleset + /api/control/.../analytics for SDK wrappers.
 *
 * v0 ships with permissive CORS so the console (which runs on a
 * separate port in dev) can call cross-origin. Lock down to specific
 * origins in production.
 */
import { serve } from "@hono/node-server";
import { Hono } from "hono";

import { analytics } from "./routes/analytics.js";
import { auth } from "./routes/auth.js";
import { operator } from "./routes/operator.js";
import { wrapper } from "./routes/wrapper.js";
import { startDecisionsSimulator } from "./simulators/decisions-tick.js";

const app = new Hono();

// ── CORS (open in dev) ──────────────────────────────────────────────────
app.use("*", async (c, next) => {
  c.header("access-control-allow-origin",  c.req.header("origin") ?? "*");
  c.header("access-control-allow-headers", "content-type, authorization");
  c.header("access-control-allow-methods", "GET, POST, PATCH, DELETE, OPTIONS");
  c.header("access-control-allow-credentials", "true");
  if (c.req.method === "OPTIONS") return c.body(null, 204);
  await next();
});

// ── Request log (compact, stdout) ───────────────────────────────────────
app.use("*", async (c, next) => {
  const start = Date.now();
  await next();
  const ms = Date.now() - start;
  // eslint-disable-next-line no-console
  console.log(`${c.req.method} ${c.req.path} → ${c.res.status} (${ms}ms)`);
});

// ── Surfaces ────────────────────────────────────────────────────────────
app.get("/health", (c) => c.json({ ok: true, ts: new Date().toISOString() }));

app.route("/v1",          wrapper);
app.route("/api",         auth);
app.route("/api/control", operator);
app.route("/api/control", analytics);

// ── Bind ────────────────────────────────────────────────────────────────
const port = Number(process.env.PORT ?? "8787");
serve({ fetch: app.fetch, port }, (info) => {
  // eslint-disable-next-line no-console
  console.log(`control-plane listening on http://localhost:${info.port}`);
  // Stand in for SDK wrappers shipping analytics. KS_SIMULATE=0 to disable.
  startDecisionsSimulator();
});
