package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * B3 — Schema-drift Layer 3 (missing fields) tests for the Java
 * wrapper. Mirrors {@code sdk-node/test/schema-drift.test.ts}.
 */
class SchemaDriftTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MatchedRule matched(String id) {
        return new MatchedRule(id, id, 0L, new Action("block", null));
    }

    private static JsonNode parse(String json) {
        try { return MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    // ─── hasField (path resolution) ──────────────────────────────────────

    @Test
    void hasFieldResolvesDottedPaths() {
        JsonNode facts = parse(
                "{\"body\":{\"transaction\":{\"amount\":100}}, \"headers\":{}, \"query\":{}, \"params\":{}, \"meta\":{}}");
        assertTrue(SchemaDrift.hasField(facts, "body.transaction.amount"));
        assertFalse(SchemaDrift.hasField(facts, "body.transaction.currency"));
        assertFalse(SchemaDrift.hasField(facts, "body.absent"));
    }

    @Test
    void hasFieldTreatsNullAsAbsent() {
        JsonNode facts = parse("{\"body\":{\"coupon\":null}}");
        assertFalse(SchemaDrift.hasField(facts, "body.coupon"));
    }

    @Test
    void hasFieldTreatsZeroAndEmptyStringAsPresent() {
        JsonNode facts = parse("{\"body\":{\"amount\":0,\"coupon\":\"\"}}");
        assertTrue(SchemaDrift.hasField(facts, "body.amount"));
        assertTrue(SchemaDrift.hasField(facts, "body.coupon"));
    }

    @Test
    void hasFieldRejectsEmptyPath() {
        assertFalse(SchemaDrift.hasField(parse("{}"), ""));
    }

    @Test
    void hasFieldDashedKey() {
        JsonNode facts = parse("{\"headers\":{\"x-api-key\":\"abc\"}}");
        assertTrue(SchemaDrift.hasField(facts, "headers.x-api-key"));
        assertFalse(SchemaDrift.hasField(facts, "headers.x-missing"));
    }

    // ─── computeMissingFields ────────────────────────────────────────────

    @Test
    void noMatchedRulesMeansEmpty() {
        String rules = "{\"rules\":[{\"id\":\"r\",\"condition\":{\"field\":\"body.x\",\"operator\":\"gt\",\"value\":0}}]}";
        List<String> out = SchemaDrift.computeMissingFields(
                List.of(),
                SchemaDrift.indexConditions(rules),
                "{\"facts\":{\"body\":{}}}");
        assertTrue(out.isEmpty());
    }

    @Test
    void matchedRuleReferencingMissingField_reported() {
        String rules = "{\"rules\":[{\"id\":\"r\",\"condition\":{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":0}}]}";
        List<String> out = SchemaDrift.computeMissingFields(
                List.of(matched("r")),
                SchemaDrift.indexConditions(rules),
                "{\"facts\":{\"body\":{}}}");
        assertEquals(List.of("body.amount"), out);
    }

    @Test
    void matchedRuleWithFieldPresent_empty() {
        String rules = "{\"rules\":[{\"id\":\"r\",\"condition\":{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":0}}]}";
        List<String> out = SchemaDrift.computeMissingFields(
                List.of(matched("r")),
                SchemaDrift.indexConditions(rules),
                "{\"facts\":{\"body\":{\"amount\":5}}}");
        assertTrue(out.isEmpty());
    }

    @Test
    void walksNestedAllAnyNot() {
        String rules = """
            {"rules":[{"id":"r","condition":{
              "any":[
                {"all":[
                  {"field":"body.amount","operator":"gt","value":0},
                  {"field":"body.country","operator":"in","value":["US"]}
                ]},
                {"not":{"field":"headers.x-api-key","operator":"exists"}}
              ]
            }}]}""";
        List<String> out = SchemaDrift.computeMissingFields(
                List.of(matched("r")),
                SchemaDrift.indexConditions(rules),
                "{\"facts\":{\"body\":{\"amount\":5},\"headers\":{}}}");
        assertEquals(List.of("body.country", "headers.x-api-key"), out);
    }

    @Test
    void dedupAcrossMatchedRules() {
        String rules = """
            {"rules":[
              {"id":"r1","condition":{"field":"body.amount","operator":"gt","value":0}},
              {"id":"r2","condition":{"field":"body.amount","operator":"lt","value":10}}
            ]}""";
        List<String> out = SchemaDrift.computeMissingFields(
                List.of(matched("r1"), matched("r2")),
                SchemaDrift.indexConditions(rules),
                "{\"facts\":{\"body\":{}}}");
        assertEquals(List.of("body.amount"), out);
    }

    @Test
    void resultsAreSortedAlphabetically() {
        String rules = """
            {"rules":[{"id":"r","condition":{"all":[
              {"field":"headers.x-zeta","operator":"exists"},
              {"field":"body.alpha","operator":"exists"},
              {"field":"query.middle","operator":"exists"}
            ]}}]}""";
        List<String> out = SchemaDrift.computeMissingFields(
                List.of(matched("r")),
                SchemaDrift.indexConditions(rules),
                "{\"facts\":{\"body\":{},\"headers\":{},\"query\":{}}}");
        assertEquals(List.of("body.alpha", "headers.x-zeta", "query.middle"), out);
    }

    @Test
    void everyLeafOperatorTreatedTheSame() {
        String rules = """
            {"rules":[{"id":"r","condition":{"any":[
              {"field":"body.a","operator":"eq","value":1},
              {"field":"body.b","operator":"neq","value":1},
              {"field":"body.c","operator":"gt","value":0},
              {"field":"body.d","operator":"lt","value":0},
              {"field":"body.e","operator":"gte","value":0},
              {"field":"body.f","operator":"lte","value":0},
              {"field":"body.g","operator":"in","value":[1]},
              {"field":"body.h","operator":"not_in","value":[1]},
              {"field":"body.i","operator":"exists"},
              {"field":"body.j","operator":"not_exists"}
            ]}}]}""";
        List<String> out = SchemaDrift.computeMissingFields(
                List.of(matched("r")),
                SchemaDrift.indexConditions(rules),
                "{\"facts\":{\"body\":{}}}");
        assertEquals(List.of(
                "body.a","body.b","body.c","body.d","body.e",
                "body.f","body.g","body.h","body.i","body.j"), out);
    }

    @Test
    void matchedRuleNotInIndexIsIgnored() {
        List<String> out = SchemaDrift.computeMissingFields(
                List.of(matched("ghost")),
                Map.of(),
                "{\"facts\":{}}");
        assertTrue(out.isEmpty());
    }

    // ─── indexConditions defensive parsing ───────────────────────────────

    @Test
    void indexConditionsReturnsEmptyOnGarbage() {
        assertTrue(SchemaDrift.indexConditions("not json").isEmpty());
        assertTrue(SchemaDrift.indexConditions(null).isEmpty());
        assertTrue(SchemaDrift.indexConditions("").isEmpty());
        // Valid JSON but no rules array.
        assertTrue(SchemaDrift.indexConditions("{\"unrelated\":\"shape\"}").isEmpty());
    }
}
