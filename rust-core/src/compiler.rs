//! Phase 4 — parse, validate, and compile a ruleset payload into runtime structures.
//!
//! The load pipeline up to (but not including) activation:
//!   1. parse the payload envelope,
//!   2. validate each rule and produce a runtime [`CompiledRule`],
//!   3. return a structured [`LoadRulesResult`] plus the compiled ruleset.
//!
//! This module **never** mutates active engine state — atomic activation is
//! Phase 6 (`engine.rs`). Behavior is frozen in `docs/rule-model.md` and the
//! `spec/compliance/v1/cases/load-*` / `replace-*` fixtures.
//!
//! Partial-load semantics (PRD §19.4, resolved decision §31.2):
//! - individual invalid rules are skipped and reported; the load is `ok` as long
//!   as at least one rule compiled (or the ruleset is empty),
//! - if every rule fails (compiled count 0 with rules present), or the payload
//!   cannot be parsed, the load is `ok: false` and nothing should be activated.

use std::collections::HashSet;

use serde::Deserialize;
use serde_json::{Map, Value};

use crate::actions::Action;
use crate::condition::{ConditionLeaf, ConditionNode, Operator};
use crate::errors::ErrorInfo;
use crate::load_result::LoadRulesResult;
use crate::rules::RulesVersion;
use crate::selector::PathMatcher;

/// Exact message asserted by the compliance fixtures for an unsupported operator.
const UNSUPPORTED_OPERATOR_MSG: &str = "Rule uses an operator that is not supported in v1.";

/// Maximum rules per ruleset (PRD §22.2). Exceeding this rejects the whole load.
pub const MAX_RULES: usize = 1000;

/// Maximum leaf comparisons per rule (PRD §22.2). Exceeding this skips the rule.
pub const MAX_CONDITIONS_PER_RULE: usize = 50;

/// A rule after successful validation, ready for selector indexing + evaluation.
///
/// `method` is compared exactly; the selector path is resolved to a
/// [`PathMatcher`] — an exact string, or a pattern compiled to segments once at
/// load time (rule-model §8.1). `order` is the rule's index in the submitted
/// `rules` array and is used as the declaration-order tie-break for equal
/// priorities (rule-model §7.4).
#[derive(Debug, Clone, PartialEq)]
pub struct CompiledRule {
    pub id: String,
    pub name: String,
    pub method: String,
    pub matcher: PathMatcher,
    pub enabled: bool,
    pub priority: i64,
    pub condition: ConditionNode,
    pub action: Action,
    pub order: usize,
    /// Resolved effective window; `None` = no window (always active). A window
    /// with both bounds absent normalizes to `None` here (rule-model §7).
    pub effective: Option<Window>,
}

/// A rule's resolved effective window: inclusive epoch-millis bounds, each
/// optional. `from <= now <= to` gates the match; see [`compile_effective`].
#[derive(Debug, Clone, PartialEq)]
pub struct Window {
    pub from: Option<i64>,
    pub to: Option<i64>,
}

/// A validated ruleset ready to become active.
#[derive(Debug, Clone, PartialEq)]
pub struct CompiledRuleSet {
    pub schema_version: String,
    pub rules_version: i64,
    pub rules: Vec<CompiledRule>,
}

/// Result of compiling a payload: the structured load result, plus the compiled
/// ruleset to activate **only when `result.ok` is true** (`None` otherwise).
#[derive(Debug, Clone)]
pub struct CompileOutcome {
    pub result: LoadRulesResult,
    pub compiled: Option<CompiledRuleSet>,
}

/// Lenient envelope: rules stay as raw JSON so one malformed rule does not fail
/// the whole parse (that is what enables per-rule skip-and-report). This is why
/// the load path hand-validates rules here rather than deserializing the strict
/// `rules::Rule` type — the compiler is the source of truth for "valid on load";
/// `rules::Rule` is for round-trip/consumer use and is kept aligned with it.
#[derive(Deserialize)]
struct Envelope {
    schema_version: String,
    rules_version: RulesVersion,
    rules: Vec<Value>,
}

