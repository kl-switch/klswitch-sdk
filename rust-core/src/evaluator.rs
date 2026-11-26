//! Phase 5 — selector lookup + condition evaluation.
//!
//! Given a set of [`CompiledRule`]s (from `compiler`) and an [`EvaluationInput`],
//! shortlist candidates by exact `method`/`path`, evaluate each enabled
//! candidate's condition against the facts, and return all matches ordered by
//! priority-descending then declaration order.
//!
//! Frozen semantics (`docs/rule-model.md`):
//! - §7.1 disabled rules are skipped before condition evaluation,
//! - §7.2 a missing field makes a leaf false for every operator except
//!   `exists` / `not_exists`,
//! - §5.1 no implicit type coercion — a present-but-wrong-type field is false
//!   for comparison operators,
//! - §7.4 results are priority-descending, ties broken by declaration order.
//!
//! Result counts are scoped to selector candidates: `skipped` counts disabled
//! candidates, `evaluated` counts enabled candidates whose condition ran
//! (whether or not it matched); non-candidate rules count as neither.

use std::borrow::Cow;
use std::cmp::Ordering;
use std::collections::HashMap;

use serde_json::Value;

use crate::compiler::{CompiledRule, Window};
use crate::condition::{ConditionLeaf, Operator};
use crate::evaluation::{EvaluationInput, EvaluationResult, MatchedRule};
use crate::facts::Facts;

/// Two-tier selector index (rule-model §8.3):
///
/// - `exact` — `(method, path)` → rule indices. The schema-1.1 hash lookup,
///   unchanged.
/// - `patterns` — `method` → rule indices for pattern rules, scanned linearly.
///   Bucketing by method keeps GET traffic from scanning POST patterns.
///
/// Both tiers are built by iterating rules in declaration order, so every bucket
/// is ascending — which is what lets [`SelectorIndex::candidates`] merge the two
/// while preserving the §7.4 declaration-order tie-break.
#[derive(Debug)]
pub struct SelectorIndex {
    exact: HashMap<(String, String), Vec<usize>>,
    patterns: HashMap<String, Vec<usize>>,
}

impl SelectorIndex {
    /// Build the index from a compiled rule slice. Indices follow declaration
    /// order, so candidate buckets are naturally in declaration order.
    pub fn build(rules: &[CompiledRule]) -> Self {
        let mut exact: HashMap<(String, String), Vec<usize>> = HashMap::new();
        let mut patterns: HashMap<String, Vec<usize>> = HashMap::new();
        for (i, rule) in rules.iter().enumerate() {
            match rule.matcher.exact_key() {
                Some(path) => exact.entry((rule.method.clone(), path.to_string())).or_default().push(i),
                None => patterns.entry(rule.method.clone()).or_default().push(i),
            }
        }
        SelectorIndex { exact, patterns }
    }

    /// Candidate rule indices for `(method, path)`, in declaration order.
    ///
    /// Returns a borrowed slice whenever no pattern rule contributes — so a
    /// ruleset with no patterns allocates nothing here, exactly as before
    /// patterns existed. `rules` must be the slice this index was built from.
    fn candidates<'a>(&'a self, method: &str, path: &str, rules: &[CompiledRule]) -> Cow<'a, [usize]> {
        let exact = self
            .exact
            .get(&(method.to_string(), path.to_string()))
            .map(Vec::as_slice)
            .unwrap_or_default();

        let Some(pattern_ids) = self.patterns.get(method) else {
            return Cow::Borrowed(exact);
        };

        let matched: Vec<usize> = pattern_ids
            .iter()
            .copied()
            .filter(|&i| rules[i].matcher.matches(path))
            .collect();

        if matched.is_empty() {
            return Cow::Borrowed(exact);
        }
        Cow::Owned(merge_ascending(exact, &matched))
    }
}

