# KLSwitch

[![CI](https://github.com/kl-switch/klswitch-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/kl-switch/klswitch-sdk/actions/workflows/ci.yml)

KLSwitch is a WASM-first multi-language rule engine SDK.

The shared rule engine core is implemented in Rust and compiled to WebAssembly. Language-specific SDK wrappers load the WASM module and provide host-specific capabilities such as polling, logging, analytics, and framework integration.

## Product direction

KLSwitch is designed to be a generic rule engine over time, but v1 is intentionally focused on API-centric rule evaluation.

## Core principles

- Local in-process evaluation
- No network call at evaluation time
- Shared rule behavior across languages
- Remote rules refresh through SDK wrapper polling
- Per-rule kill switch using `enabled: false`
- Strict WASM vs wrapper boundary

## Repo structure

- `docs/` contains architecture, contracts, and engineering guidance
- `spec/` contains schemas, examples, and compliance fixtures
- `rust-core/` contains shared engine logic
- `sdk-node/`, `sdk-java/`, `sdk-go/`, `sdk-python/` contain the four v1 wrappers
- `sdk-java-spring-boot-starter/` is the Spring Boot auto-config layer on top of `sdk-java`
- `examples/`, `examples-java/`, `examples-go/`, `examples-python/` are runnable demo servers
- `backend/` contains rules API materials (v1 is mocked)
- `scripts/` holds the validation / compliance / release-gate scripts

v1 ships Node, Java, Go, and Python wrappers — all four replay the same WASM
engine against the same compliance fixtures.

## Status

v1 is feature-complete: the Rust engine, all four wrappers
(`init` / `evaluate` / `refresh` / `getStatus` / `close`), framework adapters,
and a mock rules API are in place. Every wrapper replays the **same**
`spec/compliance/v1/` fixtures against the shared engine and matches
byte-for-byte. See [`docs/known-limitations.md`](docs/known-limitations.md) for
what v1 intentionally does not do.

Adapter matrix shipped with v1:

| Wrapper | Adapters |
| --- | --- |
| Node    | Express, Fastify, NestJS |
| Java    | JAX-RS filter, Servlet filter, Spring Boot starter |
| Go      | `net/http`, `chi`, `gorilla/mux` |
| Python  | Flask, FastAPI, Django |

## Quick start

Each wrapper bundles a prebuilt `klswitch_core.wasm`, so you can use them
without the Rust toolchain. Rebuilding the engine needs Rust + the
`wasm32-unknown-unknown` target, and goes through
[`scripts/build-wasm.sh`](scripts/build-wasm.sh) — it remaps build paths out of
the artifact and syncs all four copies, so the committed `.wasm` files stay
byte-identical and reproducible from any checkout.

```bash
# Node wrapper
cd sdk-node && npm install && npm test
# Java wrapper
cd sdk-java && mvn test
# Go wrapper
cd sdk-go && go test ./...
# Python wrapper
cd sdk-python && python -m venv .venv && .venv/bin/pip install -e '.[dev]' && .venv/bin/pytest
# Everything, against one freshly built engine:
bash scripts/run-compliance-tests.sh
```

Per-wrapper usage and adapter examples live in each wrapper's README:
[`sdk-node/README.md`](sdk-node/README.md), [`sdk-java/README.md`](sdk-java/README.md),
[`sdk-go/README.md`](sdk-go/README.md), [`sdk-python/README.md`](sdk-python/README.md).
Runnable demos: `examples/` (Express/Fastify/NestJS), `examples-java/` (Dropwizard / Spring Boot),
`examples-go/` (net/http / chi / mux), `examples-python/` (Flask/FastAPI/Django).

## Key docs

Read these first:
- `docs/prd-trd.md`
- `docs/architecture.md`
- `docs/wasm-host-contract.md`
- `docs/sdk-wrapper-contract.md`
- `docs/review-checklist.md`
- `docs/known-limitations.md`
- `docs/release-strategy.md`

## Contributing

The architecture boundary in [`CLAUDE.md`](CLAUDE.md) is the one rule that
matters: engine logic stays in `rust-core/`, runtime concerns stay in the
wrappers. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the test suites a change
has to pass, and [`SECURITY.md`](SECURITY.md) to report a vulnerability.

## License

MIT — see [`LICENSE`](LICENSE).

