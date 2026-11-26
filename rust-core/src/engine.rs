//! Phase 6 — stateful engine: active compiled ruleset + atomic load-and-swap.
//!
//! The engine owns the currently active compiled ruleset and its selector index.
//! `load_rules` compiles a payload and, **only on success**, atomically swaps it
//! in; a failed load leaves the previous active ruleset untouched (the frozen
//! "last known good" guarantee, `docs/wasm-host-contract.md` §2, rule-model).
//! `evaluate` is read-only against the active state. One `Engine` per SDK engine
//! handle.

use crate::compiler::{compile_ruleset, CompileOutcome, CompiledRuleSet};
use crate::errors::ErrorInfo;
use crate::evaluation::{EvaluationInput, EvaluationResult};
use crate::evaluator::SelectorIndex;
use crate::load_result::LoadRulesResult;
use crate::status::EngineStatus;

/// Engine / contract schema version reported by `status` (v1). Constant — it is
/// the engine's supported version, not derived from any loaded payload.
///
/// `1.1` adds the optional per-rule `effective` window (rule-model §7.5).
/// `1.2` adds `*` / `**` selector path patterns (rule-model §8.1).
///
/// The engine still loads `1.0` and `1.1` payloads unchanged — both additions
/// are opt-in and the version is informational (no engine rejects on version).
/// Both carry the same rollout note: an older engine silently ignores what it
/// does not understand — a pre-1.1 engine drops the window, a pre-1.2 engine
/// treats `/v1/*` as a literal path that never matches — so upgrade instances
/// before publishing rules that depend on either.
pub const SCHEMA_VERSION: &str = "1.2";

/// The active ruleset together with its selector index.
#[derive(Debug)]
struct ActiveState {
    set: CompiledRuleSet,
    index: SelectorIndex,
}

/// Stateful rule engine. Holds the active compiled ruleset and the last load
/// outcome for status reporting.
#[derive(Debug, Default)]
pub struct Engine {
    active: Option<ActiveState>,
    last_load_ok: bool,
    last_error: Option<ErrorInfo>,
}

impl Engine {
    pub fn new() -> Self {
        Self::default()
    }

    /// Parse, validate, and compile `json`; on success atomically swap it in as
    /// the active ruleset. On failure the previous active ruleset is preserved.
    /// Returns the structured load result either way.
    pub fn load_rules(&mut self, json: &str) -> LoadRulesResult {
        // A compiled set is produced iff the load succeeded, so branch on it
        // directly: the swap and `last_load_ok` can never disagree.
        let CompileOutcome { result, compiled } = compile_ruleset(json);

        match compiled {
            Some(set) => {
                // Build the index, then assign in one move — evaluators only ever
                // see a fully-built active state (atomic replacement).
                let index = SelectorIndex::build(&set.rules);
                self.active = Some(ActiveState { set, index });
                self.last_load_ok = true;
                self.last_error = None;
            }
            None => {
                // Failed load: leave `self.active` untouched — last known good stays active.
                self.last_load_ok = false;
                self.last_error = result.errors.first().cloned();
            }
        }

        result
    }

    /// Evaluate `input` against the active ruleset. With no active ruleset,
    /// returns an empty (ok) result — no candidates, nothing evaluated.
    pub fn evaluate(&self, input: &EvaluationInput) -> EvaluationResult {
        match &self.active {
            Some(a) => crate::evaluator::evaluate(&a.set.rules, &a.index, input),
            None => EvaluationResult {
                ok: true,
                matched: Vec::new(),
                evaluated: 0,
                skipped: 0,
                errors: Vec::new(),
            },
        }
    }

    /// Snapshot of engine state for `get_status`.
    pub fn status(&self) -> EngineStatus {
        EngineStatus {
            initialized: true,
            schema_version: SCHEMA_VERSION.to_string(),
            active_rules_version: self.active.as_ref().map(|a| a.set.rules_version),
            rule_count: self.active.as_ref().map_or(0, |a| a.set.rules.len() as i64),
            last_load_ok: self.last_load_ok,
            last_error: self.last_error.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn eval_input(method: &str, path: &str, body: serde_json::Value) -> EvaluationInput {
        let v = serde_json::json!({
            "selector": {"kind": "api", "keys": {"method": method, "path": path}},
            "facts": {"headers": {}, "query": {}, "params": {}, "body": body, "meta": {}}
        });
        serde_json::from_value(v).unwrap()
    }

    const RULESET_V1: &str = r#"{"schema_version":"1.0","rules_version":1,"rules":[
        {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
         "enabled":true,"priority":5,"condition":{"all":[{"field":"body.amount","operator":"gt","value":100}]},
         "action":{"type":"block"}}]}"#;

    #[test]
    fn fresh_engine_status_is_empty() {
        let s = Engine::new().status();
        assert!(s.initialized);
        assert_eq!(s.schema_version, SCHEMA_VERSION, "status reports the engine's own version");
        assert_eq!(s.active_rules_version, None);
        assert_eq!(s.rule_count, 0);
        assert!(!s.last_load_ok);
        assert!(s.last_error.is_none());
    }

    #[test]
    fn evaluate_with_no_active_ruleset_is_empty() {
        let r = Engine::new().evaluate(&eval_input("POST", "/p", serde_json::json!({"amount": 200})));
        assert_eq!((r.evaluated, r.skipped, r.matched.len()), (0, 0, 0));
        assert!(r.ok);
    }

    #[test]
    fn successful_load_activates_and_status_reflects_it() {
        let mut e = Engine::new();
        let res = e.load_rules(RULESET_V1);
        assert!(res.ok);
        let s = e.status();
        assert_eq!(s.active_rules_version, Some(1));
        assert_eq!(s.rule_count, 1);
        assert!(s.last_load_ok);
        let r = e.evaluate(&eval_input("POST", "/p", serde_json::json!({"amount": 200})));
        assert_eq!(r.matched.len(), 1);
    }

    #[test]
    fn failed_load_preserves_active_and_records_error() {
        let mut e = Engine::new();
        e.load_rules(RULESET_V1);
        let bad = r#"{"schema_version":"1.0","rules_version":99,"rules":[
            {"id":"bad","name":"B","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"condition":{"all":[{"field":"body.x","operator":"bogus_op","value":1}]},
             "action":{"type":"block"}}]}"#;
        let res = e.load_rules(bad);
        assert!(!res.ok);

        let s = e.status();
        assert_eq!(s.active_rules_version, Some(1), "active ruleset preserved at v1");
        assert_eq!(s.rule_count, 1);
        assert!(!s.last_load_ok);
        assert_eq!(s.last_error.as_ref().unwrap().code, "INVALID_OPERATOR");

        // Evaluation still uses the old (good) ruleset.
        let r = e.evaluate(&eval_input("POST", "/p", serde_json::json!({"amount": 200})));
        assert_eq!(r.matched.len(), 1);
    }

    #[test]
    fn successful_load_clears_prior_error() {
        let mut e = Engine::new();
        e.load_rules(r#"{"schema_version":"1.0","rules_version":1,"rules":[
            {"id":"bad","name":"B","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"condition":{"field":"body.x","operator":"bogus_op"},"action":{"type":"a"}}]}"#);
        assert!(e.status().last_error.is_some());
        e.load_rules(RULESET_V1);
        let s = e.status();
        assert!(s.last_load_ok);
        assert!(s.last_error.is_none(), "successful load clears the prior error");
    }
}
