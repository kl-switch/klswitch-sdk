# Releasing

Five artifacts, one version. Every wrapper bundles the same
`klswitch_core.wasm`, so a change to the engine forces all of them to move
together — `docs/release-strategy.md` §5. `scripts/check-versions.sh` enforces
it, in CI and locally.

| Artifact | Registry | Version comes from |
|---|---|---|
| `@klswitch/sdk-node` | npm | `sdk-node/package.json` |
| `klswitch-sdk` | PyPI | `sdk-python/pyproject.toml` |
| `com.klswitch:klswitch-sdk-java` | Maven Central | `sdk-java/pom.xml` |
| `com.klswitch:klswitch-sdk-java-spring-boot-starter` | Maven Central | that module's pom |
| `github.com/kl-switch/klswitch-sdk/sdk-go` | none — the tag *is* the release | the `sdk-go/vX.Y.Z` tag |

## Where the packages are

Three places, in increasing order of commitment:

| Where | When | What |
|---|---|---|
| A CI run's **artifacts** | every push and PR | `packages.zip` — the npm tarball, the wheel + sdist, both jars. Downloadable for 14 days: `gh run download <run-id> -n packages` |
| A **GitHub release** | on a `vX.Y.Z` tag | the same five artifacts, plus `klswitch_core.wasm` and `SHA256SUMS`, attached permanently |
| **npm / PyPI / Maven Central** | on a tag, per registry switched on below | the published packages |

Nothing reaches a registry without a tag *and* that registry's variable set, so
a green CI run publishes nothing by design.

Locally, `scripts/build-packages.sh` produces the identical set in `dist/` and
verifies the engine inside each one by checksum — the test suites run against
the working tree, so that check is the only thing that catches a package which
has quietly stopped bundling the wasm.

## Cutting a release

```sh
scripts/bump-version.sh 0.1.1     # every manifest + the example poms + both lockfiles
scripts/build-wasm.sh             # only if rust-core changed
scripts/release-checklist.sh      # the full gate, locally, before you tag
git commit -am "Release v0.1.1"
git tag -a v0.1.1 -m "KLSwitch SDK v0.1.1"
git push && git push --tags
```

The tag starts `.github/workflows/release.yml`, which re-runs the gate against
the tagged commit, refuses to continue if the tag and the manifests disagree,
publishes to whichever registries are switched on, creates the `sdk-go/v0.1.1`
tag that makes `go get` resolve, and opens a GitHub release carrying the engine
binary and its SHA-256.

## Switching a registry on

Each publisher is gated on a repository variable, so the workflow is inert
until the registry side actually exists. Set one with:

```sh
gh variable set PUBLISH_NPM --body true     # or PUBLISH_PYPI, PUBLISH_MAVEN
```

The gate job prints a table of what is on and what is off into every run's
summary.

### npm — `PUBLISH_NPM`

1. Create the `@klswitch` organisation on npmjs.com.
2. Publish `@klswitch/sdk-node` once by hand (trusted publishing can only be
   configured on a package that exists), then on the package's Settings page
   add a trusted publisher: this repository, workflow `release.yml`.
3. `gh variable set PUBLISH_NPM --body true`

No token is stored. The job authenticates over OIDC, which is also what lets
npm attach a provenance attestation — `publishConfig.provenance` in
`package.json` is already set. If you would rather use a token, add
`NPM_TOKEN` as a secret and set `NODE_AUTH_TOKEN` on the publish step instead.

### PyPI — `PUBLISH_PYPI`

1. On pypi.org, add a *pending* trusted publisher for the project name
   `klswitch-sdk`: owner `kl-switch`, repository `klswitch-sdk`, workflow
   `release.yml`. Pending publishers work before the project exists, so the
   first release can go straight through OIDC.
2. `gh variable set PUBLISH_PYPI --body true`

### Maven Central — `PUBLISH_MAVEN`

Not wired yet. The job fails loudly if you switch it on, because three things
are missing:

1. **A verified namespace.** The Central Portal verifies `com.klswitch` through
   a DNS TXT record on `klswitch.com`. (`io.github.kl-switch` is the fallback
   if that domain is ever out of reach — it is verified by the GitHub org
   itself, but it changes the groupId every consumer writes.)
2. **A signing key.** Central requires detached GPG signatures. Publish the
   public key to a keyserver and add `MAVEN_GPG_PRIVATE_KEY` and
   `MAVEN_GPG_PASSPHRASE` as secrets.
3. **Four plugins in both publishable poms** — `maven-source-plugin`,
   `maven-javadoc-plugin` (Central rejects a release without sources and
   javadoc jars), `maven-gpg-plugin`, and `central-publishing-maven-plugin`.

The metadata Central checks — `url`, `licenses`, `developers`, `scm` — is
already in both poms.

## What the gate actually checks

`scripts/release-checklist.sh` → schema and example validation
(`validate-schemas.sh`, including deserializing every `spec/examples` payload
into the engine's own domain models), the full cross-language compliance run
(`run-compliance-tests.sh` — rust-core plus all four wrappers against one
freshly built engine), and the architecture boundary (`check-boundary.sh`).

On top of that the release workflow asserts the tag matches every manifest, and
that a rebuild of the engine is byte-identical to the four committed copies.
