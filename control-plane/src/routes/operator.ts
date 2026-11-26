/**
 * Operator console surface — /api/control/orgs/:org/*.
 *
 * Read-only in Phase 8. Response shapes match the console's MSW
 * handlers verbatim so swapping `NEXT_PUBLIC_API_BASE` is the only
 * change needed on the console side.
 */
import { Hono } from "hono";

import { store } from "../store/index.js";

export const operator = new Hono();

// ── Orgs (the org-switcher needs a list) ───────────────────────────────
operator.get("/orgs", (c) => {
  const seat = store.activeSeat();
  return c.json([{ id: "org_acme", slug: "acme", name: "Acme Corp", role: seat.role }]);
});

operator.get("/orgs/:org", (c) => {
  return c.json({
    id:        "org_acme",
    slug:      "acme",
    name:      "Acme Corp",
    createdAt: new Date(Date.now() - 90 * 24 * 60 * 60 * 1000).toISOString(),
  });
});

// ── Audit ──────────────────────────────────────────────────────────────
operator.get("/orgs/:org/audit", (c) => {
  const actor  = c.req.query("actor")  ?? undefined;
  const action = c.req.query("action") ?? undefined;
  const limit  = Number(c.req.query("limit") ?? "50");
  return c.json(store.audit({ actor, action }, limit));
});

// ── Rules ──────────────────────────────────────────────────────────────
operator.get("/orgs/:org/rules/summary", (c) => {
  return c.json(store.rulesSummary());
});

operator.get("/orgs/:org/rules", (c) => {
  const service = c.req.query("service");
  const env     = c.req.query("env");
  const status  = c.req.query("status");
  const q       = (c.req.query("q") ?? "").toLowerCase();

  const items = store
    .rules()
    .filter((r) => !service || r.serviceId === service)
    .filter((r) => !env     || r.envId     === env)
    .filter((r) => {
      if (!q) return true;
      return (
        r.name.toLowerCase().includes(q) ||
        r.key.toLowerCase().includes(q) ||
        r.selectorPath.toLowerCase().includes(q)
      );
    })
    .map((rule) => {
      const currentVersion = store.ruleVersion(rule.currentVersionId);
      if (!currentVersion) return null;
      return { rule, currentVersion };
    })
    .filter((x): x is NonNullable<typeof x> => !!x)
    .filter((item) => !status || item.currentVersion.status === status);

  return c.json(items);
});

operator.get("/orgs/:org/rules/:id", (c) => {
  const id = c.req.param("id");
  const rule = store.rule(id);
  if (!rule) return c.body(null, 404);
  const versions = store
    .ruleVersions()
    .filter((v) => v.ruleId === id)
    .sort((a, b) => b.versionInt - a.versionInt);
  const currentVersion = versions.find((v) => v.id === rule.currentVersionId);
  if (!currentVersion) return c.body(null, 500);
  return c.json({ rule, currentVersion, versions });
});

operator.get("/orgs/:org/rules/:id/versions", (c) => {
  const id = c.req.param("id");
  const versions = store
    .ruleVersions()
    .filter((v) => v.ruleId === id)
    .sort((a, b) => b.versionInt - a.versionInt);
  return c.json(versions);
});

// ── Services + envs ─────────────────────────────────────────────────────
operator.get("/orgs/:org/services", (c) => {
  const includeArchived = c.req.query("includeArchived") === "1";
  const items = includeArchived
    ? store.services()
    : store.services().filter((s) => !s.archived);
  return c.json(items);
});

operator.get("/orgs/:org/services/:svc/envs", (c) => {
  const svc = c.req.param("svc");
  const includeArchived = c.req.query("includeArchived") === "1";
  const all = store.envs().filter((e) => e.serviceId === svc);
  return c.json(includeArchived ? all : all.filter((e) => !e.archived));
});

operator.get("/orgs/:org/envs", (c) => {
  const includeArchived = c.req.query("includeArchived") === "1";
  const all = store.envs();
  return c.json(includeArchived ? all : all.filter((e) => !e.archived));
});

// ── Instances summary (fixed for Phase 8) ───────────────────────────────
operator.get("/orgs/:org/instances/summary", (c) => {
  return c.json(store.instanceHealthSummary());
});
