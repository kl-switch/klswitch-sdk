# Contributing

## The architecture boundary comes first

`docs/` and `spec/` are the source of truth. The engine (`rust-core/`) owns
rule parsing, compilation, selector indexing and evaluation — and nothing
else. No HTTP, timers, logging, filesystem or env access ever goes into it.
The wrappers own WASM loading, polling, logging, analytics and the framework
adapters, and never contain rule logic. A change that moves work across that
line will be asked to move back; `CLAUDE.md` states the boundary in full.

## Before you open a pull request

1. Read `docs/architecture.md` and the contract doc for the layer you are
   touching (`docs/wasm-host-contract.md` or `docs/sdk-wrapper-contract.md`).
2. Behaviour changes need a compliance fixture in `spec/compliance/v1/cases/`.
   All four wrappers replay those fixtures against the same engine, so a
   fixture is how a behaviour becomes a promise rather than an accident.
3. Run the suites for what you touched:

   ```sh
   cargo test --manifest-path rust-core/Cargo.toml   # engine
   npm --prefix sdk-node test                        # Node
   go -C sdk-go test ./...                           # Go
   mvn -pl sdk-java -am test                         # Java
   pytest sdk-python                                 # Python
   ./scripts/run-compliance-tests.sh                 # every wrapper, same fixtures
   ```

4. Self-review against `docs/review-checklist.md`.

CI runs all of the above on every pull request, across each wrapper's
supported language versions, plus the cross-language parity run and the
version and architecture-boundary gates.

## Rebuilding the engine

The four committed `klswitch_core.wasm` copies are byte-identical and must
stay that way. Build them with `./scripts/build-wasm.sh`, which remaps build
paths so no machine-specific path is baked into a published artifact — a plain
`cargo build` embeds the absolute path of every panic site.

## Questions

Open an issue, or email dev@klswitch.com.
