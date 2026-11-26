import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { WasmBridge } from "../src/wasm-bridge.js";

const casesDir = fileURLToPath(new URL("../../spec/compliance/v1/cases/", import.meta.url));

/**
 * Cross-language parity: run every single-shot `eval-*` compliance fixture
 * through the Node wrapper + real WASM and assert the result matches the frozen
 * expectation byte-for-byte. This is the same suite the Rust engine validates,
 * so a pass proves the wrapper introduces no behavioral drift.
 */
test("Node wrapper matches the eval compliance fixtures", async () => {
  const bridge = await WasmBridge.load();
  const files = readdirSync(casesDir)
    .filter((f) => f.endsWith(".json"))
    .sort();

  let checked = 0;
  for (const file of files) {
    const fixture = JSON.parse(readFileSync(casesDir + file, "utf8"));
    // Scenario fixtures are multi-step (load/replace/status); see the scenario test below.
    if (fixture.fixture_kind || !fixture.expected_evaluation_result) continue;

    const handle = bridge.createEngine();
    const load = bridge.loadRules(handle, JSON.stringify(fixture.ruleset));
    assert.equal(load.ok, true, `${file}: ruleset should compile`);

    const result = bridge.evaluate(handle, JSON.stringify(fixture.evaluation_input));
    assert.deepEqual(result, fixture.expected_evaluation_result, file);

    bridge.destroyEngine(handle);
    checked++;
  }

  // Exact count — catches silent skips and surfaces drift the moment a
  // fixture is added or removed. The canonical set is documented in
  // docs/cross-language-parity.md.
  assert.equal(checked, 41, `expected 41 eval-* fixtures; checked ${checked}`);
});

/**
 * Cross-language parity: replay the multi-step `scenario` fixtures (load_rules /
 * evaluate / get_status) through the Node bridge and assert each step matches the
 * frozen expectation. Mirrors the Rust and Java scenario harnesses, so all three
 * exercise the SAME load/atomic-replacement/status fixtures — not just eval.
 */
test("Node wrapper replays the scenario compliance fixtures", async () => {
  const bridge = await WasmBridge.load();
  const files = readdirSync(casesDir)
    .filter((f) => f.endsWith(".json"))
    .sort();

  let scenarios = 0;
  let steps = 0;
  for (const file of files) {
    const fixture = JSON.parse(readFileSync(casesDir + file, "utf8"));
    if (fixture.fixture_kind !== "scenario") continue;
    scenarios++;

    const handle = bridge.createEngine(); // fresh engine per scenario
    for (const step of fixture.steps) {
      switch (step.op) {
        case "load_rules": {
          const actual = bridge.loadRules(handle, JSON.stringify(step.ruleset));
          assert.deepEqual(actual, step.expected_load_rules_result, `${file} (load_rules)`);
          break;
        }
        case "evaluate": {
          const actual = bridge.evaluate(handle, JSON.stringify(step.evaluation_input));
          assert.deepEqual(actual, step.expected_evaluation_result, `${file} (evaluate)`);
          break;
        }
        case "get_status": {
          const actual = bridge.getStatus(handle);
          assert.deepEqual(actual, step.expected_engine_status, `${file} (get_status)`);
          break;
        }
        default:
          assert.fail(`unknown op ${step.op} in ${file}`);
      }
      steps++;
    }
    bridge.destroyEngine(handle);
  }

  // Exact counts — see the eval-fixture comment above for rationale.
  assert.equal(scenarios, 9, `expected 9 scenario fixtures; found ${scenarios}`);
  assert.equal(steps, 18, `expected 18 scenario steps; replayed ${steps}`);
});
