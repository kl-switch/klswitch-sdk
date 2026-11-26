#!/usr/bin/env bash
#
# Build every publishable artifact into dist/, then prove each one actually
# carries the audited engine.
#
# The wrappers are thin; the thing consumers execute is klswitch_core.wasm,
# bundled inside each package by a different mechanism — package.json "files"
# for npm, a hatch force-include for the wheel, src/main/resources for the jar.
# Any one of those can silently stop including it, and the test suites would
# not notice: they run against the working tree, not the package. So this
# checks the engine is present in each artifact AND that its sha256 matches the
# committed engine, which CI separately proves is a reproducible build of
# rust-core.
#
# Usage: scripts/build-packages.sh
set -euo pipefail

cd "$(dirname "$0")/.."

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' is required ($2)." >&2; exit 1; }
}
require npm    "npm tarball"
require mvn    "Java jars"
require python3 "Python wheel + sdist"
python3 -m build --version >/dev/null 2>&1 || {
  echo "ERROR: the 'build' package is missing. Install it with:" >&2
  echo "       python3 -m pip install build" >&2
  exit 1
}

OUT="$PWD/dist"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "== Node — @klswitch/sdk-node =="
( cd sdk-node
  [ -d node_modules ] || npm ci --silent
  npm run --silent build
  npm pack --silent --pack-destination "$OUT" >/dev/null
  # Prints the exact file list the registry would receive.
  npm publish --dry-run )

echo "== Python — klswitch-sdk =="
python3 -m build --outdir "$OUT" sdk-python >/dev/null

echo "== Java — klswitch-sdk-java (+ the Spring starter, which compiles against it) =="
mvn -B -ntp -q -DskipTests install -f sdk-java/pom.xml
mvn -B -ntp -q -DskipTests package -f sdk-java-spring-boot-starter/pom.xml
cp sdk-java/target/*.jar "$OUT"/
cp sdk-java-spring-boot-starter/target/*.jar "$OUT"/

echo
echo "== Verifying the engine inside each artifact =="
python3 - "$OUT" <<'PY'
import hashlib, sys, tarfile, zipfile
from pathlib import Path

out = Path(sys.argv[1])
want = hashlib.sha256(Path("sdk-go/klswitch_core.wasm").read_bytes()).hexdigest()
print(f"  committed engine sha256 {want[:16]}…")

def members(p: Path):
    if p.suffix in (".whl", ".jar"):
        with zipfile.ZipFile(p) as z:
            for n in z.namelist():
                if n.endswith("klswitch_core.wasm"):
                    yield n, z.read(n)
    else:  # .tgz / .tar.gz
        with tarfile.open(p) as t:
            for m in t.getmembers():
                if m.name.endswith("klswitch_core.wasm"):
                    f = t.extractfile(m)
                    if f:
                        yield m.name, f.read()

# The Spring starter carries no engine: it depends on the wrapper jar, which does.
NO_ENGINE = {"klswitch-sdk-java-spring-boot-starter"}
artifacts = sorted(p for p in out.iterdir() if p.suffix in (".whl", ".jar", ".tgz", ".gz"))
if not artifacts:
    sys.exit("ERROR: dist/ is empty")

bad = []
for p in artifacts:
    if any(p.name.startswith(n) for n in NO_ENGINE):
        print(f"  {p.name:<46} (no engine by design — depends on the wrapper jar)")
        continue
    found = list(members(p))
    if not found:
        bad.append(f"{p.name}: no klswitch_core.wasm inside")
        continue
    for name, blob in found:
        got = hashlib.sha256(blob).hexdigest()
        mark = "ok" if got == want else "MISMATCH"
        print(f"  {p.name:<46} {name}  {got[:16]}…  {mark}")
        if got != want:
            bad.append(f"{p.name}: {name} is not the committed engine")

print()
if bad:
    for b in bad:
        print(f"ERROR: {b}", file=sys.stderr)
    sys.exit(1)
print(f"All {len(artifacts)} artifacts carry the committed engine.")
PY

echo
ls -lh "$OUT"