/// Parse, validate, and compile a full ruleset payload (JSON string).
pub fn compile_ruleset(json: &str) -> CompileOutcome {
    let env: Envelope = match serde_json::from_str(json) {
        Ok(e) => e,
        Err(e) => {
            // Catastrophic: bad JSON or invalid envelope (missing/typed fields).
            return CompileOutcome {
                result: LoadRulesResult {
                    ok: false,
                    rules_version: 0,
                    rule_count: 0,
                    compiled_rule_count: 0,
                    skipped_rule_count: 0,
                    errors: vec![err(
                        "INVALID_PAYLOAD",
                        &format!("Ruleset payload could not be parsed: {e}"),
                        None,
                        None,
                    )],
                },
                compiled: None,
            };
        }
    };

    let rules_version = normalize_version(&env.rules_version);
    let rule_count = env.rules.len() as i64;

    // Payload-level cap: too many rules rejects the whole load (active preserved).
    if env.rules.len() > MAX_RULES {
        return CompileOutcome {
            result: LoadRulesResult {
                ok: false,
                rules_version,
                rule_count,
                compiled_rule_count: 0,
                skipped_rule_count: rule_count,
                errors: vec![err(
                    "RULE_LIMIT_EXCEEDED",
                    &format!("Ruleset has {} rules; the maximum is {MAX_RULES}.", env.rules.len()),
                    None,
                    None,
                )],
            },
            compiled: None,
        };
    }

    let mut compiled: Vec<CompiledRule> = Vec::new();
    let mut errors: Vec<ErrorInfo> = Vec::new();
    let mut seen_ids: HashSet<String> = HashSet::new();

    for (i, rule_value) in env.rules.iter().enumerate() {
        match compile_rule(rule_value, i) {
            Ok(rule) => {
                if !seen_ids.insert(rule.id.clone()) {
                    errors.push(err(
                        "DUPLICATE_RULE_ID",
                        "Rule id is not unique within the ruleset; later occurrence skipped.",
                        Some(rule.id),
                        Some("id"),
                    ));
                    continue;
                }
                compiled.push(rule);
            }
            Err(e) => errors.push(e),
        }
    }

    let compiled_rule_count = compiled.len() as i64;
    let skipped_rule_count = rule_count - compiled_rule_count;
    // Fail the load only if rules were present but none compiled.
    let ok = !(rule_count > 0 && compiled_rule_count == 0);

    let compiled_set = ok.then(|| CompiledRuleSet {
        schema_version: env.schema_version.clone(),
        rules_version,
        rules: compiled,
    });

    CompileOutcome {
        result: LoadRulesResult {
            ok,
            rules_version,
            rule_count,
            compiled_rule_count,
            skipped_rule_count,
            errors,
        },
        compiled: compiled_set,
    }
}

fn normalize_version(v: &RulesVersion) -> i64 {
    match v {
        RulesVersion::Integer(n) => *n,
        // The load result and status expose `rules_version` as an integer. A
        // numeric string tag parses; a non-numeric string is a v1 simplification
        // that flattens to 0 (hosts should send integers if they rely on it).
        RulesVersion::Text(s) => s.parse::<i64>().unwrap_or(0),
    }
}

fn compile_rule(value: &Value, order: usize) -> Result<CompiledRule, ErrorInfo> {
    let obj = value
        .as_object()
        .ok_or_else(|| err("INVALID_RULE", "Rule must be a JSON object.", None, None))?;

    // Extract id first so every later error can be attributed to this rule.
    let rule_id: Option<String> = obj.get("id").and_then(Value::as_str).map(str::to_string);

    let id = rule_id
        .clone()
        .ok_or_else(|| err("INVALID_RULE", "Rule requires a string id.", None, Some("id")))?;

    let name = obj
        .get("name")
        .and_then(Value::as_str)
        .ok_or_else(|| attribute(err("INVALID_RULE", "Rule requires a string name.", None, Some("name")), &rule_id))?
        .to_string();

    let enabled = obj.get("enabled").and_then(Value::as_bool).ok_or_else(|| {
        attribute(err("INVALID_RULE", "Rule requires a boolean enabled.", None, Some("enabled")), &rule_id)
    })?;

    let priority = match obj.get("priority") {
        None | Some(Value::Null) => 0, // default per rule-model
        Some(Value::Number(n)) if n.is_i64() => n.as_i64().unwrap(),
        Some(_) => {
            return Err(attribute(
                err("INVALID_RULE", "priority must be an integer.", None, Some("priority")),
                &rule_id,
            ))
        }
    };

    let effective = compile_effective(obj.get("effective")).map_err(|e| attribute(e, &rule_id))?;

    let (method, matcher) = compile_selector(obj.get("selector")).map_err(|e| attribute(e, &rule_id))?;
    let condition =
        compile_condition(obj.get("condition").unwrap_or(&Value::Null)).map_err(|e| attribute(e, &rule_id))?;

    // Per-rule cap: too many leaf comparisons skips this rule (reported, not fatal).
    let condition_count = condition.leaf_count();
    if condition_count > MAX_CONDITIONS_PER_RULE {
        return Err(attribute(
            err(
                "CONDITION_LIMIT_EXCEEDED",
                &format!("Rule has {condition_count} conditions; the maximum is {MAX_CONDITIONS_PER_RULE}."),
                None,
                Some("condition"),
            ),
            &rule_id,
        ));
    }

    let action = compile_action(obj.get("action")).map_err(|e| attribute(e, &rule_id))?;

    Ok(CompiledRule {
        id,
        name,
        method,
        matcher,
        enabled,
        priority,
        condition,
        action,
        order,
        effective,
    })
}

