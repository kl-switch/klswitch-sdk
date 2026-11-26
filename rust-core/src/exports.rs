//! Phase 7 — WASM export layer.
//!
//! JSON-in / JSON-out across the host boundary with explicit memory ownership
//! (see `memory`) and a handle table of [`Engine`] instances. The concrete v1
//! ABI is documented in `docs/wasm-host-contract.md`; names are prefixed `ks_`.
//!
//! Exports never panic on bad input: an invalid handle, non-UTF-8 bytes, or
//! malformed JSON each return a structured error result of the appropriate kind.

use std::cell::RefCell;
use std::collections::HashMap;

use serde::Serialize;

use crate::engine::{Engine, SCHEMA_VERSION};
use crate::errors::ErrorInfo;
use crate::evaluation::{EvaluationInput, EvaluationResult};
use crate::load_result::LoadRulesResult;
use crate::memory::into_result;
use crate::status::EngineStatus;

/// Per-instance engine table. WASM is single-threaded; a thread-local keeps the
/// host tests isolated too. Handles start at 1 so 0 can mean "invalid handle".
struct Registry {
    next: u32,
    engines: HashMap<u32, Engine>,
}

thread_local! {
    static REGISTRY: RefCell<Registry> = RefCell::new(Registry { next: 1, engines: HashMap::new() });
}

/// Create a new engine instance. Returns its handle (never 0).
#[no_mangle]
pub extern "C" fn ks_create_engine() -> u32 {
    REGISTRY.with(|reg| {
        let mut reg = reg.borrow_mut();
        // Hand out the next free, non-zero handle. In practice this just
        // increments; the loop only matters if the u32 counter ever wraps
        // (~4 billion creates), where it must skip 0 (the invalid sentinel) and
        // any still-live handle rather than overwrite it.
        loop {
            let handle = reg.next;
            reg.next = reg.next.checked_add(1).unwrap_or(1);
            if handle != 0 && !reg.engines.contains_key(&handle) {
                reg.engines.insert(handle, Engine::new());
                return handle;
            }
        }
    })
}

/// Destroy an engine instance. No-op for an unknown handle.
#[no_mangle]
pub extern "C" fn ks_destroy_engine(handle: u32) {
    REGISTRY.with(|reg| {
        reg.borrow_mut().engines.remove(&handle);
    });
}

/// Load a ruleset payload (`[ptr, len)` JSON) into the engine. Returns a result
/// buffer holding a `LoadRulesResult` JSON.
///
/// # Safety
/// `ptr`/`len` must describe a valid input buffer from [`crate::memory::ks_alloc`].
#[no_mangle]
pub unsafe extern "C" fn ks_load_rules(handle: u32, ptr: *const u8, len: usize) -> *mut u8 {
    let json = match read_input(ptr, len) {
        Some(s) => s,
        None => return serialize(&load_error("INVALID_INPUT", "Input bytes were not valid UTF-8.")),
    };
    REGISTRY.with(|reg| match reg.borrow_mut().engines.get_mut(&handle) {
        Some(engine) => serialize(&engine.load_rules(&json)),
        None => serialize(&load_error("INVALID_HANDLE", "No engine exists for the given handle.")),
    })
}

/// Evaluate an `EvaluationInput` (`[ptr, len)` JSON) against the active ruleset.
/// Returns a result buffer holding an `EvaluationResult` JSON.
///
/// # Safety
/// `ptr`/`len` must describe a valid input buffer from [`crate::memory::ks_alloc`].
#[no_mangle]
pub unsafe extern "C" fn ks_evaluate(handle: u32, ptr: *const u8, len: usize) -> *mut u8 {
    let json = match read_input(ptr, len) {
        Some(s) => s,
        None => return serialize(&eval_error("INVALID_INPUT", "Input bytes were not valid UTF-8.")),
    };
    let input: EvaluationInput = match serde_json::from_str(&json) {
        Ok(input) => input,
        Err(e) => return serialize(&eval_error("INVALID_INPUT", &format!("Invalid evaluation input: {e}"))),
    };
    REGISTRY.with(|reg| match reg.borrow().engines.get(&handle) {
        Some(engine) => serialize(&engine.evaluate(&input)),
        None => serialize(&eval_error("INVALID_HANDLE", "No engine exists for the given handle.")),
    })
}

/// Return a result buffer holding the engine's `EngineStatus` JSON. An unknown
/// handle yields a status with `initialized: false` and an `INVALID_HANDLE` error.
#[no_mangle]
pub extern "C" fn ks_get_status(handle: u32) -> *mut u8 {
    REGISTRY.with(|reg| match reg.borrow().engines.get(&handle) {
        Some(engine) => serialize(&engine.status()),
        None => serialize(&EngineStatus {
            initialized: false,
            schema_version: SCHEMA_VERSION.to_string(),
            active_rules_version: None,
            rule_count: 0,
            last_load_ok: false,
            last_error: Some(error("INVALID_HANDLE", "No engine exists for the given handle.")),
        }),
    })
}

/// Copy the input bytes into an owned `String`, validating UTF-8. Returns `None`
/// on invalid UTF-8 or a null pointer with non-zero length.
fn read_input(ptr: *const u8, len: usize) -> Option<String> {
    if len == 0 {
        return Some(String::new());
    }
    if ptr.is_null() {
        return None;
    }
    // SAFETY: the host contract guarantees `[ptr, ptr+len)` is a readable input
    // buffer (from `ks_alloc`) for the duration of the call.
    let bytes = unsafe { std::slice::from_raw_parts(ptr, len) };
    std::str::from_utf8(bytes).ok().map(str::to_owned)
}

fn serialize<T: Serialize>(value: &T) -> *mut u8 {
    // Serializing our own owned types never fails.
    let bytes = serde_json::to_vec(value).expect("serialize result");
    into_result(&bytes)
}

fn error(code: &str, message: &str) -> ErrorInfo {
    ErrorInfo {
        code: code.to_string(),
        message: message.to_string(),
        rule_id: None,
        field: None,
    }
}

fn load_error(code: &str, message: &str) -> LoadRulesResult {
    LoadRulesResult {
        ok: false,
        rules_version: 0,
        rule_count: 0,
        compiled_rule_count: 0,
        skipped_rule_count: 0,
        errors: vec![error(code, message)],
    }
}

fn eval_error(code: &str, message: &str) -> EvaluationResult {
    EvaluationResult {
        ok: false,
        matched: Vec::new(),
        evaluated: 0,
        skipped: 0,
        errors: vec![error(code, message)],
    }
}
