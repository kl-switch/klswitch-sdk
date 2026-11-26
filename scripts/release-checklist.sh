#!/usr/bin/env bash
#
# Pre-release gate for KLSwitch v1 (internal MVP milestone).
#
# Runs schema/example validation and the full cross-language compliance suite,
# enforces the frozen WASM/wrapper boundary, then prints the manual checklist to
# complete before tagging. Exits non-zero on any automated failure.
# See docs/release-strategy.md and docs/known-limitations.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
HERE="$ROOT/scripts"

echo "########## 1/3  Schema & example validation ##########"
bash "$HERE/validate-schemas.sh"

echo "########## 2/3  Cross-language compliance ##########"
bash "$HERE/run-compliance-tests.sh"

echo "########## 3/3  Boundary check (the WASM core must stay pure) ##########"
bash "$HERE/check-boundary.sh"

echo
echo "All automated gates passed. Complete the manual checklist before tagging:"
cat <<'EOF'
  [ ] docs/known-limitations.md reviewed and current for this release
  [ ] versions bumped: rust-core/Cargo.toml, sdk-node/package.json, sdk-java/pom.xml
  [ ] docs/release-strategy.md version line updated
  [ ] release notes / CHANGELOG drafted
  [ ] working tree clean and on the intended branch
  [ ] feature branches merged to the release line
  [ ] tag the MVP-ready milestone (annotated), e.g.:
          git tag -a v0.1.0-mvp -m "KLSwitch v1 internal MVP"
EOF
