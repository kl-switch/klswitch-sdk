package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Cross-language parity: run the canonical compliance fixtures through the Java
 * wrapper + real WASM and assert results match the frozen expectations. This is
 * the same suite the Rust engine and the Node wrapper validate, so a pass proves
 * Java behaves identically on the shared engine.
 */
class ComplianceFixturesTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final File CASES = new File("../spec/compliance/v1/cases");

    private static File[] caseFiles() {
        File[] files = CASES.listFiles((dir, name) -> name.endsWith(".json"));
        assertNotNull(files, "compliance cases dir not found: " + CASES.getAbsolutePath());
        Arrays.sort(files);
        return files;
    }

    @Test
    void evalFixturesMatch() throws Exception {
        WasmBridge bridge = WasmBridge.loadBundled();
        int checked = 0;
        for (File file : caseFiles()) {
            JsonNode fx = M.readTree(file);
            // Single-shot eval fixtures only; scenario fixtures are multi-step.
            if (fx.has("fixture_kind") || !fx.has("expected_evaluation_result")) {
                continue;
            }
            int handle = bridge.createEngine();
            String load = bridge.loadRules(handle, fx.get("ruleset").toString());
            assertTrue(M.readTree(load).get("ok").asBoolean(), file.getName() + ": ruleset should compile");

            String result = bridge.evaluate(handle, fx.get("evaluation_input").toString());
            assertEquals(fx.get("expected_evaluation_result"), M.readTree(result), file.getName());

            bridge.destroyEngine(handle);
            checked++;
        }
        // Exact count — catches silent skips and surfaces drift the moment a
        // fixture is added or removed. Canonical set in docs/cross-language-parity.md.
        assertEquals(41, checked, "expected 41 eval-* fixtures; checked " + checked);
    }

    @Test
    void scenarioFixturesReplay() throws Exception {
        int scenarios = 0;
        int steps = 0;
        for (File file : caseFiles()) {
            JsonNode fx = M.readTree(file);
            if (!"scenario".equals(fx.path("fixture_kind").asText())) {
                continue;
            }
            scenarios++;
            WasmBridge bridge = WasmBridge.loadBundled();
            int handle = bridge.createEngine();
            for (JsonNode step : fx.get("steps")) {
                String op = step.get("op").asText();
                switch (op) {
                    case "load_rules" -> {
                        String actual = bridge.loadRules(handle, step.get("ruleset").toString());
                        assertEquals(step.get("expected_load_rules_result"), M.readTree(actual), file.getName());
                    }
                    case "evaluate" -> {
                        String actual = bridge.evaluate(handle, step.get("evaluation_input").toString());
                        assertEquals(step.get("expected_evaluation_result"), M.readTree(actual), file.getName());
                    }
                    case "get_status" -> {
                        String actual = bridge.getStatus(handle);
                        assertEquals(step.get("expected_engine_status"), M.readTree(actual), file.getName());
                    }
                    default -> fail("unknown op " + op + " in " + file.getName());
                }
                steps++;
            }
            bridge.destroyEngine(handle);
        }
        // Exact counts — see the eval-fixture comment above for rationale.
        assertEquals(9, scenarios, "expected 9 scenario fixtures; found " + scenarios);
        assertEquals(18, steps, "expected 18 scenario steps; replayed " + steps);
    }
}
