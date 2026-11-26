use serde::{Deserialize, Serialize};
use serde_json::Map;

use crate::actions::Action;
use crate::condition::ConditionNode;
use crate::selector::Selector;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum RulesVersion {
    Integer(i64),
    Text(String),
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Rule {
    pub id: String,
    pub name: String,
    pub selector: Selector,
    pub enabled: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub priority: Option<i64>,
    pub condition: ConditionNode,
    pub action: Action,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tags: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub metadata: Option<Map<String, serde_json::Value>>,
    /// Optional effective window (epoch-millis bounds, both optional). Absent =
    /// always active. See `docs/rule-model.md` §7 and the compiler's `Window`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effective: Option<Effective>,
}

/// Optional per-rule effective window: the rule only matches while
/// `from <= now <= to` (inclusive, epoch millis; each bound optional).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Effective {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub from: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub to: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RuleSetPayload {
    pub schema_version: String,
    pub rules_version: RulesVersion,
    pub rules: Vec<Rule>,
}
