---
name: generate-wasm-export
description: Generates or reviews Rust WASM export functions for KLSwitch following JSON in/out, explicit memory ownership, and no network/logging/analytics in WASM. Use when implementing rust-core WASM bindings, host bridge code, or when the user mentions WASM exports, alloc/free, or wasm-host-contract.
---

# Generate WASM Export

## Instructions

1. **Contract source of truth**: Follow `docs/wasm-host-contract.md` and `CLAUDE.md`: WASM owns validation, compilation, indexing, in-memory rules cache, atomic replacement, evaluation, and engine status — **not** HTTP, polling, timers, logging, analytics, or framework code.

2. **Conceptual exports** (adapt names to the project’s actual ABI): `create_engine`, `destroy_engine`, `load_rules`, `evaluate`, `get_status`, plus `alloc` / `free` / result-free helpers. One stateful engine instance per SDK instance; evaluation is synchronous from the caller’s perspective.

3. **JSON boundary**: Input and output are JSON bytes only for v1 (`ruleset`, evaluation input, load/eval results, status, structured errors). No custom binary host protocol.

4. **Memory ownership**:
   - Host allocates/writes input into WASM memory and calls the export.
   - WASM returns output as **pointer + length** (or equivalent); host copies bytes out **immediately**.
   - Host frees the output buffer with the **agreed free function**; host must not retain raw WASM pointers after the bridge call completes.

5. **load_rules**: Parse full ruleset, validate schema, compile, build selector indexes, then **atomically** swap active rules on success. On failure: structured error, **previous rules unchanged** (`docs/wasm-host-contract.md`).

6. **evaluate**: Shortlist by selector, evaluate conditions on shortlisted rules only, return **all** matches in **priority-descending** order (ties by declaration order).

7. **Errors**: Use structured error objects; align with `spec/schemas/error-info.schema.json` (`code`, `message`, optional `rule_id`, `field`).

8. **Status**: Align fields with `spec/schemas/engine-status.schema.json` where applicable.

## Anti-patterns (reject in generated code)

- `reqwest`, `ureq`, sockets, or any remote rules fetch inside WASM
- `log::`, `println!`, or analytics emitters in the engine path
- Framework types (HTTP server crates’ request/response types) inside the core WASM API
