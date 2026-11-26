//! Roundtrip serde against valid `spec/examples/*.json` only (see Phase 3 plan).

use std::fs;
use std::path::PathBuf;

use klswitch_core::{
    EngineStatus, ErrorInfo, EvaluationInput, EvaluationResult, LoadRulesResult, RuleSetPayload,
};

fn examples_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../spec/examples")
}

fn read_example(name: &str) -> String {
    let p = examples_dir().join(name);
    fs::read_to_string(&p).unwrap_or_else(|e| panic!("read {}: {e}", p.display()))
}

fn roundtrip_json<T>(label: &str, json: &str)
where
    T: serde::de::DeserializeOwned + serde::Serialize + PartialEq + std::fmt::Debug,
{
    let v: T = serde_json::from_str(json)
        .unwrap_or_else(|e| panic!("{label} deserialize failed: {e}\n{json}"));
    let s = serde_json::to_string(&v).expect("serialize");
    let v2: T = serde_json::from_str(&s).unwrap_or_else(|e| {
        panic!("{label} second deserialize failed: {e}\n{s}");
    });
    assert_eq!(v, v2, "{label}");
}

#[test]
fn roundtrip_ruleset_api_basic() {
    roundtrip_json::<RuleSetPayload>("ruleset-api-basic", &read_example("ruleset-api-basic.json"));
}

#[test]
fn roundtrip_ruleset_api_nested() {
    roundtrip_json::<RuleSetPayload>("ruleset-api-nested", &read_example("ruleset-api-nested.json"));
}

#[test]
fn roundtrip_ruleset_api_multi_match() {
    roundtrip_json::<RuleSetPayload>(
        "ruleset-api-multi-match",
        &read_example("ruleset-api-multi-match.json"),
    );
}

#[test]
fn roundtrip_evaluation_input_payment() {
    roundtrip_json::<EvaluationInput>(
        "evaluation-input-payment",
        &read_example("evaluation-input-payment.json"),
    );
}

#[test]
fn roundtrip_evaluation_input_auth() {
    roundtrip_json::<EvaluationInput>(
        "evaluation-input-auth",
        &read_example("evaluation-input-auth.json"),
    );
}

#[test]
fn roundtrip_ruleset_api_risk() {
    roundtrip_json::<RuleSetPayload>("ruleset-api-risk", &read_example("ruleset-api-risk.json"));
}

#[test]
fn roundtrip_evaluation_result_block() {
    roundtrip_json::<EvaluationResult>(
        "evaluation-result-block",
        &read_example("evaluation-result-block.json"),
    );
}

#[test]
fn roundtrip_evaluation_result_match_multiple() {
    roundtrip_json::<EvaluationResult>(
        "evaluation-result-match-multiple",
        &read_example("evaluation-result-match-multiple.json"),
    );
}

#[test]
fn roundtrip_load_rules_success() {
    roundtrip_json::<LoadRulesResult>("load-rules-success", &read_example("load-rules-success.json"));
}

#[test]
fn roundtrip_load_rules_failure() {
    roundtrip_json::<LoadRulesResult>("load-rules-failure", &read_example("load-rules-failure.json"));
}

#[test]
fn roundtrip_load_rules_partial_invalid() {
    roundtrip_json::<LoadRulesResult>(
        "load-rules-partial-invalid",
        &read_example("load-rules-partial-invalid.json"),
    );
}

#[test]
fn roundtrip_engine_status_basic() {
    roundtrip_json::<EngineStatus>(
        "engine-status-basic",
        &read_example("engine-status-basic.json"),
    );
}

#[test]
fn roundtrip_error_invalid_rule() {
    roundtrip_json::<ErrorInfo>("error-invalid-rule", &read_example("error-invalid-rule.json"));
}
