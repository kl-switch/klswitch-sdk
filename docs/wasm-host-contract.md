# WASM Host Contract

## Purpose

This document defines the conceptual contract between SDK wrappers and the WASM engine.

V1 uses:
- JSON in / JSON out
- explicit memory ownership
- one stateful engine instance per SDK instance

---

## 1. Engine lifecycle

Conceptual exports:

- `create_engine()`
- `destroy_engine(engine_handle)`

The wrapper creates one long-lived engine instance and reuses it across evaluations.

---

## 2. Rules loading

Conceptual export:

- `load_rules(engine_handle, rules_json)`

Rules loading must:
- parse full ruleset payload
- validate schema
- compile rules into runtime structures
- build selector indexes
- atomically replace active rules if successful

If loading fails:
- return structured error information
- keep previous active rules unchanged

---

## 3. Evaluation

Conceptual export:

- `evaluate(engine_handle, evaluation_input_json)`

Evaluation must:
- use selector to shortlist candidate rules
- evaluate conditions against facts
- return all matched rules in priority-descending order

Evaluation is synchronous from the caller perspective.

---

## 4. Status

Conceptual export:

- `get_status(engine_handle)`

Status should include:
- initialized
- schema_version
- active_rules_version
- rule_count
- last_load_ok
- last_error

---

## 5. Memory ownership

V1 memory rules:
- wrapper allocates and writes input bytes into WASM memory
- wrapper calls export
- WASM returns pointer + length for output
- wrapper copies output bytes out immediately
- wrapper frees output buffer using agreed free function
- wrapper must not hold raw WASM pointers after bridge completion

Ownership must always be explicit.

---

## 6. JSON boundary

V1 uses JSON for:
- ruleset payloads
- evaluation inputs
- load results
- evaluation results
- engine status
- structured errors

No custom binary boundary protocol is allowed in v1.

---

## 7. Out-of-scope for WASM

WASM must not:
- fetch remote rules
- make HTTP calls
- log directly
- emit analytics
- know about Express, Spring, net/http, or similar frameworks

WASM owns computation and state only.

---

## 8. Concrete v1 ABI (frozen)

Sections 1–4 name exports conceptually. The concrete v1 wasm module
(`rust-core`, built as `cdylib` for `wasm32-unknown-unknown`) exports these
symbols, all prefixed `ks_` (the prefix avoids clashing with the C runtime's
`alloc`/`free` when the same code is linked natively for tests):

| Export | Signature (wasm32: `usize`/pointer = `i32`) | Returns |
| --- | --- | --- |
| `ks_create_engine` | `() -> u32` | engine handle (≥ 1; 0 means invalid) |
| `ks_destroy_engine` | `(handle: u32)` | — |
| `ks_load_rules` | `(handle: u32, ptr: i32, len: i32) -> i32` | result-buffer ptr (`LoadRulesResult` JSON) |
| `ks_evaluate` | `(handle: u32, ptr: i32, len: i32) -> i32` | result-buffer ptr (`EvaluationResult` JSON) |
| `ks_get_status` | `(handle: u32) -> i32` | result-buffer ptr (`EngineStatus` JSON) |
| `ks_alloc` | `(size: i32) -> i32` | input-buffer ptr (null for size 0) |
| `ks_free` | `(ptr: i32, size: i32)` | — |
| `ks_free_result` | `(ptr: i32)` | — |

The module also exports its linear `memory`, which the host reads and writes.

### Input buffers

1. Host calls `ks_alloc(len)` to get `ptr`.
2. Host writes `len` bytes of UTF-8 JSON into `memory` at `ptr`.
3. Host calls the export with `(handle, ptr, len)`.
4. After the call returns, the host calls `ks_free(ptr, len)`. The engine only
   borrows the input during the call; it never takes ownership.

### Result buffers

Every JSON-returning export returns a pointer to a buffer laid out as:

```
[ len: u32 little-endian ][ json: `len` bytes ]
```

1. Host reads the 4-byte little-endian length at `ptr`.
2. Host copies the next `len` bytes as the JSON response.
3. Host calls `ks_free_result(ptr)` (it reads the prefix to size the free).

### Error and lifecycle behavior

- An **invalid handle** yields a structured error result of the right kind:
  `LoadRulesResult` / `EvaluationResult` with `ok: false` and an
  `INVALID_HANDLE` error, or an `EngineStatus` with `initialized: false`.
- **Non-UTF-8 bytes** or **malformed JSON** yield `ok: false` with an
  `INVALID_INPUT` error. Exports never panic or trap on bad input.
- Engine instances live in an in-module handle table; one handle per SDK engine
  instance, created with `ks_create_engine` and released with `ks_destroy_engine`.