//! KLSwitch v1 rule engine core.
//!
//! Domain models (serde, aligned with `spec/schemas/`), the load pipeline
//! (`compiler`), selector lookup + evaluation (`evaluator`), the stateful engine
//! with atomic replacement (`engine`), and the WASM export boundary (`exports` +
//! `memory`).

pub mod actions;
pub mod compiler;
pub mod condition;
pub mod engine;
pub mod errors;
pub mod evaluation;
pub mod evaluator;
pub mod exports;
pub mod facts;
pub mod json;
pub mod load_result;
pub mod memory;
pub mod rules;
pub mod selector;
pub mod status;

pub use actions::Action;
pub use compiler::{
    compile_ruleset, CompileOutcome, CompiledRule, CompiledRuleSet, Window,
    MAX_CONDITIONS_PER_RULE, MAX_RULES,
};
pub use condition::{ConditionLeaf, ConditionNode, Operator};
pub use engine::{Engine, SCHEMA_VERSION};
pub use errors::ErrorInfo;
pub use evaluation::{EvaluationInput, EvaluationResult, MatchedRule};
pub use evaluator::{evaluate, SelectorIndex};
pub use facts::Facts;
pub use load_result::LoadRulesResult;
pub use rules::{Rule, RuleSetPayload, RulesVersion};
pub use selector::{ApiKeys, PathMatcher, Segment, Selector, SelectorKind};
pub use status::EngineStatus;
