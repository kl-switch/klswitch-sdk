use serde::{Deserialize, Serialize};

use crate::actions::Action;
use crate::errors::ErrorInfo;
use crate::facts::Facts;
use crate::selector::Selector;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EvaluationInput {
    pub selector: Selector,
    pub facts: Facts,
    /// Trusted current time (epoch millis) supplied by the wrapper; the engine
    /// has no clock. Used only to gate rules that carry an `effective` window.
    /// Absent for inputs without any windowed rules (rule-model §7).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub now: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MatchedRule {
    pub id: String,
    pub name: String,
    pub priority: i64,
    pub action: Action,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EvaluationResult {
    pub ok: bool,
    pub matched: Vec<MatchedRule>,
    pub evaluated: i64,
    pub skipped: i64,
    pub errors: Vec<ErrorInfo>,
}
