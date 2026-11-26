package com.klswitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Schema-drift detection — Layer 3 (runtime).
 *
 * <p>After every evaluation, we walk each <em>matched</em> rule's
 * condition tree and tag the analytics event with the set of fields
 * the rule referenced that weren't present in the request input.
 * Operators see these in the console as "rule X referenced
 * {@code body.coupon_code}, but {@code coupon_code} isn't in this
 * route's body" — early warning that a rule was authored against a
 * stale request shape.
 *
 * <p>Pure wrapper-side; the engine doesn't know about this. See
 * {@code docs/schema-drift-detection.md} for the four-layer detection
 * plan.
 *
 * <p>The Java wrapper passes ruleset + evaluation input across the WASM
 * boundary as JSON strings, so this helper works on Jackson
 * {@link JsonNode} rather than re-introducing a structured Rule /
 * Condition model on the wrapper side. The walker / presence checker
 * is intentionally tiny.
 */
public final class SchemaDrift {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaDrift() {}

    /**
     * Parse {@code rulesJson} and build a lookup of {@code ruleId →
     * condition node}. Returns an empty map on any parse error so a
     * malformed payload can never break {@code evaluate()} — the worst
     * case is just an empty {@code missingFields} list.
     */
    public static Map<String, JsonNode> indexConditions(String rulesJson) {
        if (rulesJson == null || rulesJson.isEmpty()) return Map.of();
        Map<String, JsonNode> out = new HashMap<>();
        try {
            JsonNode root = MAPPER.readTree(rulesJson);
            JsonNode rules = root.path("rules");
            if (!rules.isArray()) return Map.of();
            for (JsonNode rule : rules) {
                String id = rule.path("id").asText("");
                JsonNode condition = rule.get("condition");
                if (!id.isEmpty() && condition != null) {
                    out.put(id, condition);
                }
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return out;
    }

    /**
     * Compute the deduplicated, sorted list of field paths referenced
     * by any matched rule's condition tree that were NOT present in
     * {@code inputJson}'s {@code facts}. Empty when no matched rule
     * referenced a missing field (the common case — keeps the wire
     * format slim).
     */
    public static List<String> computeMissingFields(
            List<MatchedRule> matched,
            Map<String, JsonNode> conditionsById,
            String inputJson) {
        if (matched.isEmpty() || conditionsById.isEmpty()) return List.of();
        JsonNode facts;
        try {
            facts = MAPPER.readTree(inputJson).path("facts");
        } catch (Exception e) {
            return List.of();
        }
        if (facts.isMissingNode() || facts.isNull()) return List.of();

        SortedSet<String> missing = new TreeSet<>();
        for (MatchedRule m : matched) {
            JsonNode cond = conditionsById.get(m.id());
            if (cond == null) continue;
            walkLeaves(cond, leaf -> {
                String field = leaf.path("field").asText("");
                if (!field.isEmpty() && !hasField(facts, field)) {
                    missing.add(field);
                }
            });
        }
        return Collections.unmodifiableList(missing.stream().toList());
    }

    /** Recursively visit every leaf condition in {@code node}. Leaves
     *  are nodes with a {@code field} attribute. */
    static void walkLeaves(JsonNode node, Consumer<JsonNode> visitor) {
        if (node == null) return;
        JsonNode all = node.get("all");
        if (all != null && all.isArray()) {
            all.forEach(c -> walkLeaves(c, visitor));
            return;
        }
        JsonNode any = node.get("any");
        if (any != null && any.isArray()) {
            any.forEach(c -> walkLeaves(c, visitor));
            return;
        }
        JsonNode not = node.get("not");
        if (not != null) {
            walkLeaves(not, visitor);
            return;
        }
        if (node.has("field")) {
            visitor.accept(node);
        }
    }

    /**
     * Returns true iff the dotted {@code path} resolves to a defined
     * non-null value within {@code facts}. {@code body.amount} walks
     * {@code facts.body.amount}; {@code headers.x-api-key} walks
     * {@code facts.headers["x-api-key"]}.
     *
     * <p>Treats {@code null} as "absent" (matching the engine's
     * missing-field semantics: a null body field is the same as not
     * setting it). Empty string and zero are PRESENT — they're real
     * values an operator might have explicitly sent.
     */
    static boolean hasField(JsonNode facts, String path) {
        if (path.isEmpty()) return false;
        String[] segments = path.split("\\.");
        if (segments.length == 0) return false;
        JsonNode cursor = facts.get(segments[0]);
        if (cursor == null || cursor.isNull()) return false;
        for (int i = 1; i < segments.length; i++) {
            if (!cursor.isObject()) return false;
            cursor = cursor.get(segments[i]);
            if (cursor == null || cursor.isNull()) return false;
        }
        return true;
    }
}
