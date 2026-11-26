#!/usr/bin/env bash
#
# Move every version reference to one new version — the lockstep rule in
# docs/release-strategy.md §5, applied mechanically.
#
# Touches: rust-core/Cargo.toml, sdk-node/package.json (+ its lockfile, via
# `npm version`, or the gate's own `npm ci` will reject the mismatch),
# sdk-python/pyproject.toml, both publishable poms, and every in-repo
# dependency on a com.klswitch artifact including the example servers.
#
# sdk-go is absent on purpose: a Go module's version is its tag, which the
# release workflow creates as sdk-go/vX.Y.Z.
#
# Usage: scripts/bump-version.sh 0.1.1
set -euo pipefail

cd "$(dirname "$0")/.."

NEW="${1:-}"
if ! [[ "$NEW" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: scripts/bump-version.sh X.Y.Z   (got '${NEW}')" >&2
  exit 2
fi

python3 - "$NEW" <<'PY'
import re, sys, xml.etree.ElementTree as ET
from pathlib import Path

new = sys.argv[1]
NS = "http://maven.apache.org/POM/4.0.0"
ET.register_namespace("", NS)
changed = []

def sub_once(path, pattern, repl):
    p = Path(path); s = p.read_text()
    s2, n = re.subn(pattern, repl, s, count=1, flags=re.M)
    if n != 1:
        sys.exit(f"ERROR: {path}: expected one version line, matched {n}")
    if s2 != s:
        p.write_text(s2); changed.append(path)

# [package] version is the first `version = "..."` in the file; dependency
# versions below it are inline tables and never match this anchored pattern.
sub_once("rust-core/Cargo.toml", r'^version = "[^"]+"', f'version = "{new}"')
sub_once("sdk-python/pyproject.toml", r'^version = "[^"]+"', f'version = "{new}"')

for pom in sorted(Path(".").glob("**/pom.xml")):
    if "target" in pom.parts:
        continue
    text = pom.read_text()
    tree = ET.parse(pom); root = tree.getroot()
    out = text
    # Project version — only for the artifacts that are actually published.
    if str(pom) in ("sdk-java/pom.xml", "sdk-java-spring-boot-starter/pom.xml"):
        node = root.find(f"{{{NS}}}version")
        if node is not None and node.text and node.text.strip() != new:
            out = out.replace(f"<version>{node.text.strip()}</version>",
                              f"<version>{new}</version>", 1)
    # Every dependency on one of our own artifacts.
    for dep in root.iter(f"{{{NS}}}dependency"):
        gid = (dep.findtext(f"{{{NS}}}groupId") or "").strip()
        aid = (dep.findtext(f"{{{NS}}}artifactId") or "").strip()
        ver = (dep.findtext(f"{{{NS}}}version") or "").strip()
        if gid == "com.klswitch" and ver and not ver.startswith("${") and ver != new:
            old_block = f"<artifactId>{aid}</artifactId>"
            idx = out.find(old_block)
            if idx == -1:
                continue
            tail = out[idx:]
            tail_new = tail.replace(f"<version>{ver}</version>",
                                    f"<version>{new}</version>", 1)
            out = out[:idx] + tail_new
    if out != text:
        pom.write_text(out); changed.append(str(pom))

print("\n".join(f"  updated {c}" for c in dict.fromkeys(changed)) or "  (already at that version)")
PY

# Cargo.lock records the crate's own version, and cargo rewrites it on the next
# invocation anyway — do it here so the bump commit is self-consistent.
if command -v cargo >/dev/null 2>&1; then
  cargo metadata --manifest-path rust-core/Cargo.toml --format-version 1 >/dev/null
  echo "  updated rust-core/Cargo.lock"
else
  echo "WARN: cargo not found — rust-core/Cargo.lock still holds the old version." >&2
fi

# `npm version` keeps package.json and package-lock.json consistent; an
# out-of-sync lockfile makes `npm ci` fail in CI, not at publish time.
if command -v npm >/dev/null 2>&1; then
  ( cd sdk-node && npm version "$NEW" --no-git-tag-version --allow-same-version >/dev/null )
  echo "  updated sdk-node/package.json + package-lock.json"
else
  echo "WARN: npm not found — update sdk-node/package.json and its lockfile by hand." >&2
fi

echo
./scripts/check-versions.sh "$NEW"
echo
echo "Next: rebuild the engine if rust-core changed (scripts/build-wasm.sh),"
echo "commit, then tag v$NEW to release. See .github/RELEASING.md."
