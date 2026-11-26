# Release Strategy

## Purpose

This document defines how versioning and releases should be thought about.

---

## 1. Version lines

The project has three version lines:

- schema version
- WASM core version
- SDK wrapper version

---

## 2. Compatibility

Wrappers should validate compatibility with the bundled WASM version and expected schema version at initialization.

---

## 3. V1 expectation

V1 releases should prioritize:
- stable contracts
- stable schemas
- predictable wrapper APIs
- safe refresh behavior

---

## 4. Breaking changes

Breaking changes include:
- changing selector model
- changing evaluation result shape
- changing WASM memory ownership expectations
- changing public wrapper API semantics

---

## 5. Versioning notes (v1)

The three version lines are tracked in concrete places — keep them in step:

- **Schema version** — the `schema_version` string in a ruleset payload
  (`spec/schemas/ruleset.schema.json`), surfaced by `getStatus()`.
- **WASM core version** — `rust-core/Cargo.toml` `[package].version`; the built
  `klswitch_core.wasm` is committed in both wrappers and regenerated with
  `scripts/run-compliance-tests.sh` (or `npm run build:wasm`).
- **SDK wrapper version** — one version, shared by all five published
  artifacts: `sdk-node/package.json`, `sdk-java/pom.xml`,
  `sdk-java-spring-boot-starter/pom.xml`, `sdk-python/pyproject.toml`, and the
  `sdk-go/vX.Y.Z` tag (a Go module in a subdirectory takes its version from a
  tag carrying that prefix, so the tag *is* the release).

A wrapper release bundles exactly one WASM core build, so bump the wrapper
version whenever the bundled wasm changes — even if the public API did not.
Since all four wrappers bundle the *same* engine, that rule means they move
together: one version, every artifact, every release.

`scripts/bump-version.sh X.Y.Z` applies it across the manifests, both
lockfiles, and every in-repo dependency on a `com.klswitch` artifact (the
example servers included). `scripts/check-versions.sh` asserts it, and the
release workflow additionally asserts the git tag agrees.

---

## 6. Release checklist (internal MVP)

`scripts/release-checklist.sh` is the automated gate. It runs schema/example
validation (`validate-schemas.sh`), the full cross-language compliance suite
(`run-compliance-tests.sh` — rust-core plus all four wrappers against one freshly
built engine), and the architecture boundary check (`check-boundary.sh`), exiting
non-zero on any failure. It then prints the manual steps to finish before
tagging:

1. `docs/known-limitations.md` reviewed and current.
2. Versions bumped across the three lines in §5.
3. This document's version line updated.
4. Release notes / CHANGELOG drafted.
5. Working tree clean, on the intended branch, feature branches merged.
6. Tag the milestone (annotated), e.g. `git tag -a v0.1.0-mvp -m "…"`.

```bash
bash scripts/release-checklist.sh
```

---

## 7. CI and the release workflow

Both gates run in GitHub Actions:

- `.github/workflows/ci.yml` — on every pull request and push to `main`. The
  version and boundary gates, the engine (clippy + tests) and spec validation,
  all four wrapper suites across their supported language versions, the
  cross-language parity run, and two checks on the committed engine: that a
  rebuild of `rust-core` is byte-identical to it, and that all four bundled
  copies are the same bytes.
- `.github/workflows/release.yml` — on a `vX.Y.Z` tag. Re-runs the §6 gate
  against the tagged commit, publishes to the registries that are switched on,
  mirrors the tag to `sdk-go/vX.Y.Z`, and opens a GitHub release carrying the
  engine binary and its SHA-256.

The engine is rebuilt only through `scripts/build-wasm.sh`, which remaps build
paths out of the artifact; `rust-toolchain.toml` pins the toolchain so those
bytes are reproducible. Registry-by-registry setup is in
[`.github/RELEASING.md`](../.github/RELEASING.md).