/// Validate the optional `effective` window and resolve its bounds. Mirrors the
/// `priority` handling (optional, integer-typed) and the leaf unknown-key check.
/// Both bounds are optional epoch millis; absent/null/`{}` = no window (`None`).
/// `from > to` is a compile-time error (skip-and-report), like an invalid priority.
fn compile_effective(value: Option<&Value>) -> Result<Option<Window>, ErrorInfo> {
    let obj = match value {
        None | Some(Value::Null) => return Ok(None),
        Some(Value::Object(m)) => m,
        Some(_) => {
            return Err(err("INVALID_RULE", "effective must be an object.", None, Some("effective")))
        }
    };

    if let Some(key) = obj.keys().find(|k| !matches!(k.as_str(), "from" | "to")) {
        return Err(err(
            "INVALID_RULE",
            &format!("effective has unexpected key '{key}'."),
            None,
            Some("effective"),
        ));
    }

    let from = effective_bound(obj.get("from"), "effective.from")?;
    let to = effective_bound(obj.get("to"), "effective.to")?;

    if let (Some(f), Some(t)) = (from, to) {
        if f > t {
            return Err(err(
                "INVALID_RULE",
                "effective.from must be less than or equal to effective.to.",
                None,
                Some("effective"),
            ));
        }
    }

    // An empty window (both bounds absent) is equivalent to no window.
    if from.is_none() && to.is_none() {
        return Ok(None);
    }
    Ok(Some(Window { from, to }))
}

/// Resolve one effective bound: absent/null = `None`; an integer = `Some`;
/// any other type is a compile-time error (mirrors the `priority` type check).
fn effective_bound(value: Option<&Value>, field: &str) -> Result<Option<i64>, ErrorInfo> {
    match value {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Number(n)) if n.is_i64() => Ok(Some(n.as_i64().unwrap())),
        Some(_) => Err(err("INVALID_RULE", &format!("{field} must be an integer."), None, Some(field))),
    }
}

/// Validate the API selector, resolving `keys.method` and compiling `keys.path`
/// into a [`PathMatcher`] (rule-model §8.1).
fn compile_selector(value: Option<&Value>) -> Result<(String, PathMatcher), ErrorInfo> {
    let obj = value
        .and_then(Value::as_object)
        .ok_or_else(|| err("INVALID_SELECTOR", "Rule requires a selector object.", None, Some("selector")))?;

    if obj.get("kind").and_then(Value::as_str) != Some("api") {
        return Err(err(
            "INVALID_SELECTOR_KIND",
            "v1 supports only selector.kind = \"api\".",
            None,
            Some("selector.kind"),
        ));
    }

    let keys = obj.get("keys").and_then(Value::as_object).ok_or_else(|| {
        err("MISSING_SELECTOR_KEY", "API selector requires keys.method and keys.path.", None, Some("selector.keys"))
    })?;

    let method = keys.get("method").and_then(Value::as_str).ok_or_else(|| {
        err("MISSING_SELECTOR_KEY", "API selector requires keys.method.", None, Some("selector.keys.method"))
    })?;
    let path = keys.get("path").and_then(Value::as_str).ok_or_else(|| {
        err("MISSING_SELECTOR_KEY", "API selector requires keys.path.", None, Some("selector.keys.path"))
    })?;

    // Normalization (uppercase method, templated path) is the wrapper's job
    // (rule-model §8); the method is stored as given. The path is compiled here
    // so a malformed pattern is caught at load time rather than silently
    // matching nothing for the life of the ruleset (§8.4).
    let matcher = PathMatcher::compile(path).map_err(|reason| {
        err("INVALID_SELECTOR_PATTERN", &reason, None, Some("selector.keys.path"))
    })?;

    Ok((method.to_string(), matcher))
}

