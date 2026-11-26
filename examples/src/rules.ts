import type { RuleSetPayload } from "@klswitch/sdk-node";

/**
 * Exhaustive demo ruleset. Exercises every v1 operator, nested all/any/not,
 * dotted field paths, priority ordering with multiple matches, a disabled rule,
 * and the missing-field nuances. Paths are the templated routes the adapters
 * produce (e.g. /v1/payments/:id).
 */
export const demoRules: RuleSetPayload = {
  schema_version: "1.0",
  rules_version: 2,
  rules: [
    // ── POST /v1/payments/:id ── priority + multiple matches; gt / in / gte+lte / eq
    {
      id: "block_restricted_region",
      name: "Block restricted region",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/payments/:id" } },
      enabled: true,
      priority: 110, // highest — wins ordering
      condition: { all: [{ field: "body.country", operator: "in", value: ["KP", "IR", "SY"] }] },
      action: { type: "block", payload: { statusCode: 451, code: "RESTRICTED_REGION" } },
    },
    {
      id: "block_large_payment",
      name: "Block large payment",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/payments/:id" } },
      enabled: true,
      priority: 100,
      condition: { all: [{ field: "body.amount", operator: "gt", value: 50000 }] },
      action: { type: "block", payload: { statusCode: 429, code: "PAYMENT_TOO_LARGE" } },
    },
    {
      id: "flag_high_value_review",
      name: "Flag mid/high value for review",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/payments/:id" } },
      enabled: true,
      priority: 50,
      condition: {
        all: [
          { field: "body.amount", operator: "gte", value: 10000 },
          { field: "body.amount", operator: "lte", value: 50000 },
        ],
      },
      action: { type: "flag", payload: { code: "NEEDS_REVIEW", queue: "risk" } },
    },
    {
      id: "notify_first_time_payer",
      name: "Notify on first-time payer",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/payments/:id" } },
      enabled: true,
      priority: 10, // lowest — appears last in matched
      condition: { all: [{ field: "body.first_time", operator: "eq", value: true }] },
      action: { type: "notify", payload: { channel: "ops" } },
    },

    // ── POST /v1/auth/login ── gte, nested any, and a DISABLED rule
    {
      id: "block_brute_force_login",
      name: "Block brute-force login",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/auth/login" } },
      enabled: true,
      priority: 100,
      condition: { all: [{ field: "body.failed_attempts", operator: "gte", value: 5 }] },
      action: { type: "block", payload: { statusCode: 429, code: "TOO_MANY_ATTEMPTS" } },
    },
    {
      id: "flag_suspicious_login",
      name: "Flag suspicious login",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/auth/login" } },
      enabled: true,
      priority: 50,
      condition: {
        all: [
          { field: "meta.ip_reputation", operator: "eq", value: "suspicious" },
          {
            any: [
              { field: "body.country", operator: "eq", value: "IN" },
              { field: "body.country", operator: "eq", value: "BR" },
            ],
          },
        ],
      },
      action: { type: "flag", payload: { code: "SUSPICIOUS_LOGIN" } },
    },
    {
      id: "disabled_legacy_geo_block",
      name: "Legacy geo block (disabled — never matches)",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/auth/login" } },
      enabled: false, // demonstrates the per-rule kill switch
      priority: 80,
      condition: { all: [{ field: "body.country", operator: "eq", value: "IN" }] },
      action: { type: "block", payload: { statusCode: 451, code: "LEGACY_GEO" } },
    },

    // ── GET /v1/users/:id ── any + exists, reading query + header facts
    {
      id: "flag_admin_access",
      name: "Flag admin access",
      selector: { kind: "api", keys: { method: "GET", path: "/v1/users/:id" } },
      enabled: true,
      priority: 50,
      condition: {
        any: [
          { field: "query.role", operator: "eq", value: "admin" },
          { field: "headers.x-impersonate", operator: "exists" },
        ],
      },
      action: { type: "flag", payload: { code: "ADMIN_ACCESS", queue: "audit" } },
    },

    // ── PUT /v1/products/:id ── lt (and lt 0)
    {
      id: "block_negative_price",
      name: "Block negative price",
      selector: { kind: "api", keys: { method: "PUT", path: "/v1/products/:id" } },
      enabled: true,
      priority: 90,
      condition: { all: [{ field: "body.price", operator: "lt", value: 0 }] },
      action: { type: "block", payload: { statusCode: 400, code: "INVALID_PRICE" } },
    },
    {
      id: "flag_low_inventory",
      name: "Flag low inventory",
      selector: { kind: "api", keys: { method: "PUT", path: "/v1/products/:id" } },
      enabled: true,
      priority: 40,
      condition: { all: [{ field: "body.stock", operator: "lt", value: 10 }] },
      action: { type: "flag", payload: { code: "LOW_STOCK" } },
    },

    // ── POST /v1/orders ── not_in, gt
    {
      id: "block_unsupported_currency",
      name: "Block unsupported currency",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/orders" } },
      enabled: true,
      priority: 70,
      condition: { all: [{ field: "body.currency", operator: "not_in", value: ["USD", "EUR", "GBP"] }] },
      action: { type: "block", payload: { statusCode: 400, code: "UNSUPPORTED_CURRENCY" } },
    },
    {
      id: "block_oversized_order",
      name: "Block oversized order",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/orders" } },
      enabled: true,
      priority: 60,
      condition: { all: [{ field: "body.item_count", operator: "gt", value: 100 }] },
      action: { type: "block", payload: { statusCode: 413, code: "ORDER_TOO_LARGE" } },
    },

    // ── POST /v1/messages ── contains / not_contains (content moderation)
    {
      id: "block_spam_message",
      name: "Block spam message",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/messages" } },
      enabled: true,
      priority: 100,
      condition: { all: [{ field: "body.text", operator: "contains", value: "spam" }] },
      action: { type: "block", payload: { statusCode: 403, code: "SPAM" } },
    },
    {
      id: "flag_missing_disclaimer",
      name: "Flag missing disclaimer",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/messages" } },
      enabled: true,
      priority: 20,
      // not_contains is false when body.text is missing (missing field => false),
      // so this only fires when text is present and lacks the word.
      condition: { all: [{ field: "body.text", operator: "not_contains", value: "disclaimer" }] },
      action: { type: "flag", payload: { code: "NO_DISCLAIMER" } },
    },

    // ── GET /v1/search ── contains on query + not_exists (missing field is true)
    {
      id: "block_injection_search",
      name: "Block injection-ish search",
      selector: { kind: "api", keys: { method: "GET", path: "/v1/search" } },
      enabled: true,
      priority: 100,
      condition: { all: [{ field: "query.q", operator: "contains", value: "DROP TABLE" }] },
      action: { type: "block", payload: { statusCode: 400, code: "BAD_QUERY" } },
    },
    {
      id: "flag_empty_search",
      name: "Flag empty search",
      selector: { kind: "api", keys: { method: "GET", path: "/v1/search" } },
      enabled: true,
      priority: 10,
      condition: { all: [{ field: "query.q", operator: "not_exists" }] },
      action: { type: "flag", payload: { code: "EMPTY_QUERY" } },
    },

    // ── DELETE /v1/sessions/:id ── not_exists on a header (auth required)
    {
      id: "require_auth_header",
      name: "Require authorization header",
      selector: { kind: "api", keys: { method: "DELETE", path: "/v1/sessions/:id" } },
      enabled: true,
      priority: 100,
      condition: { all: [{ field: "headers.authorization", operator: "not_exists" }] },
      action: { type: "block", payload: { statusCode: 401, code: "AUTH_REQUIRED" } },
    },

    // ── GET /v1/admin/reports ── not(eq) so a MISSING role is ALSO blocked.
    // (neq would let a missing header through, because a missing field is false.)
    {
      id: "block_non_admin_reports",
      name: "Block non-admin reports",
      selector: { kind: "api", keys: { method: "GET", path: "/v1/admin/reports" } },
      enabled: true,
      priority: 100,
      condition: { not: { field: "headers.x-role", operator: "eq", value: "admin" } },
      action: { type: "block", payload: { statusCode: 403, code: "ADMIN_ONLY" } },
    },

    // ── POST /v1/feature/evaluate ── nested all/any/not + dotted path + neq
    {
      id: "enable_premium_feature",
      name: "Enable premium feature",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/feature/evaluate" } },
      enabled: true,
      priority: 50,
      condition: {
        all: [
          { field: "body.user.tier", operator: "eq", value: "premium" },
          {
            any: [
              { field: "body.region", operator: "eq", value: "us" },
              { field: "body.region", operator: "eq", value: "eu" },
            ],
          },
          { not: { field: "body.user.blocked", operator: "eq", value: true } },
          { field: "body.env", operator: "neq", value: "test" },
        ],
      },
      action: { type: "allow", payload: { feature: "premium_dashboard" } },
    },
  ],
};
