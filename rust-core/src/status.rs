use serde::{Deserialize, Serialize};

use crate::errors::ErrorInfo;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EngineStatus {
    pub initialized: bool,
    pub schema_version: String,
    pub active_rules_version: Option<i64>,
    pub rule_count: i64,
    pub last_load_ok: bool,
    pub last_error: Option<ErrorInfo>,
}