/// Validate and build a condition tree (`all` / `any` / `not` / leaf).
fn compile_condition(value: &Value) -> Result<ConditionNode, ErrorInfo> {
    let obj = value
        .as_object()
        .ok_or_else(|| err("INVALID_CONDITION", "Condition node must be a JSON object.", None, Some("condition")))?;

    let is_branch = obj.contains_key("all") || obj.contains_key("any") || obj.contains_key("not");
    if is_branch {
        if obj.len() != 1 {
            return Err(err(
                "INVALID_CONDITION",
                "Condition branch must contain exactly one of all/any/not.",
                None,
                Some("condition"),
            ));
        }
        if let Some(all) = obj.get("all") {
            return Ok(ConditionNode::all(compile_branch(all, "all")?));
        }
        if let Some(any) = obj.get("any") {
            return Ok(ConditionNode::any(compile_branch(any, "any")?));
        }
        let not = obj.get("not").expect("branch has not");
        return Ok(ConditionNode::not(compile_condition(not)?));
    }

    compile_leaf(obj)
}

fn compile_branch(value: &Value, key: &str) -> Result<Vec<ConditionNode>, ErrorInfo> {
    let arr = value.as_array().filter(|a| !a.is_empty()).ok_or_else(|| {
        err(
            "INVALID_CONDITION",
            &format!("Condition branch '{key}' must be a non-empty array."),
            None,
            Some("condition"),
        )
    })?;
    arr.iter().map(compile_condition).collect()
}

fn compile_leaf(obj: &Map<String, Value>) -> Result<ConditionNode, ErrorInfo> {
    // Reject unknown keys, mirroring `deny_unknown_fields` on the serde leaf and
    // the single-key check on branch nodes (so a typo'd key is not silently kept).
    if let Some(key) = obj
        .keys()
        .find(|k| !matches!(k.as_str(), "field" | "operator" | "value"))
    {
        return Err(err(
            "INVALID_CONDITION",
            &format!("Condition leaf has unexpected key '{key}'."),
            None,
            Some("condition"),
        ));
    }

    let field = obj
        .get("field")
        .and_then(Value::as_str)
        .ok_or_else(|| err("INVALID_CONDITION", "Condition leaf requires a string field.", None, Some("condition.field")))?
        .to_string();

    let operator_str = obj
        .get("operator")
        .and_then(Value::as_str)
        .ok_or_else(|| err("INVALID_OPERATOR", UNSUPPORTED_OPERATOR_MSG, None, Some("condition.operator")))?;

    // Reuse the serde enum mapping (handles the `in` rename) for the supported set.
    let operator: Operator = serde_json::from_value(Value::String(operator_str.to_string()))
        .map_err(|_| err("INVALID_OPERATOR", UNSUPPORTED_OPERATOR_MSG, None, Some("condition.operator")))?;

    let value = obj.get("value").cloned();

    match operator {
        // exists / not_exists take no value; any provided value is ignored.
        Operator::Exists | Operator::NotExists => {}
        // in / not_in require an array value (frozen decision; non-array is invalid).
        Operator::In | Operator::NotIn => {
            if !matches!(value, Some(Value::Array(_))) {
                return Err(err(
                    "INVALID_VALUE",
                    "Operators in and not_in require an array value.",
                    None,
                    Some("condition.value"),
                ));
            }
        }
        // value-comparison operators require a value.
        _ => {
            if value.is_none() {
                return Err(err(
                    "MISSING_VALUE",
                    "Operator requires a comparison value.",
                    None,
                    Some("condition.value"),
                ));
            }
        }
    }

    Ok(ConditionNode::leaf(ConditionLeaf {
        field,
        operator,
        value,
    }))
}

