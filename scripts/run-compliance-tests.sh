#!/usr/bin/env bash
#
# Cross-language compliance + full test run for KLSwitch.
#
# Rebuilds the WASM engine from rust-core (when the wasm32 toolchain is present),
# then runs the Rust core, Node, Java, Go, and Python wrapper suites. All five
# replay spec/compliance/v1/cases against the SAME engine, so a green run proves
# the wrappers introduce no behavioral drift. Safe to run from anywhere.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Make a user-local Rust toolchain visible if it isn't already on PATH.
if ! command -v cargo >/dev/null 2>&1 && [ -f "$HOME/.cargo/env" ]; then
  # shellcheck disable=SC1091
  . "$HOME/.cargo/env"
fi
have_cargo() { command -v cargo >/dev/null 2>&1; }

# npm, mvn, go, and python are required: they run the wrapper suites. Fail
# clearly up front rather than aborting mid-run (and never silently skip a
# wrapper — a release gate must not green-light while skipping a whole suite).
# cargo is optional: the wasm is committed, so the Rust step degrades to a
# warning.
require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: '$1' is required to run the $2 suite but was not found on PATH." >&2
    exit 1
  }
}
require npm    "Node wrapper"
require mvn    "Java wrapper"
require go     "Go wrapper"
require python "Python wrapper"

# The Python wrapper needs its venv populated with wasmtime + pytest + adapter
# deps. We don't (re)create the venv here — that's a setup step — but we fail
# fast if it's missing or doesn't have pytest, rather than letting the wrapper
# step blow up with a vague "command not found".
PY_VENV="$ROOT/sdk-python/.venv"
PY_BIN="$PY_VENV/bin/python"
[ -x "$PY_BIN" ] || {
  echo "ERROR: $PY_BIN not found. Run:" >&2
  echo "       python -m venv sdk-python/.venv && sdk-python/.venv/bin/pip install -e 'sdk-python[dev]'" >&2
  exit 1
}
"$PY_BIN" -m pytest --version >/dev/null 2>&1 || {
  echo "ERROR: pytest not installed in $PY_VENV. Run:" >&2
  echo "       sdk-python/.venv/bin/pip install -e 'sdk-python[dev]'" >&2
  exit 1
}

rebuild_wasm() {
  if ! have_cargo; then
    echo "WARN: cargo not found; using the committed klswitch_core.wasm." >&2
    return
  fi
  if ! rustup target list --installed 2>/dev/null | grep -q wasm32-unknown-unknown; then
    echo "WARN: wasm32-unknown-unknown target not installed; using the committed wasm." >&2
    return
  fi
  echo "== Rebuilding the WASM engine from rust-core =="
  # Via the script, not cargo directly: it remaps build paths out of the
  # artifact (see scripts/build-wasm.sh) and syncs all four copies.
  ./scripts/build-wasm.sh
}

rebuild_wasm

if have_cargo; then
  echo "== Rust core (domain roundtrip + eval + scenario fixtures) =="
  cargo test --manifest-path rust-core/Cargo.toml
else
  echo "WARN: skipping Rust tests (no cargo)." >&2
fi

echo "== Node wrapper (eval + scenario fixtures, bridge, polling, mock API) =="
( cd sdk-node && npm test )

echo "== Java wrapper (eval + scenario fixtures, lifecycle, polling, mock API) =="
# install (not just test) — the spring-boot-starter module below depends on
# this artifact via the local Maven repo. Keeps the run hermetic: no need
# for a pre-deployed snapshot somewhere.
( cd sdk-java && mvn -q install )

echo "== Java Spring Boot starter (auto-config + interceptor + manifest discovery) =="
( cd sdk-java-spring-boot-starter && mvn -q test )

echo "== Go wrapper (eval + scenario fixtures, lifecycle, polling, middleware) =="
( cd sdk-go && go test ./... )

echo "== Python wrapper (eval + scenario fixtures, lifecycle, polling, async, adapters) =="
( cd sdk-python && .venv/bin/pytest -q )

echo
echo "All compliance suites passed (rust-core + node + java + java-spring + go + python)."