/// Merge two ascending index lists into one ascending list.
///
/// Note this is *not* what enforces §7.4: `evaluate` sorts its matches by
/// `(priority desc, order asc)` afterwards, so result ordering would survive a
/// plain concatenation here (verified — swapping in a concat leaves the
/// ordering tests passing). The merge exists so `candidates` actually returns
/// what it claims to, declaration order, keeping the two tiers indistinguishable
/// to callers. That matters the moment anything becomes order-sensitive before
/// the sort — a short-circuit, or a first-match-wins mode — where a
/// tier-ordered list would be wrong in a way the current tests could not catch.
fn merge_ascending(a: &[usize], b: &[usize]) -> Vec<usize> {
    let mut out = Vec::with_capacity(a.len() + b.len());
    let (mut i, mut j) = (0, 0);
    while i < a.len() && j < b.len() {
        if a[i] <= b[j] {
            out.push(a[i]);
            i += 1;
        } else {
            out.push(b[j]);
            j += 1;
        }
    }
    out.extend_from_slice(&a[i..]);
    out.extend_from_slice(&b[j..]);
    out
}

/// Evaluate `input` against `rules` using `index` (which must be built from the
/// same `rules`). Always returns `ok: true`; evaluation never errors.
pub fn evaluate(rules: &[CompiledRule], index: &SelectorIndex, input: &EvaluationInput) -> EvaluationResult {
    let facts = &input.facts;
    let mut matches: Vec<&CompiledRule> = Vec::new();
    let mut evaluated = 0i64;
    let mut skipped = 0i64;

    for &i in index.candidates(&input.selector.keys.method, &input.selector.keys.path, rules).iter() {
        let rule = &rules[i];
        if !rule.enabled {
            skipped += 1; // disabled candidate (rule-model §7.1)
            continue;
        }
        if !window_active(rule.effective.as_ref(), input.now) {
            skipped += 1; // outside its effective window — inactive (rule-model §7.1)
            continue;
        }
        evaluated += 1;
        if rule.condition.evaluate(&|leaf| eval_leaf(leaf, facts)) {
            matches.push(rule);
        }
    }

    // priority descending, ties broken by ascending declaration order (§7.4).
    matches.sort_by(|a, b| b.priority.cmp(&a.priority).then(a.order.cmp(&b.order)));

    let matched = matches
        .into_iter()
        .map(|r| MatchedRule {
            id: r.id.clone(),
            name: r.name.clone(),
            priority: r.priority,
            action: r.action.clone(),
        })
        .collect();

    EvaluationResult {
        ok: true,
        matched,
        evaluated,
        skipped,
        errors: Vec::new(),
    }
}

/// Is a rule active for the given `now`, per its effective window?
/// No window => always active. A windowed rule with no `now` in the input is
/// inactive (fail-safe: a scheduled rule never fires without a trusted clock).
/// Bounds are inclusive: `from <= now <= to` (rule-model §7).
fn window_active(effective: Option<&Window>, now: Option<i64>) -> bool {
    let window = match effective {
        None => return true,
        Some(w) => w,
    };
    let now = match now {
        Some(n) => n,
        None => return false,
    };
    window.from.is_none_or(|from| now >= from) && window.to.is_none_or(|to| now <= to)
}

/// Evaluate a single condition leaf against the facts.
fn eval_leaf(leaf: &ConditionLeaf, facts: &Facts) -> bool {
    let resolved = resolve_field(facts, &leaf.field);

    // Presence operators are the only ones that "match" on a missing field.
    match leaf.operator {
        Operator::Exists => return resolved.is_some(),
        Operator::NotExists => return resolved.is_none(),
        _ => {}
    }

    // Every other operator is false when the field is missing (§7.2, uniform —
    // including the negative operators, so they never match absent data).
    let actual = match resolved {
        Some(v) => v,
        None => return false,
    };
    let value = leaf.value.as_ref();

    match leaf.operator {
        Operator::Eq => value.is_some_and(|v| json_eq(actual, v)),
        Operator::Neq => value.is_some_and(|v| !json_eq(actual, v)),
        Operator::Gt => num_cmp(actual, value, |o| o == Ordering::Greater),
        Operator::Gte => num_cmp(actual, value, |o| o != Ordering::Less),
        Operator::Lt => num_cmp(actual, value, |o| o == Ordering::Less),
        Operator::Lte => num_cmp(actual, value, |o| o != Ordering::Greater),
        Operator::Contains => contains(actual, value),
        Operator::NotContains => !contains(actual, value),
        Operator::In => in_array(actual, value),
        Operator::NotIn => !in_array(actual, value),
        Operator::Exists | Operator::NotExists => unreachable!("handled above"),
    }
}

