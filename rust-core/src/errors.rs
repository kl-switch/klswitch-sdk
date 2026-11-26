use serde::{Deserialize, Serialize};

/// Structured error (PRD §11.8). Extra JSON keys are ignored on deserialize (`additionalProperties` in schema).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ErrorInfo {
    pub code: String,
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rule_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub field: Option<String>,
}
