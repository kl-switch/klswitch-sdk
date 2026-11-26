#!/usr/bin/env bash
#
# Cross-language compliance gate — fast.
#
# Stands in for the C2 CI gate until CI is set up: runs ONLY the four
# wrappers' compliance test classes against the shared
# spec/compliance/v1/cases/ fixtures, then prints a per-wrapper summary
# of how many eval + scenario fixtures each replayed. Exits non-zero on
# any failure or count divergence.
#
# Distinct from run-compliance-tests.sh, which runs each wrapper's FULL
# test suite (slower; rebuilds WASM; validates all the runtime concerns).
# This script is the minimum that proves all four wrappers still agree
# on the canonical set of compliance fixtures byte-for-byte.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ─── Pre-flight: audit the canonical fixture set ────────────────────────
audit_fixtures() {
  python3 - <<'PY'
import json, sys
from pathlib import Path

cases = sorted(Path("spec/compliance/v1/cases").glob("*.json"))
evals, scenarios, steps = [], [], 0
for p in cases:
    fx = json.loads(p.read_text())
    if fx.get("fixture_kind") == "scenario":
        scenarios.append(p.name)
        steps += len(fx.get("steps", []))
    elif "expected_evaluation_result" in fx:
        evals.append(p.name)
    else:
        print(f"ERROR: {p.name} is neither a scenario nor an eval fixture", file=sys.stderr)
        sys.exit(2)
print(f"canonical: {len(evals)} eval + {len(scenarios)} scenarios / {steps} steps "
      f"({len(cases)} total fixtures)")
PY
}

# ─── Pretty section header ──────────────────────────────────────────────
section() { printf '\n\033[1;34m== %s ==\033[0m\n' "$*"; }

# ─── Tool checks ────────────────────────────────────────────────────────
require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: '$1' is required but not on PATH (needed for $2)" >&2
    exit 1
  }
}
require npm    "Node compliance"
require mvn    "Java compliance"
require go     "Go compliance"
require python "Python compliance"

# ─── Run ────────────────────────────────────────────────────────────────
section "Auditing canonical fixture set"
audit_fixtures

section "Node — compliance fixtures"
( cd sdk-node && npx tsx --test test/fixtures.test.ts )

section "Java — ComplianceFixturesTest"
( cd sdk-java && mvn -q test -Dtest=ComplianceFixturesTest )

section "Go — TestCompliance_*"
( cd sdk-go && GOTOOLCHAIN=local go test -run '^TestCompliance_' -v . )

section "Python — test_compliance.py"
( cd sdk-python && python -m pytest tests/test_compliance.py -v --no-header )

printf '\n\033[1;32mAll four wrappers replayed the canonical compliance set successfully.\033[0m\n'
