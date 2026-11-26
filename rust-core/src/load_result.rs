use serde::{Deserialize, Serialize};

use crate::errors::ErrorInfo;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LoadRulesResult {
    pub ok: bool,
    pub rules_version: i64,
    pub rule_count: i64,
    pub compiled_rule_count: i64,
    pub skipped_rule_count: i64,
    pub errors: Vec<ErrorInfo>,
}
