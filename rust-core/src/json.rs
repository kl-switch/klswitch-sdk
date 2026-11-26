//! Thin parse helpers returning `serde_json::Error`.

use crate::evaluation::{EvaluationInput, EvaluationResult};
use crate::load_result::LoadRulesResult;
use crate::rules::RuleSetPayload;
use crate::status::EngineStatus;

pub fn parse_rule_set(json: &str) -> Result<RuleSetPayload, serde_json::Error> {
    serde_json::from_str(json)
}

pub fn parse_evaluation_input(json: &str) -> Result<EvaluationInput, serde_json::Error> {
    serde_json::from_str(json)
}

pub fn parse_evaluation_result(json: &str) -> Result<EvaluationResult, serde_json::Error> {
    serde_json::from_str(json)
}

pub fn parse_load_rules_result(json: &str) -> Result<LoadRulesResult, serde_json::Error> {
    serde_json::from_str(json)
}

pub fn parse_engine_status(json: &str) -> Result<EngineStatus, serde_json::Error> {
    serde_json::from_str(json)
}
