use serde::{Deserialize, Serialize};
use serde_json::Map;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Action {
    #[serde(rename = "type")]
    pub type_: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub payload: Option<Map<String, serde_json::Value>>,
}