/// Resolve a `<bucket>.<key>[.<key>...]` field path against the fact buckets.
/// A missing key — or a missing/non-object intermediate parent — yields `None`.
/// A present key whose value is JSON `null` resolves to `Some(&Null)` (present).
fn resolve_field<'a>(facts: &'a Facts, field: &str) -> Option<&'a Value> {
    let mut parts = field.split('.');
    let bucket = match parts.next()? {
        "headers" => &facts.headers,
        "query" => &facts.query,
        "params" => &facts.params,
        "body" => &facts.body,
        "meta" => &facts.meta,
        _ => return None,
    };
    let mut current = bucket.get(parts.next()?)?;
    for part in parts {
        current = current.as_object()?.get(part)?;
    }
    Some(current)
}

/// Equality honoring JSON's single numeric type: two numbers are equal when
/// numerically equal (so `5` equals `5.0`); every other comparison requires the
/// same JSON type and value (so `5` never equals `"5"`, `true` never equals
/// `"true"`). No string<->number coercion. This keeps `eq`/`in` consistent with
/// the ordering operators, which already compare numbers by value.
///
/// Numeric comparison uses IEEE-754 `f64`; integers beyond 2^53 may lose
/// precision — a documented v1 limitation, and consistent across wrappers
/// (notably matching JS `number`).
fn json_eq(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::Number(_), Value::Number(_)) => match (a.as_f64(), b.as_f64()) {
            (Some(x), Some(y)) => x == y,
            _ => a == b,
        },
        _ => a == b,
    }
}

/// Numeric comparison with no coercion: both sides must be JSON numbers, else false.
fn num_cmp(actual: &Value, value: Option<&Value>, pred: impl Fn(Ordering) -> bool) -> bool {
    match (actual.as_f64(), value.and_then(Value::as_f64)) {
        (Some(a), Some(b)) => a.partial_cmp(&b).is_some_and(pred),
        _ => false,
    }
}

/// `contains`: substring for string fields, element membership for array fields,
/// false for any other field type (§5.1).
fn contains(actual: &Value, value: Option<&Value>) -> bool {
    let needle = match value {
        Some(v) => v,
        None => return false,
    };
    match actual {
        Value::String(s) => needle.as_str().is_some_and(|n| s.contains(n)),
        Value::Array(items) => items.iter().any(|e| json_eq(e, needle)),
        _ => false,
    }
}