fn compile_action(value: Option<&Value>) -> Result<Action, ErrorInfo> {
    let obj = value
        .and_then(Value::as_object)
        .ok_or_else(|| err("INVALID_ACTION", "Rule requires an action object.", None, Some("action")))?;

    let type_ = obj
        .get("type")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| err("INVALID_ACTION", "action.type must be a non-empty string.", None, Some("action.type")))?
        .to_string();

    let payload = match obj.get("payload") {
        None | Some(Value::Null) => None,
        Some(Value::Object(m)) => Some(m.clone()),
        Some(_) => {
            return Err(err("INVALID_ACTION", "action.payload must be an object.", None, Some("action.payload")))
        }
    };

    Ok(Action { type_, payload })
}

fn err(code: &str, message: &str, rule_id: Option<String>, field: Option<&str>) -> ErrorInfo {
    ErrorInfo {
        code: code.to_string(),
        message: message.to_string(),
        rule_id,
        field: field.map(str::to_string),
    }
}

/// Attach the rule id to an error raised by a nested validator that did not know it.
fn attribute(mut e: ErrorInfo, rule_id: &Option<String>) -> ErrorInfo {
    if e.rule_id.is_none() {
        e.rule_id = rule_id.clone();
    }
    e
}

#[cfg(test)]
mod tests {
    use super::*;

    fn outcome(json: &str) -> CompileOutcome {
        compile_ruleset(json)
    }

