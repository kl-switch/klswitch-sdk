import express, { type Request, type RequestHandler, type Response } from "express";
import {
  KLSwitch,
  klswitchMiddleware,
  consoleLogger,
  expressManifestSource,
  type EvaluationResult,
} from "@klswitch/sdk-node";

import { demoRules } from "./rules.js";

// Create the app first so the manifest source can introspect its route table.
const app = express();
app.use(express.json());

const ks = await KLSwitch.init({
  rules: demoRules,
  logger: consoleLogger,
  // When KS_TOKEN is set, point at a real backend (klswitch): emit analytics AND report the
  // discovered API surface. The per-(service,env) API key lets the backend derive service + env.
  ...(process.env.KS_TOKEN
    ? {
        token: process.env.KS_TOKEN,
        baseUrl: process.env.KS_BASE_URL ?? "http://localhost:40010",
        analytics: { batchSize: 1, flushIntervalMs: 500 },
        manifest: expressManifestSource(app),
      }
    : {}),
});

/**
 * Centralized decision: if a matched rule's action is `block`, short-circuit with
 * the rule's status code; otherwise attach the result and continue. (The adapter
 * itself never interprets actions — that policy lives here, in the app.)
 */
// The only KLSwitch line in this file.
//
// `app` lets the app-wide mount report the *templated* route (`/v1/payments/:id`)
// rather than the concrete one — the same path the manifest reports and rules are
// written against. Blocking is the default, so no route and no handler below
// contains any KLSwitch code.
app.use(klswitchMiddleware<Response>(ks, { app }));

const matched = (req: Request) =>
  ((req as unknown as { klswitch?: EvaluationResult }).klswitch?.matched ?? []).map((m) => ({
    id: m.id,
    action: m.action.type,
  }));

const ok = (req: Request, extra: Record<string, unknown> = {}) => ({ ok: true, matched: matched(req), ...extra });

app.post("/v1/payments/:id", (req, res) => res.json(ok(req, { paymentId: req.params.id })));
app.post("/v1/auth/login", (req, res) => res.json(ok(req, { user: req.body?.username })));
app.get("/v1/users/:id", (req, res) => res.json(ok(req, { userId: req.params.id })));
app.put("/v1/products/:id", (req, res) => res.json(ok(req, { productId: req.params.id })));
app.post("/v1/orders", (req, res) => res.json(ok(req)));
app.post("/v1/messages", (req, res) => res.json(ok(req)));
app.get("/v1/search", (req, res) => res.json(ok(req, { q: req.query.q ?? null })));
app.delete("/v1/sessions/:id", (req, res) => res.json(ok(req, { sessionId: req.params.id })));
app.get("/v1/admin/reports", (req, res) => res.json(ok(req)));
app.post("/v1/feature/evaluate", (req, res) => res.json(ok(req)));

app.get("/health", (_req, res) => {
  res.json({ ok: true, status: ks.getStatus() });
});

const port = Number(process.env.PORT ?? 3000);
app.listen(port, () => console.log(`[express-demo] listening on http://localhost:${port}`));
