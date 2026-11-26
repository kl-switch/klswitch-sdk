# rust-core

The shared KLSwitch rule engine, written in Rust and compiled to WebAssembly.
This is the single source of engine behavior for every language wrapper.

## Responsibilities (inside the WASM boundary)

- parse and validate rulesets
- compile rules into runtime structures and build the selector index
- hold the active compiled ruleset in memory (stateful engine)
- atomically replace the active ruleset on successful load
- evaluate facts against active rules
- expose engine status

It must **not** do HTTP, polling, timers, logging, analytics, or framework
integration — those live in the SDK wrappers. See
[`../docs/architecture.md`](../docs/architecture.md) and
[`../docs/wasm-host-contract.md`](../docs/wasm-host-contract.md).

## Behavior contract

Engine semantics are frozen in [`../docs/rule-model.md`](../docs/rule-model.md)
(operators, type handling, missing-field rule, tie-break, exact selector
matching) and exercised by the fixtures in
[`../spec/compliance/v1/cases/`](../spec/compliance/v1/cases/). Implementation
reminders live in [`IMPLEMENTATION-NOTES.md`](IMPLEMENTATION-NOTES.md).

## Status

Phase 3 complete — domain models with serde + roundtrip tests against
`spec/examples`. Next: Phase 4 (parsing/validation/compilation). See
[`../docs/implementation-plan.md`](../docs/implementation-plan.md).

## Build & test

```bash
cargo test
```
