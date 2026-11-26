/**
 * Auth surface — matches the console's MSW handlers.
 *
 * Phase 8 ships a sticky-seat stub: /me returns whatever seat the last
 * /demo-login set (defaulting to Maya / owner). No cookies, no
 * verification — Phase 10 swaps to real cookie-backed sessions when
 * auth becomes a real concern.
 */
import { Hono } from "hono";

import { store } from "../store/index.js";

export const auth = new Hono();

function sessionFor(seatId: string) {
  const seat = store.seat(seatId);
  if (!seat) return null;
  return {
    user: { id: seat.id, email: seat.email, name: seat.name, image: null },
    activeOrg: { id: "org_acme", slug: "acme", name: "Acme Corp", role: seat.role },
    permissions: store.permsForRole(seat.role),
  };
}

auth.get("/auth/me", (c) => {
  const s = sessionFor(store.activeSeat().id);
  if (!s) return c.body(null, 401);
  return c.json(s);
});

auth.post("/auth/demo-login", async (c) => {
  let body: { userId?: string };
  try { body = await c.req.json(); } catch { return c.json({ error: "bad json" }, 400); }
  if (!body?.userId) return c.json({ error: "userId required" }, 400);
  const s = sessionFor(body.userId);
  if (!s) return c.json({ error: "unknown demo seat" }, 404);
  store.setActiveSeat(body.userId);
  return c.json(s);
});

auth.post("/auth/logout", (c) => {
  // Phase 8 keeps the active seat — there's nothing real to log out of.
  return c.body(null, 204);
});
