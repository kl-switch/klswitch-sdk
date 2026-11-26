use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Facts {
    pub headers: Map<String, Value>,
    pub query: Map<String, Value>,
    pub params: Map<String, Value>,
    pub body: Map<String, Value>,
    pub meta: Map<String, Value>,
}