    #[test]
    fn compiles_valid_rule_with_defaults() {
        let o = outcome(
            r#"{"schema_version":"1.0","rules_version":7,"rules":[
                {"id":"r1","name":"R1","selector":{"kind":"api","keys":{"method":"GET","path":"/x"}},
                 "enabled":true,"condition":{"all":[{"field":"body.a","operator":"eq","value":1}]},
                 "action":{"type":"allow"}}]}"#,
        );
        assert!(o.result.ok);
        assert_eq!(o.result.rules_version, 7);
        assert_eq!(o.result.compiled_rule_count, 1);
        assert_eq!(o.result.skipped_rule_count, 0);
        let set = o.compiled.expect("compiled set");
        assert_eq!(set.rules[0].priority, 0, "absent priority defaults to 0");
        assert_eq!(set.rules[0].method, "GET");
        assert_eq!(set.rules[0].order, 0);
    }

    #[test]
    fn all_invalid_fails_with_invalid_operator() {
        let o = outcome(
            r#"{"schema_version":"1.0","rules_version":1,"rules":[
                {"id":"bad","name":"Bad","selector":{"kind":"api","keys":{"method":"GET","path":"/x"}},
                 "enabled":true,"priority":10,
                 "condition":{"all":[{"field":"body.x","operator":"bogus_op","value":1}]},
                 "action":{"type":"block","payload":{}}}]}"#,
        );
        assert!(!o.result.ok);
        assert_eq!(o.result.compiled_rule_count, 0);
        assert_eq!(o.result.skipped_rule_count, 1);
        assert!(o.compiled.is_none(), "nothing to activate on failed load");
        let e = &o.result.errors[0];
        assert_eq!(e.code, "INVALID_OPERATOR");
        assert_eq!(e.message, UNSUPPORTED_OPERATOR_MSG);
        assert_eq!(e.rule_id.as_deref(), Some("bad"));
        assert_eq!(e.field.as_deref(), Some("condition.operator"));
    }

    #[test]
    fn partial_invalid_keeps_valid_rule() {
        let o = outcome(
            r#"{"schema_version":"1.0","rules_version":42,"rules":[
                {"id":"good","name":"G","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
                 "enabled":true,"condition":{"all":[{"field":"body.ok","operator":"eq","value":true}]},
                 "action":{"type":"allow"}},
                {"id":"bad","name":"B","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
                 "enabled":true,"condition":{"all":[{"field":"body.x","operator":"bogus_op","value":1}]},
                 "action":{"type":"block"}}]}"#,
        );
        assert!(o.result.ok);
        assert_eq!(o.result.compiled_rule_count, 1);
        assert_eq!(o.result.skipped_rule_count, 1);
        assert_eq!(o.result.errors.len(), 1);
        assert_eq!(o.compiled.unwrap().rules[0].id, "good");
    }

    #[test]
    fn empty_ruleset_is_ok() {
        let o = outcome(r#"{"schema_version":"1.0","rules_version":1,"rules":[]}"#);
        assert!(o.result.ok);
        assert_eq!(o.result.compiled_rule_count, 0);
        assert!(o.compiled.is_some());
    }

    #[test]
    fn malformed_json_is_catastrophic() {
        let o = outcome("{ not json");
        assert!(!o.result.ok);
        assert_eq!(o.result.errors[0].code, "INVALID_PAYLOAD");
        assert!(o.compiled.is_none());
    }

    #[test]
    fn in_requires_array_value() {
        let o = outcome(
            r#"{"schema_version":"1.0","rules_version":1,"rules":[
                {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"GET","path":"/x"}},
                 "enabled":true,"condition":{"field":"body.c","operator":"in","value":"not-array"},
                 "action":{"type":"allow"}}]}"#,
        );
        assert!(!o.result.ok);
        assert_eq!(o.result.errors[0].code, "INVALID_VALUE");
    }

    #[test]
    fn bad_selector_kind_is_reported() {
        let o = outcome(
            r#"{"schema_version":"1.0","rules_version":1,"rules":[
                {"id":"r","name":"R","selector":{"kind":"event","keys":{"method":"GET","path":"/x"}},
                 "enabled":true,"condition":{"field":"body.c","operator":"exists"},
                 "action":{"type":"allow"}}]}"#,
        );
        assert!(!o.result.ok);
        assert_eq!(o.result.errors[0].code, "INVALID_SELECTOR_KIND");
    }

    fn ruleset_of_n_rules(n: usize) -> String {
        let rules: Vec<String> = (0..n)
            .map(|i| {
                format!(
                    r#"{{"id":"r{i}","name":"R","selector":{{"kind":"api","keys":{{"method":"GET","path":"/x"}}}},"enabled":true,"condition":{{"field":"body.a","operator":"exists"}},"action":{{"type":"a"}}}}"#
                )
            })
            .collect();
        format!(r#"{{"schema_version":"1.0","rules_version":1,"rules":[{}]}}"#, rules.join(","))
    }

    fn rule_of_n_conditions(n: usize) -> String {
        let leaves: Vec<String> = (0..n)
            .map(|i| format!(r#"{{"field":"body.f{i}","operator":"exists"}}"#))
            .collect();
        format!(
            r#"{{"schema_version":"1.0","rules_version":1,"rules":[
                {{"id":"r","name":"R","selector":{{"kind":"api","keys":{{"method":"GET","path":"/x"}}}},
                 "enabled":true,"condition":{{"all":[{}]}},"action":{{"type":"a"}}}}]}}"#,
            leaves.join(",")
        )
    }

    #[test]
    fn ruleset_at_rule_limit_is_accepted() {
        let o = outcome(&ruleset_of_n_rules(MAX_RULES));
        assert!(o.result.ok);
        assert_eq!(o.result.compiled_rule_count, MAX_RULES as i64);
    }

    #[test]
    fn ruleset_over_rule_limit_is_rejected_wholesale() {
        let o = outcome(&ruleset_of_n_rules(MAX_RULES + 1));
        assert!(!o.result.ok);
        assert_eq!(o.result.compiled_rule_count, 0);
        assert_eq!(o.result.errors[0].code, "RULE_LIMIT_EXCEEDED");
        assert!(o.compiled.is_none(), "active ruleset must be preserved");
    }

    #[test]
    fn rule_at_condition_limit_is_accepted() {
        let o = outcome(&rule_of_n_conditions(MAX_CONDITIONS_PER_RULE));
        assert!(o.result.ok);
        assert_eq!(o.result.compiled_rule_count, 1);
    }

    #[test]
    fn rule_over_condition_limit_is_skipped() {
        let o = outcome(&rule_of_n_conditions(MAX_CONDITIONS_PER_RULE + 1));
        assert_eq!(o.result.skipped_rule_count, 1);
        assert_eq!(o.result.errors[0].code, "CONDITION_LIMIT_EXCEEDED");
        assert_eq!(o.result.errors[0].rule_id.as_deref(), Some("r"));
    }

    #[test]
    fn leaf_with_unknown_key_is_rejected() {
        // A stray/typo'd key on a leaf must not be silently accepted.
        let o = outcome(
            r#"{"schema_version":"1.0","rules_version":1,"rules":[
                {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"GET","path":"/x"}},
                 "enabled":true,"condition":{"field":"body.a","operator":"eq","value":1,"bogus":true},
                 "action":{"type":"allow"}}]}"#,
        );
        assert!(!o.result.ok);
        assert_eq!(o.result.errors[0].code, "INVALID_CONDITION");
    }

    fn rule_with_effective(effective: &str) -> String {
        format!(
            r#"{{"schema_version":"1.0","rules_version":1,"rules":[
                {{"id":"r","name":"R","selector":{{"kind":"api","keys":{{"method":"GET","path":"/x"}}}},
                 "enabled":true,"effective":{effective},
                 "condition":{{"field":"body.a","operator":"exists"}},"action":{{"type":"a"}}}}]}}"#
        )
    }

    #[test]
    fn valid_effective_window_compiles() {
        let o = outcome(&rule_with_effective(r#"{"from":1000,"to":2000}"#));
        assert!(o.result.ok);
        let w = o.compiled.unwrap().rules[0].effective.clone().expect("window");
        assert_eq!((w.from, w.to), (Some(1000), Some(2000)));
    }

    #[test]
    fn effective_one_sided_bounds_compile() {
        let only_from = outcome(&rule_with_effective(r#"{"from":1000}"#)).compiled.unwrap();
        assert_eq!(only_from.rules[0].effective.clone().unwrap(), Window { from: Some(1000), to: None });
        let only_to = outcome(&rule_with_effective(r#"{"to":2000}"#)).compiled.unwrap();
        assert_eq!(only_to.rules[0].effective.clone().unwrap(), Window { from: None, to: Some(2000) });
    }

    #[test]
    fn empty_effective_window_is_no_window() {
        for e in [r#"{}"#, "null"] {
            let set = outcome(&rule_with_effective(e)).compiled.unwrap();
            assert_eq!(set.rules[0].effective, None, "empty/null window == always active");
        }
        // Field entirely absent is also no window.
        let o = outcome(
            r#"{"schema_version":"1.0","rules_version":1,"rules":[
                {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"GET","path":"/x"}},
                 "enabled":true,"condition":{"field":"body.a","operator":"exists"},"action":{"type":"a"}}]}"#,
        );
        assert_eq!(o.compiled.unwrap().rules[0].effective, None);
    }

    #[test]
    fn effective_from_after_to_is_rejected() {
        let o = outcome(&rule_with_effective(r#"{"from":2000,"to":1000}"#));
        assert!(!o.result.ok);
        assert_eq!(o.result.errors[0].code, "INVALID_RULE");
        assert_eq!(o.result.errors[0].field.as_deref(), Some("effective"));
        assert_eq!(o.result.errors[0].rule_id.as_deref(), Some("r"));
    }

    #[test]
    fn effective_non_integer_bound_is_rejected() {
        let o = outcome(&rule_with_effective(r#"{"from":"soon"}"#));
        assert!(!o.result.ok);
        assert_eq!(o.result.errors[0].code, "INVALID_RULE");
        assert_eq!(o.result.errors[0].field.as_deref(), Some("effective.from"));
    }

    #[test]
    fn effective_unknown_key_is_rejected() {
        let o = outcome(&rule_with_effective(r#"{"from":1,"tz":"UTC"}"#));
        assert!(!o.result.ok);
        assert_eq!(o.result.errors[0].code, "INVALID_RULE");
        assert_eq!(o.result.errors[0].field.as_deref(), Some("effective"));
    }

    #[test]
    fn duplicate_ids_skip_later_occurrence() {
        let o = outcome(
            r#"{"schema_version":"1.0","rules_version":1,"rules":[
                {"id":"dup","name":"A","selector":{"kind":"api","keys":{"method":"GET","path":"/x"}},
                 "enabled":true,"condition":{"field":"body.c","operator":"exists"},"action":{"type":"allow"}},
                {"id":"dup","name":"B","selector":{"kind":"api","keys":{"method":"GET","path":"/x"}},
                 "enabled":true,"condition":{"field":"body.c","operator":"exists"},"action":{"type":"block"}}]}"#,
        );
        assert!(o.result.ok);
        assert_eq!(o.result.compiled_rule_count, 1);
        assert_eq!(o.result.errors[0].code, "DUPLICATE_RULE_ID");
        assert_eq!(o.compiled.unwrap().rules[0].name, "A", "first occurrence kept");
    }
}
