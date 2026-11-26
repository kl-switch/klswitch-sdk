//! Phase 5 — drive the evaluator against the canonical single-shot eval fixtures.
//!
//! For every non-scenario fixture in `spec/compliance/v1/cases/` (the `eval-*`
//! files: ruleset + evaluation_input + expected_evaluation_result), compile the
//! ruleset, build the selector index, evaluate the input, and compare the result
//! to the fixture's `expected_evaluation_result`. Scenario fixtures (multi-step
//! load/replace/status) are covered once the engine lands (Phase 6).

use std::fs;
use std::path::PathBuf;

use klswitch_core::{compile_ruleset, evaluate, EvaluationInput, EvaluationResult, SelectorIndex};
use serde_json::Value;

fn cases_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../spec/compliance/v1/cases")
}

#[test]
fn eval_fixtures_match_expected() {
    let mut checked = 0usize;

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

        // Scenario fixtures use multi-step envelopes; skip here (Phase 6).
        if fixture.get("fixture_kind").is_some() || fixture.get("expected_evaluation_result").is_none() {
            continue;
        }

        let ruleset_json = serde_json::to_string(&fixture["ruleset"]).expect("reserialize ruleset");
        let compiled = compile_ruleset(&ruleset_json)
            .compiled
            .unwrap_or_else(|| panic!("{label}: eval fixture ruleset failed to compile"));
        let index = SelectorIndex::build(&compiled.rules);

        let input: EvaluationInput = serde_json::from_value(fixture["evaluation_input"].clone())
            .unwrap_or_else(|e| panic!("{label}: evaluation_input deserialize: {e}"));
        let expected: EvaluationResult = serde_json::from_value(fixture["expected_evaluation_result"].clone())
            .unwrap_or_else(|e| panic!("{label}: expected result deserialize: {e}"));

        let actual = evaluate(&compiled.rules, &index, &input);
        assert_eq!(actual, expected, "evaluation mismatch for {label}");
        checked += 1;
    }

    assert!(checked >= 20, "expected to check the eval-* fixtures, only checked {checked}");
    eprintln!("evaluator validated against {checked} eval fixtures");
}
