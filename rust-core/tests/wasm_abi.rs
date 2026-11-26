//! Phase 7 — exercise the WASM export ABI on the host (no wasm runtime needed).
//!
//! Drives the real `ks_*` exports through the documented memory protocol: alloc
//! an input buffer, write JSON, call the export, read the length-prefixed result
//! buffer, free both. Verifies the engine lifecycle, JSON in/out, error paths
//! (invalid handle / bad input), and repeated-call stability across the boundary.

use klswitch_core::exports::{
    ks_create_engine, ks_destroy_engine, ks_evaluate, ks_get_status, ks_load_rules,
};
use klswitch_core::memory::{ks_alloc, ks_free, ks_free_result};
use serde_json::Value;

type JsonExport = unsafe extern "C" fn(u32, *const u8, usize) -> *mut u8;

/// Read a length-prefixed result buffer (`[u32 LE len][json]`) into owned bytes.
unsafe fn read_result(ptr: *mut u8) -> Vec<u8> {
    let mut len_bytes = [0u8; 4];
    std::ptr::copy_nonoverlapping(ptr, len_bytes.as_mut_ptr(), 4);
    let len = u32::from_le_bytes(len_bytes) as usize;
    let mut out = vec![0u8; len];
    std::ptr::copy_nonoverlapping(ptr.add(4), out.as_mut_ptr(), len);
    out
}

/// Call a JSON-in/JSON-out export end to end and return the parsed result,
/// freeing both the input and result buffers (so leaks would show under repetition).
fn call_json(export: JsonExport, handle: u32, json: &str) -> Value {
    let bytes = json.as_bytes();
    unsafe {
        let input = ks_alloc(bytes.len());
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), input, bytes.len());
        let result = export(handle, input as *const u8, bytes.len());
        let out = read_result(result);
        ks_free_result(result);
        ks_free(input, bytes.len());
        serde_json::from_slice(&out).expect("result is valid JSON")
    }
}

fn status(handle: u32) -> Value {
    unsafe {
        let result = ks_get_status(handle);
        let out = read_result(result);
        ks_free_result(result);
        serde_json::from_slice(&out).expect("status is valid JSON")
    }
}

const RULESET: &str = r#"{"schema_version":"1.0","rules_version":12,"rules":[
    {"id":"block","name":"Block","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
     "enabled":true,"priority":100,"condition":{"all":[{"field":"body.amount","operator":"gt","value":100}]},
     "action":{"type":"block","payload":{"code":"RULE_BLOCKED"}}}]}"#;

const INPUT_MATCH: &str = r#"{"selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
    "facts":{"headers":{},"query":{},"params":{},"body":{"amount":500},"meta":{}}}"#;

#[test]
fn full_lifecycle_through_abi() {
    let h = ks_create_engine();
    assert!(h != 0, "handle is non-zero");

    // Fresh engine: not loaded yet.
    let s = status(h);
    assert_eq!(s["last_load_ok"], Value::Bool(false));
    assert_eq!(s["active_rules_version"], Value::Null);

    // Load a ruleset.
    let load = call_json(ks_load_rules, h, RULESET);
    assert_eq!(load["ok"], Value::Bool(true));
    assert_eq!(load["compiled_rule_count"], 1);
    assert_eq!(load["rules_version"], 12);

    // Status reflects the active ruleset.
    let s = status(h);
    assert_eq!(s["initialized"], Value::Bool(true));
    assert_eq!(s["active_rules_version"], 12);
    assert_eq!(s["rule_count"], 1);
    assert_eq!(s["last_load_ok"], Value::Bool(true));

    // Evaluate a matching request.
    let eval = call_json(ks_evaluate, h, INPUT_MATCH);
    assert_eq!(eval["ok"], Value::Bool(true));
    assert_eq!(eval["matched"].as_array().unwrap().len(), 1);
    assert_eq!(eval["matched"][0]["id"], "block");

    ks_destroy_engine(h);

    // After destroy, the handle is gone.
    let s = status(h);
    assert_eq!(s["initialized"], Value::Bool(false));
    assert_eq!(s["last_error"]["code"], "INVALID_HANDLE");
}

#[test]
fn invalid_handle_returns_structured_errors() {
    let load = call_json(ks_load_rules, 999, RULESET);
    assert_eq!(load["ok"], Value::Bool(false));
    assert_eq!(load["errors"][0]["code"], "INVALID_HANDLE");

    let eval = call_json(ks_evaluate, 999, INPUT_MATCH);
    assert_eq!(eval["ok"], Value::Bool(false));
    assert_eq!(eval["errors"][0]["code"], "INVALID_HANDLE");
}

#[test]
fn malformed_input_is_handled_safely() {
    let h = ks_create_engine();

    // Not JSON at all -> load reports a parse failure, not a panic.
    let load = call_json(ks_load_rules, h, "{ not json");
    assert_eq!(load["ok"], Value::Bool(false));
    assert!(load["errors"][0]["code"].is_string());

    // Not a valid EvaluationInput -> structured INVALID_INPUT.
    let eval = call_json(ks_evaluate, h, "{}");
    assert_eq!(eval["ok"], Value::Bool(false));
    assert_eq!(eval["errors"][0]["code"], "INVALID_INPUT");

    ks_destroy_engine(h);
}

#[test]
fn repeated_calls_are_stable() {
    let h = ks_create_engine();
    for _ in 0..1000 {
        let load = call_json(ks_load_rules, h, RULESET);
        assert_eq!(load["ok"], Value::Bool(true));
        let eval = call_json(ks_evaluate, h, INPUT_MATCH);
        assert_eq!(eval["matched"].as_array().unwrap().len(), 1);
    }
    ks_destroy_engine(h);
}

#[test]
fn engine_handles_are_isolated() {
    // Two engines with different rulesets/versions must not see each other's state.
    let ruleset_a = r#"{"schema_version":"1.0","rules_version":1,"rules":[
        {"id":"a","name":"A","selector":{"kind":"api","keys":{"method":"GET","path":"/a"}},
         "enabled":true,"condition":{"field":"body.x","operator":"exists"},"action":{"type":"a"}}]}"#;
    let ruleset_b = r#"{"schema_version":"1.0","rules_version":2,"rules":[
        {"id":"b","name":"B","selector":{"kind":"api","keys":{"method":"GET","path":"/b"}},
         "enabled":true,"condition":{"field":"body.x","operator":"exists"},"action":{"type":"b"}}]}"#;
    let input_a = r#"{"selector":{"kind":"api","keys":{"method":"GET","path":"/a"}},
        "facts":{"headers":{},"query":{},"params":{},"body":{"x":1},"meta":{}}}"#;

    let h1 = ks_create_engine();
    let h2 = ks_create_engine();
    assert_ne!(h1, h2, "distinct handles");

    call_json(ks_load_rules, h1, ruleset_a);
    call_json(ks_load_rules, h2, ruleset_b);

    // Each engine reports its own version.
    assert_eq!(status(h1)["active_rules_version"], 1);
    assert_eq!(status(h2)["active_rules_version"], 2);

    // h1 has rule A (matches /a); h2 has only rule B (no /a rule -> no match).
    assert_eq!(call_json(ks_evaluate, h1, input_a)["matched"].as_array().unwrap().len(), 1);
    assert_eq!(call_json(ks_evaluate, h2, input_a)["matched"].as_array().unwrap().len(), 0);

    // Destroying one leaves the other intact.
    ks_destroy_engine(h1);
    assert_eq!(status(h1)["initialized"], Value::Bool(false));
    assert_eq!(status(h2)["active_rules_version"], 2);

    ks_destroy_engine(h2);
}
