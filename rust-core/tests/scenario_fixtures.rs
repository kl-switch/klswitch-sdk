//! Phase 6 — drive the scenario compliance fixtures through the stateful engine.
//!
//! Each `fixture_kind: "scenario"` file in `spec/compliance/v1/cases/` is replayed
//! step by step against one `Engine` instance: `load_rules` / `evaluate` /
//! `get_status`, asserting the expected result of each step. This exercises
//! Phases 4–6 together — compile, atomic swap, last-known-good preservation,
//! evaluation against active state, and status reporting.

use std::fs;
use std::path::PathBuf;

use klswitch_core::{Engine, EngineStatus, EvaluationInput, EvaluationResult, LoadRulesResult};
use serde_json::Value;

fn cases_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../spec/compliance/v1/cases")
}

#[test]
fn scenario_fixtures_replay_through_engine() {
    let mut steps_checked = 0usize;
    let mut scenarios = 0usize;

    let mut files: Vec<PathBuf> = fs::read_dir(cases_dir())
        .expect("read cases dir")
        .map(|e| e.expect("dir entry").path())
        .filter(|p| p.extension().map(|x| x == "json").unwrap_or(false))
        .collect();
    files.sort();

    for path in files {
        let label = path.file_name().unwrap().to_string_lossy().into_owned();
        let raw = fs::read_to_string(&path).expect("read fixture");
        let fixture: Value = serde_json::from_str(&raw).unwrap_or_else(|e| panic!("parse {label}: {e}"));

        if fixture.get("fixture_kind").and_then(Value::as_str) != Some("scenario") {
            continue;
        }
        scenarios += 1;

        let steps = fixture.get("steps").and_then(Value::as_array).expect("scenario has steps");
        let mut engine = Engine::new();

        for (i, step) in steps.iter().enumerate() {
            let where_ = format!("{label} step {i}");
            match step.get("op").and_then(Value::as_str) {
                Some("load_rules") => {
                    let ruleset = serde_json::to_string(&step["ruleset"]).expect("reserialize ruleset");
                    let expected: LoadRulesResult =
                        serde_json::from_value(step["expected_load_rules_result"].clone())
                            .unwrap_or_else(|e| panic!("{where_}: expected load result: {e}"));
                    assert_eq!(engine.load_rules(&ruleset), expected, "{where_} (load_rules)");
                }
                Some("evaluate") => {
                    let input: EvaluationInput = serde_json::from_value(step["evaluation_input"].clone())
                        .unwrap_or_else(|e| panic!("{where_}: evaluation_input: {e}"));
                    let expected: EvaluationResult =
                        serde_json::from_value(step["expected_evaluation_result"].clone())
                            .unwrap_or_else(|e| panic!("{where_}: expected eval result: {e}"));
                    assert_eq!(engine.evaluate(&input), expected, "{where_} (evaluate)");
                }
                Some("get_status") => {
                    let expected: EngineStatus =
                        serde_json::from_value(step["expected_engine_status"].clone())
                            .unwrap_or_else(|e| panic!("{where_}: expected status: {e}"));
                    assert_eq!(engine.status(), expected, "{where_} (get_status)");
                }
                other => panic!("{where_}: unknown op {other:?}"),
            }
            steps_checked += 1;
        }
    }

    assert!(scenarios >= 8, "expected the scenario fixtures, found {scenarios}");
    assert!(steps_checked >= 15, "expected several steps, checked {steps_checked}");
    eprintln!("replayed {scenarios} scenarios / {steps_checked} steps through the engine");
}
