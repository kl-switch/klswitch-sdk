#!/usr/bin/env bash
#
# Version gate — release-strategy.md §5, lockstep variant.
#
# Every published artifact bundles the same klswitch_core.wasm, so a wasm
# change forces all of them to move together (§5, last paragraph). That only
# works if one version string is true everywhere, so this asserts exactly that
# across the five manifests, plus every in-repo dependency on a com.klswitch
# artifact — an example that still asks for the previous version compiles
# against a jar the release no longer produces.
#
# sdk-go has no manifest: a Go module's version IS its tag, which is why the
# release workflow mirrors vX.Y.Z to sdk-go/vX.Y.Z.
#
# Usage:
#   scripts/check-versions.sh              # all manifests agree with each other
#   scripts/check-versions.sh v0.1.0       # ...and agree with this tag
set -euo pipefail

cd "$(dirname "$0")/.."

python3 - "${1:-}" <<'PY'
import json, re, sys, xml.etree.ElementTree as ET
from pathlib import Path

POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
found: list[tuple[str, str, str]] = []   # (version, file, what)
errors: list[str] = []

def toml_version(path: str) -> str | None:
    # Deliberately regex, not tomllib: that is 3.11+, and this repo supports 3.10.
    text = Path(path).read_text()
    m = re.search(r'^\s*version\s*=\s*"([^"]+)"', text, re.M)
    return m.group(1) if m else None

v = toml_version("rust-core/Cargo.toml")
found.append((v, "rust-core/Cargo.toml", "wasm core")) if v else errors.append("rust-core/Cargo.toml: no version")

v = toml_version("sdk-python/pyproject.toml")
found.append((v, "sdk-python/pyproject.toml", "python wrapper")) if v else errors.append("sdk-python/pyproject.toml: no version")

v = json.loads(Path("sdk-node/package.json").read_text()).get("version")
found.append((v, "sdk-node/package.json", "node wrapper")) if v else errors.append("sdk-node/package.json: no version")

for pom, what in [("sdk-java/pom.xml", "java wrapper"),
                  ("sdk-java-spring-boot-starter/pom.xml", "spring starter")]:
    root = ET.parse(pom).getroot()
    node = root.find("m:version", POM_NS)
    if node is None or not (node.text or "").strip():
        errors.append(f"{pom}: no project-level <version>")
    else:
        found.append((node.text.strip(), pom, what))

# Every in-repo consumer of a com.klswitch artifact, examples included.
for pom in sorted(Path(".").glob("**/pom.xml")):
    if "target" in pom.parts:
        continue
    for dep in ET.parse(pom).getroot().iter("{http://maven.apache.org/POM/4.0.0}dependency"):
        gid = (dep.findtext("m:groupId", "", POM_NS) or "").strip()
        aid = (dep.findtext("m:artifactId", "", POM_NS) or "").strip()
        ver = (dep.findtext("m:version", "", POM_NS) or "").strip()
        if gid == "com.klswitch" and ver and not ver.startswith("${"):
            found.append((ver, str(pom), f"depends on {aid}"))

width = max(len(f) for _, f, _ in found)
for ver, f, what in found:
    print(f"  {f:<{width}}  {ver:<10} {what}")

versions = {v for v, _, _ in found}
if len(versions) > 1:
    errors.append("versions disagree: " + ", ".join(sorted(versions)) +
                  " — release-strategy.md §5 requires one version across all five artifacts")

expected = (sys.argv[1] or "").lstrip("v")
if expected:
    off = sorted({v for v in versions if v != expected})
    if off:
        errors.append(f"tag says {expected}, manifests say {', '.join(off)}")

print()
if errors:
    for e in errors:
        print(f"ERROR: {e}", file=sys.stderr)
    sys.exit(1)
print(f"All {len(found)} version references agree: {versions.pop()}")
PY
