import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { canonicalizeTemplate, toEvaluationInput } from "../src/index.js";

/**
 * The regression: a host integrating without an adapter emitted FastAPI-style `{id}` templates.
 * Rules whose selectors carried no path parameter still matched, so the integration looked
 * healthy — only the parameterised rules silently missed. Six of eight checks passed, which is
 * exactly the shape that hides a broken integration.
 */
describe("canonicalizeTemplate", () => {
  it("accepts every common dialect", () => {
    assert.equal(canonicalizeTemplate("/api/items/{id}"), "/api/items/:id");
    assert.equal(canonicalizeTemplate("/api/items/<id>"), "/api/items/:id");
    assert.equal(canonicalizeTemplate("/api/items/<int:id>"), "/api/items/:id");
    assert.equal(canonicalizeTemplate("/api/items/:id"), "/api/items/:id");
  });

  it("drops converter prefixes and suffixes, keeping the name", () => {
    assert.equal(canonicalizeTemplate("/static/{file_path:path}"), "/static/:file_path");
    assert.equal(canonicalizeTemplate("/static/<path:filename>"), "/static/:filename");
  });

  it("handles several parameters in one path", () => {
    assert.equal(
      canonicalizeTemplate("/api/items/{id}/reviews/{reviewId}"),
      "/api/items/:id/reviews/:reviewId",
    );
  });

  it("leaves paths without parameters alone", () => {
    assert.equal(canonicalizeTemplate("/api/items"), "/api/items");
    assert.equal(canonicalizeTemplate("/api/payments/*/refund"), "/api/payments/*/refund");
    assert.equal(canonicalizeTemplate("/api/items/**"), "/api/items/**");
  });

  it("is applied by toEvaluationInput, not just available to call", () => {
    // Being available was the old state — the converters existed and had to be found.
    const input = toEvaluationInput({ method: "post", path: "/api/payments/{id}/refund" });
    assert.equal(input.selector.keys.path, "/api/payments/:id/refund");
    assert.equal(input.selector.keys.method, "POST");
  });
});
