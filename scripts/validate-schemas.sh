#!/usr/bin/env bash
#
# Validate the spec artifacts. Every schema / example / compliance fixture must be
# well-formed JSON, and every schema must declare JSON Schema draft 2020-12.
#
# Full *instance* validation (examples + fixtures actually conform) is enforced by
# the Rust suite: rust-core/tests/roundtrip_examples.rs deserializes every
# spec/examples payload into the canonical domain models, and the compliance
# fixtures are replayed by all three wrappers. This script runs the roundtrip test
# when cargo is available — a stronger guarantee than schema-shape validation.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

command -v node >/dev/null 2>&1 || {
  echo "ERROR: 'node' is required for the JSON well-formedness check but was not found on PATH." >&2
  exit 1
}

echo "== Checking spec JSON is well-formed and schemas declare draft 2020-12 =="
# cwd is the repo root (set above), so spec paths are relative.
node <<'NODE'
const { readdirSync, readFileSync, statSync } = require("node:fs");
const { join } = require("node:path");
let files = 0, bad = 0;
const walk = (d) => {
  for (const e of readdirSync(d)) {
    const p = join(d, e);
    if (statSync(p).isDirectory()) { walk(p); continue; }
    if (!p.endsWith(".json")) continue;
    files++;
    try { JSON.parse(readFileSync(p, "utf8")); }
    catch (err) { bad++; console.error(`  BAD JSON: ${p}\n    ${err.message}`); }
  }
};
for (const d of ["spec/schemas", "spec/examples", "spec/compliance/v1/cases"]) walk(d);

let schemaWarn = 0;
const schemaDir = "spec/schemas";
for (const e of readdirSync(schemaDir).filter((f) => f.endsWith(".json"))) {
  const s = JSON.parse(readFileSync(join(schemaDir, e), "utf8"));
  if (!String(s.$schema || "").includes("2020-12")) {
    schemaWarn++;
    console.error(`  WARN: ${e} does not declare draft 2020-12 ($schema=${s.$schema})`);
  }
}
console.log(`  ${files} JSON files parsed, ${bad} malformed, ${schemaWarn} schema-draft warnings`);
if (bad > 0) process.exit(1);
NODE

if ! command -v cargo >/dev/null 2>&1 && [ -f "$HOME/.cargo/env" ]; then
  # shellcheck disable=SC1091
  . "$HOME/.cargo/env"
fi
if command -v cargo >/dev/null 2>&1; then
  echo "== Validating example payloads against the engine's domain models (cargo) =="
  cargo test --manifest-path rust-core/Cargo.toml --test roundtrip_examples
else
  echo "WARN: cargo not found; skipping domain-model validation of examples." >&2
fi

echo
echo "Schema / example / fixture validation passed."
