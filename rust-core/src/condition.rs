use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Operator {
    Eq,
    Neq,
    Gt,
    Gte,
    Lt,
    Lte,
    Contains,
    NotContains,
    #[serde(rename = "in")]
    In,
    NotIn,
    Exists,
    NotExists,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ConditionLeaf {
    pub field: String,
    pub operator: Operator,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub value: Option<Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct AllBranch {
    all: Vec<ConditionNode>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct AnyBranch {
    any: Vec<ConditionNode>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct NotBranch {
    not: Box<ConditionNode>,
}

/// Boolean condition tree: `all` / `any` / `not` / leaf. Variant order matches distinct JSON keys.
#[allow(private_interfaces)]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum ConditionNode {
    All(AllBranch),
    Any(AnyBranch),
    Not(NotBranch),
    Leaf(ConditionLeaf),
}

impl ConditionNode {
    pub fn all(nodes: Vec<ConditionNode>) -> Self {
        ConditionNode::All(AllBranch { all: nodes })
    }

    pub fn any(nodes: Vec<ConditionNode>) -> Self {
        ConditionNode::Any(AnyBranch { any: nodes })
    }

    // Constructor mirroring the JSON `not` branch key; not the `std::ops::Not` operator.
    #[allow(clippy::should_implement_trait)]
    pub fn not(inner: ConditionNode) -> Self {
        ConditionNode::Not(NotBranch {
            not: Box::new(inner),
        })
    }

    pub fn leaf(leaf: ConditionLeaf) -> Self {
        ConditionNode::Leaf(leaf)
    }

    /// Evaluate this boolean tree, delegating each leaf's truth to `leaf_eval`.
    ///
    /// Tree-walking lives here because it owns the private branch structure;
    /// leaf and operator semantics (field resolution, type rules) live in the
    /// `evaluator` module. `all` over an (already non-empty) branch is true,
    /// `any` is false, matching standard boolean folding.
    pub fn evaluate(&self, leaf_eval: &dyn Fn(&ConditionLeaf) -> bool) -> bool {
        match self {
            ConditionNode::All(b) => b.all.iter().all(|n| n.evaluate(leaf_eval)),
            ConditionNode::Any(b) => b.any.iter().any(|n| n.evaluate(leaf_eval)),
            ConditionNode::Not(b) => !b.not.evaluate(leaf_eval),
            ConditionNode::Leaf(l) => leaf_eval(l),
        }
    }

    /// Number of leaf comparisons in this tree. Branch nodes (`all`/`any`/`not`)
    /// are structure, not conditions; used to enforce the per-rule condition cap.
    pub fn leaf_count(&self) -> usize {
        match self {
            ConditionNode::All(b) => b.all.iter().map(Self::leaf_count).sum(),
            ConditionNode::Any(b) => b.any.iter().map(Self::leaf_count).sum(),
            ConditionNode::Not(b) => b.not.leaf_count(),
            ConditionNode::Leaf(_) => 1,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_all_any_not_leaf_snippets() {
        let j = r#"{"all":[{"field":"body.amount","operator":"gt","value":0},{"any":[{"field":"body.country","operator":"eq","value":"IN"},{"field":"body.country","operator":"eq","value":"US"}]},{"not":{"field":"body.status","operator":"eq","value":"blocked"}}]}"#;
        let n: ConditionNode = serde_json::from_str(j).unwrap();
        let j2 = serde_json::to_string(&n).unwrap();
        let n2: ConditionNode = serde_json::from_str(&j2).unwrap();
        assert_eq!(n, n2);
    }
}
