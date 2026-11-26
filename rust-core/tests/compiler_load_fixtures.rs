//! Phase 4 — drive the compiler against the canonical compliance fixtures.
//!
//! For every scenario fixture in `spec/compliance/v1/cases/`, each `load_rules`
//! step's `ruleset` is compiled and the resulting `LoadRulesResult` is compared
//! to the step's `expected_load_rules_result`. This ties `compiler.rs` directly
//! to the frozen behavior contract rather than to hand-mirrored expectations.

use std::fs;
use std::path::PathBuf;

use klswitch_core::{compile_ruleset, LoadRulesResult};
use serde_json::Value;

fn cases_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../spec/compliance/v1/cases")
}

#[test]
fn load_rules_steps_match_expected_results() {
    let mut checked = 0usize;

    let mut files: Vec<PathBuf> = fs::read_dir(cases_dir())
        .expect("read cases dir")
        .map(|e| e.expect("dir entry").path())
        .filter(|p| p.extension().map(|x| x == "json").unwrap_or(false))
        .collect();
    files.sort();

    for path in files {
        let raw = fs::read_to_string(&path).expect("read fixture");
        let fixture: Value = serde_json::from_str(&raw)
            .unwrap_or_else(|e| panic!("parse {}: {e}", path.display()));

        if fixture.get("fixture_kind").and_then(Value::as_str) != Some("scenario") {
            continue;
        }
        let steps = match fixture.get("steps").and_then(Value::as_array) {
            Some(s) => s,
            None => continue,
        };

        for (i, step) in steps.iter().enumerate() {
            if step.get("op").and_then(Value::as_str) != Some("load_rules") {
                continue;
            }
            let label = format!("{}#step{i}", path.file_name().unwrap().to_string_lossy());

            let ruleset_json = serde_json::to_string(
                step.get("ruleset").unwrap_or_else(|| panic!("{label}: load_rules step missing ruleset")),
            )
            .expect("reserialize ruleset");

            let expected: LoadRulesResult = serde_json::from_value(
                step.get("expected_load_rules_result")
                    .unwrap_or_else(|| panic!("{label}: missing expected_load_rules_result"))
                    .clone(),
            )
            .unwrap_or_else(|e| panic!("{label}: expected result deserialize: {e}"));

            let actual = compile_ruleset(&ruleset_json).result;
            assert_eq!(actual, expected, "load result mismatch for {label}");
            checked += 1;
        }
    }

    assert!(checked >= 6, "expected to check several load_rules steps, only checked {checked}");
    eprintln!("compiler validated against {checked} load_rules fixture steps");
}
