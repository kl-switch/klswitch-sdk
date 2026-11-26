import Fastify, { type FastifyReply, type FastifyRequest } from "fastify";
import { KLSwitch, klswitchHook, consoleLogger, type EvaluationResult } from "@klswitch/sdk-node";

import { demoRules } from "./rules.js";

const ks = await KLSwitch.init({ rules: demoRules, logger: consoleLogger });

const app = Fastify();

// preHandler so the parsed body is available as facts. Same policy as the Express
// demo: block on a matched `block` action, otherwise attach the result.
const guard = klswitchHook<FastifyReply>(ks, {
  onResult: (result, request, reply) => {
    (request as unknown as { klswitch?: EvaluationResult }).klswitch = result;
    const block = result.matched.find((m) => m.action.type === "block");
    if (block) {
      const status = (block.action.payload?.statusCode as number | undefined) ?? 403;
      reply.code(status).send({ blocked: true, ruleId: block.id, action: block.action });
    }
  },
});

app.addHook("preHandler", guard);

const matched = (request: FastifyRequest) =>
  ((request as unknown as { klswitch?: EvaluationResult }).klswitch?.matched ?? []).map((m) => ({
    id: m.id,
    action: m.action.type,
  }));

const id = (request: FastifyRequest) => (request.params as { id?: string }).id;

app.post("/v1/payments/:id", async (request) => ({ ok: true, paymentId: id(request), matched: matched(request) }));
app.post("/v1/auth/login", async (request) => ({ ok: true, matched: matched(request) }));
app.get("/v1/users/:id", async (request) => ({ ok: true, userId: id(request), matched: matched(request) }));
app.put("/v1/products/:id", async (request) => ({ ok: true, productId: id(request), matched: matched(request) }));
app.post("/v1/orders", async (request) => ({ ok: true, matched: matched(request) }));
app.post("/v1/messages", async (request) => ({ ok: true, matched: matched(request) }));
app.get("/v1/search", async (request) => ({ ok: true, matched: matched(request) }));
app.delete("/v1/sessions/:id", async (request) => ({ ok: true, sessionId: id(request), matched: matched(request) }));
app.get("/v1/admin/reports", async (request) => ({ ok: true, matched: matched(request) }));
app.post("/v1/feature/evaluate", async (request) => ({ ok: true, matched: matched(request) }));

app.get("/health", async () => ({ ok: true, status: ks.getStatus() }));

const port = Number(process.env.PORT ?? 3001);
app
  .listen({ port })
  .then((address) => console.log(`[fastify-demo] listening on ${address}`))
  .catch((err) => {
    console.error(err);
    process.exit(1);
  });