/// `in`: field value is a member of the `value` array (value guaranteed array by compiler).
fn in_array(actual: &Value, value: Option<&Value>) -> bool {
    matches!(value, Some(Value::Array(items)) if items.iter().any(|e| json_eq(e, actual)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::compile_ruleset;

    fn rules(json: &str) -> Vec<CompiledRule> {
        compile_ruleset(json).compiled.expect("compiles").rules
    }

    fn input(method: &str, path: &str, body: serde_json::Value) -> EvaluationInput {
        let v = serde_json::json!({
            "selector": {"kind": "api", "keys": {"method": method, "path": path}},
            "facts": {"headers": {}, "query": {}, "params": {}, "body": body, "meta": {}}
        });
        serde_json::from_value(v).unwrap()
    }

    fn eval(json: &str, inp: &EvaluationInput) -> EvaluationResult {
        let rs = rules(json);
        let idx = SelectorIndex::build(&rs);
        evaluate(&rs, &idx, inp)
    }

    const ONE: &str = r#"{"schema_version":"1.0","rules_version":1,"rules":[
        {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
         "enabled":true,"priority":5,"condition":{"all":[{"field":"body.amount","operator":"gt","value":100}]},
         "action":{"type":"block"}}]}"#;

    /// Two rules on the same method, both priority 10 — one pattern, one exact —
    /// declared in the given order. Used to pin the merge ordering (§7.4).
    fn tie_ruleset(first_path: &str, second_path: &str) -> String {
        format!(
            r#"{{"schema_version":"1.2","rules_version":1,"rules":[
            {{"id":"first","name":"F","selector":{{"kind":"api","keys":{{"method":"GET","path":"{first_path}"}}}},
             "enabled":true,"priority":10,"condition":{{"field":"body.x","operator":"exists"}},"action":{{"type":"a"}}}},
            {{"id":"second","name":"S","selector":{{"kind":"api","keys":{{"method":"GET","path":"{second_path}"}}}},
             "enabled":true,"priority":10,"condition":{{"field":"body.x","operator":"exists"}},"action":{{"type":"a"}}}}]}}"#
        )
    }

    #[test]
    fn equal_priority_ties_break_by_declaration_order_across_both_tiers() {
        // §7.4 must hold when the two matches come from different index tiers.
        // The guarantee comes from the sort in `evaluate`, not from the tier
        // merge — so this passes with either, and is here to pin the contract
        // rather than to guard the merge.
        // Declared pattern-then-exact:
        let r = eval(&tie_ruleset("/v1/*", "/v1/a"), &input("GET", "/v1/a", serde_json::json!({"x": 1})));
        assert_eq!(
            r.matched.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(),
            vec!["first", "second"],
        );

        // Declared exact-then-pattern — the order must flip with the declaration,
        // not stay pinned to whichever tier the index happens to visit first.
        let r = eval(&tie_ruleset("/v1/a", "/v1/*"), &input("GET", "/v1/a", serde_json::json!({"x": 1})));
        assert_eq!(
            r.matched.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(),
            vec!["first", "second"],
        );
    }

    #[test]
    fn pattern_and_exact_rules_both_match_and_priority_wins() {
        // Specificity must NOT outrank priority (§8.2): the exact rule is more
        // specific but lower priority, so it comes second.
        let json = r#"{"schema_version":"1.2","rules_version":1,"rules":[
            {"id":"exact","name":"E","selector":{"kind":"api","keys":{"method":"GET","path":"/v1/a"}},
             "enabled":true,"priority":1,"condition":{"field":"body.x","operator":"exists"},"action":{"type":"a"}},
            {"id":"pattern","name":"P","selector":{"kind":"api","keys":{"method":"GET","path":"/v1/*"}},
             "enabled":true,"priority":99,"condition":{"field":"body.x","operator":"exists"},"action":{"type":"a"}}]}"#;
        let r = eval(json, &input("GET", "/v1/a", serde_json::json!({"x": 1})));
        assert_eq!(
            r.matched.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(),
            vec!["pattern", "exact"],
            "priority decides; being more specific does not promote a rule",
        );
        assert_eq!(r.evaluated, 2);
    }

    #[test]
    fn patterns_are_scoped_to_their_method() {
        let json = r#"{"schema_version":"1.2","rules_version":1,"rules":[
            {"id":"p","name":"P","selector":{"kind":"api","keys":{"method":"POST","path":"/v1/**"}},
             "enabled":true,"condition":{"field":"body.x","operator":"exists"},"action":{"type":"a"}}]}"#;
        let hit = eval(json, &input("POST", "/v1/a", serde_json::json!({"x": 1})));
        assert_eq!(hit.matched.len(), 1);
        let miss = eval(json, &input("GET", "/v1/a", serde_json::json!({"x": 1})));
        assert!(miss.matched.is_empty(), "a POST pattern must not be a GET candidate");
        assert_eq!(miss.evaluated, 0, "and must not even be counted as evaluated");
    }

    #[test]
    fn a_disabled_pattern_rule_is_skipped_on_every_route_it_covers() {
        // Counter semantics: `skipped` is scoped to candidates, and a pattern
        // widens the candidate set — worth pinning, since the console reads these.
        let json = r#"{"schema_version":"1.2","rules_version":1,"rules":[
            {"id":"off","name":"O","selector":{"kind":"api","keys":{"method":"GET","path":"/v1/**"}},
             "enabled":false,"condition":{"field":"body.x","operator":"exists"},"action":{"type":"a"}}]}"#;
        for path in ["/v1", "/v1/a", "/v1/a/b"] {
            let r = eval(json, &input("GET", path, serde_json::json!({"x": 1})));
            assert_eq!(r.skipped, 1, "disabled pattern candidate at {path}");
            assert_eq!(r.evaluated, 0);
        }
    }

    #[test]
    fn a_malformed_pattern_skips_only_its_own_rule() {
        let json = r#"{"schema_version":"1.2","rules_version":1,"rules":[
            {"id":"bad","name":"B","selector":{"kind":"api","keys":{"method":"GET","path":"/v1/**/x"}},
             "enabled":true,"condition":{"field":"body.x","operator":"exists"},"action":{"type":"a"}},
            {"id":"good","name":"G","selector":{"kind":"api","keys":{"method":"GET","path":"/v1/a"}},
             "enabled":true,"condition":{"field":"body.x","operator":"exists"},"action":{"type":"a"}}]}"#;
        let outcome = compile_ruleset(json);
        assert!(outcome.result.ok, "the ruleset still activates");
        assert_eq!(outcome.result.compiled_rule_count, 1);
        assert_eq!(outcome.result.skipped_rule_count, 1);
        assert_eq!(outcome.result.errors[0].code, "INVALID_SELECTOR_PATTERN");
        assert_eq!(outcome.result.errors[0].rule_id.as_deref(), Some("bad"));
    }

    #[test]
    fn gt_on_string_is_false_no_coercion() {
        // body.amount is "200" (string) — must NOT coerce to a number.
        let r = eval(ONE, &input("POST", "/p", serde_json::json!({"amount": "200"})));
        assert_eq!(r.evaluated, 1);
        assert!(r.matched.is_empty(), "string vs gt must be false");
    }

    #[test]
    fn eq_int_matches_float_same_value() {
        // JSON has one number type: 5 and 5.0 are numerically equal, and this
        // must agree with the ordering operators (which compare by value).
        let json = r#"{"schema_version":"1.0","rules_version":1,"rules":[
            {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"condition":{"field":"body.v","operator":"eq","value":5},"action":{"type":"a"}}]}"#;
        let r = eval(json, &input("POST", "/p", serde_json::json!({"v": 5.0})));
        assert_eq!(r.matched.len(), 1, "5 must equal 5.0 (single JSON number type)");
    }

    #[test]
    fn in_matches_int_against_float_member() {
        let json = r#"{"schema_version":"1.0","rules_version":1,"rules":[
            {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"condition":{"field":"body.v","operator":"in","value":[1.0,2.0,3.0]},"action":{"type":"a"}}]}"#;
        let r = eval(json, &input("POST", "/p", serde_json::json!({"v": 2})));
        assert_eq!(r.matched.len(), 1, "2 must be a member of [1.0,2.0,3.0]");
    }

    #[test]
    fn eq_number_vs_string_is_false() {
        let json = r#"{"schema_version":"1.0","rules_version":1,"rules":[
            {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"condition":{"field":"body.v","operator":"eq","value":5},"action":{"type":"a"}}]}"#;
        let r = eval(json, &input("POST", "/p", serde_json::json!({"v": "5"})));
        assert!(r.matched.is_empty(), "5 (number) must not equal \"5\" (string)");
    }

    #[test]
    fn neq_on_missing_field_is_false() {
        // Uniform missing => false, even for the negative operator (§7.2).
        let json = r#"{"schema_version":"1.0","rules_version":1,"rules":[
            {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"condition":{"field":"body.missing","operator":"neq","value":"x"},"action":{"type":"a"}}]}"#;
        let r = eval(json, &input("POST", "/p", serde_json::json!({})));
        assert_eq!(r.evaluated, 1);
        assert!(r.matched.is_empty(), "neq on a missing field must be false, not true");
    }

    #[test]
    fn not_contains_on_wrong_type_is_true() {
        // Present field of wrong type: contains=false, so not_contains=true (§5.1).
        let json = r#"{"schema_version":"1.0","rules_version":1,"rules":[
            {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"condition":{"field":"body.n","operator":"not_contains","value":"x"},"action":{"type":"a"}}]}"#;
        let r = eval(json, &input("POST", "/p", serde_json::json!({"n": 42})));
        assert_eq!(r.matched.len(), 1);
    }

    #[test]
    fn missing_intermediate_parent_is_missing() {
        // body.user.id with no body.user => missing => gt false.
        let json = r#"{"schema_version":"1.0","rules_version":1,"rules":[
            {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"condition":{"field":"body.user.id","operator":"gt","value":0},"action":{"type":"a"}}]}"#;
        let r = eval(json, &input("POST", "/p", serde_json::json!({"account": {}})));
        assert!(r.matched.is_empty());
    }

    #[test]
    fn nested_path_resolves_when_present() {
        let json = r#"{"schema_version":"1.0","rules_version":1,"rules":[
            {"id":"r","name":"R","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"condition":{"field":"body.user.id","operator":"eq","value":7},"action":{"type":"a"}}]}"#;
        let r = eval(json, &input("POST", "/p", serde_json::json!({"user": {"id": 7}})));
        assert_eq!(r.matched.len(), 1);
    }

    #[test]
    fn equal_priority_breaks_by_declaration_order() {
        let json = r#"{"schema_version":"1.0","rules_version":1,"rules":[
            {"id":"zeta","name":"Z","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"priority":100,"condition":{"field":"body.a","operator":"exists"},"action":{"type":"a"}},
            {"id":"alpha","name":"A","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"priority":100,"condition":{"field":"body.a","operator":"exists"},"action":{"type":"a"}}]}"#;
        let r = eval(json, &input("POST", "/p", serde_json::json!({"a": 1})));
        let ids: Vec<_> = r.matched.iter().map(|m| m.id.as_str()).collect();
        assert_eq!(ids, ["zeta", "alpha"], "ties keep declaration order");
    }

    #[test]
    fn higher_priority_comes_first() {
        let json = r#"{"schema_version":"1.0","rules_version":1,"rules":[
            {"id":"low","name":"L","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"priority":1,"condition":{"field":"body.a","operator":"exists"},"action":{"type":"a"}},
            {"id":"high","name":"H","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
             "enabled":true,"priority":9,"condition":{"field":"body.a","operator":"exists"},"action":{"type":"a"}}]}"#;
        let r = eval(json, &input("POST", "/p", serde_json::json!({"a": 1})));
        let ids: Vec<_> = r.matched.iter().map(|m| m.id.as_str()).collect();
        assert_eq!(ids, ["high", "low"]);
    }

    #[test]
    fn selector_miss_yields_no_candidates() {
        let r = eval(ONE, &input("GET", "/other", serde_json::json!({"amount": 200})));
        assert_eq!((r.evaluated, r.skipped, r.matched.len()), (0, 0, 0));
    }

    // ── effective window gating ────────────────────────────────────────────
    // A rule active only within [1000, 2000] (epoch millis).
    const WINDOWED: &str = r#"{"schema_version":"1.0","rules_version":1,"rules":[
        {"id":"w","name":"W","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
         "enabled":true,"effective":{"from":1000,"to":2000},
         "condition":{"field":"body.a","operator":"exists"},"action":{"type":"block"}}]}"#;

    fn input_now(now: Option<i64>) -> EvaluationInput {
        let mut v = serde_json::json!({
            "selector": {"kind": "api", "keys": {"method": "POST", "path": "/p"}},
            "facts": {"headers": {}, "query": {}, "params": {}, "body": {"a": 1}, "meta": {}}
        });
        if let Some(n) = now {
            v["now"] = serde_json::json!(n);
        }
        serde_json::from_value(v).unwrap()
    }

    #[test]
    fn window_matches_inside() {
        let r = eval(WINDOWED, &input_now(Some(1500)));
        assert_eq!((r.evaluated, r.skipped, r.matched.len()), (1, 0, 1));
    }

    #[test]
    fn window_skips_before_and_after() {
        let before = eval(WINDOWED, &input_now(Some(999)));
        assert_eq!((before.evaluated, before.skipped, before.matched.len()), (0, 1, 0), "before from");
        let after = eval(WINDOWED, &input_now(Some(2001)));
        assert_eq!((after.evaluated, after.skipped, after.matched.len()), (0, 1, 0), "after to");
    }

    #[test]
    fn window_bounds_are_inclusive() {
        assert_eq!(eval(WINDOWED, &input_now(Some(1000))).matched.len(), 1, "now == from matches");
        assert_eq!(eval(WINDOWED, &input_now(Some(2000))).matched.len(), 1, "now == to matches");
    }

    #[test]
    fn windowed_rule_without_now_is_inactive() {
        // Fail-safe: a scheduled rule never fires when the input carries no clock.
        let r = eval(WINDOWED, &input_now(None));
        assert_eq!((r.evaluated, r.skipped, r.matched.len()), (0, 1, 0));
    }

    #[test]
    fn unwindowed_rule_ignores_now() {
        // ONE has no effective window; it must match regardless of now (present or absent).
        let with_now = eval(ONE, &{
            let mut i = input("POST", "/p", serde_json::json!({"amount": 200}));
            i.now = Some(42);
            i
        });
        assert_eq!(with_now.matched.len(), 1, "no window => now irrelevant");
    }
}
