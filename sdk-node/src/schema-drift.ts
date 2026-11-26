/**
 * Schema-drift detection — Layer 3 (runtime).
 *
 * After every evaluation, we walk each *matched* rule's condition tree
 * and tag the analytics event with the set of fields the rule referenced
 * that weren't present in the request input. Operators see these in the
 * console as "rule X referenced `body.coupon_code`, but `coupon_code`
 * isn't in this route's body" — early warning that a rule was authored
 * against a stale request shape.
 *
 * Pure wrapper-side; the engine doesn't know about this. See
 * `docs/schema-drift-detection.md` for the four-layer detection plan.
 *
 * **Why only matched rules?** Unmatched rules also reference fields, but
 * including them in the per-event drift list would blow the analytics
 * volume up by 100× (every evaluation would carry every-rule's full
 * field set). Matched rules are the actionable subset: those are the
 * rules whose decisions touched production traffic.
 */

import type {
  ConditionLeaf,
  ConditionNode,
  EvaluationInput,
  MatchedRule,
  Rule,
} from "./types.js";

/**
 * Compute the deduplicated, sorted list of field paths referenced by
 * any matched rule's condition tree that were NOT present in the
 * request input. Empty when no matched rule referenced a missing field
 * (common case — keeps the wire-format slim).
 */
export function computeMissingFields(
  matched: MatchedRule[],
  rulesById: Map<string, Rule>,
  input: EvaluationInput,
): string[] {
  if (matched.length === 0) return [];
  const seen = new Set<string>();
  for (const m of matched) {
    const rule = rulesById.get(m.id);
    if (!rule) continue;
    for (const leaf of walkLeaves(rule.condition)) {
      if (!hasField(input, leaf.field)) seen.add(leaf.field);
    }
  }
  return [...seen].sort();
}

/** Recursively yield every leaf condition in the tree. */
export function* walkLeaves(node: ConditionNode): Iterable<ConditionLeaf> {
  if ("all" in node) {
    for (const child of node.all) yield* walkLeaves(child);
    return;
  }
  if ("any" in node) {
    for (const child of node.any) yield* walkLeaves(child);
    return;
  }
  if ("not" in node) {
    yield* walkLeaves(node.not);
    return;
  }
  // Leaf — has `field` + `operator`. Validated on rule compile, so we
  // trust the shape here.
  yield node;
}

/**
 * Returns true iff the dotted `path` resolves to a defined value within
 * the request input. `body.amount` walks `input.facts.body.amount`;
 * `headers.x-api-key` walks `input.facts.headers["x-api-key"]`.
 *
 * Treats `null` as "absent" (matching the engine's missing-field
 * semantics: a null body field is the same as not setting it). Empty
 * string and zero are considered PRESENT — they're real values an
 * operator might have explicitly sent.
 */
export function hasField(input: EvaluationInput, path: string): boolean {
  if (path.length === 0) return false;
  const segments = path.split(".");
  if (segments.length === 0) return false;
  const bucket = (input.facts as unknown as Record<string, unknown>)[segments[0]!];
  if (bucket === undefined || bucket === null) return false;
  let cursor: unknown = bucket;
  for (let i = 1; i < segments.length; i++) {
    if (cursor === null || typeof cursor !== "object") return false;
    cursor = (cursor as Record<string, unknown>)[segments[i]!];
    if (cursor === undefined || cursor === null) return false;
  }
  return true;
}

/** Build a lookup table mapping rule id → Rule. Cheap wrapper around a
 *  Map; exported so callers can build the map once per ruleset swap
 *  rather than once per evaluate(). */
export function rulesById(rules: Rule[]): Map<string, Rule> {
  const out = new Map<string, Rule>();
  for (const r of rules) out.set(r.id, r);
  return out;
}
