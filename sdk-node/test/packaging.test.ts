import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

/**
 * Packaging is a contract the test suite cannot otherwise see.
 *
 * Every other test imports source by relative path, so it never exercises how a
 * consumer actually resolves the package. That gap shipped a real bug: `exports`
 * declared only ".", and since Node treats it as an exhaustive allowlist, the
 * documented `@klswitch/sdk-node/adapters/nestjs` import failed with
 * ERR_PACKAGE_PATH_NOT_EXPORTED for everyone installing from npm — while the
 * adapter itself was built, shipped and green in CI.
 *
 * These assert the manifest, not behaviour, precisely because behaviour tests
 * cannot reach it.
 */
const pkg = JSON.parse(
  readFileSync(fileURLToPath(new URL("../package.json", import.meta.url)), "utf8"),
) as {
  exports: Record<string, unknown>;
  files: string[];
  peerDependencies: Record<string, string>;
};

test("every adapter a consumer is told to import is declared in exports", () => {
  // `adapters/nestjs` cannot be re-exported from the root barrel — it pulls
  // `@nestjs/common`, an optional peer — so the subpath is the only way in.
  for (const sub of ["./adapters/nestjs", "./adapters/express", "./adapters/fastify"]) {
    assert.ok(
      sub in pkg.exports,
      `${sub} is not in "exports"; Node would reject it with ERR_PACKAGE_PATH_NOT_EXPORTED`,
    );
  }
});

test("the root export resolves types and ESM, and rejects require with an explanation", () => {
  const root = pkg.exports["."] as Record<string, string>;
  assert.equal(root.types, "./dist/index.d.ts");
  assert.equal(root.import, "./dist/index.js");
  // ESM-only is deliberate: a dual build would let one process hold two WASM
  // engines and two polling loops. The `require` condition exists so that shows
  // up as a sentence rather than as "No exports main defined".
  assert.equal(root.require, "./cjs-unsupported.cjs");
});

test("everything exports points at is actually published", () => {
  const published = (p: string) => pkg.files.some((f) => p.startsWith(`./${f}`));
  const targets = Object.values(pkg.exports).flatMap((v) =>
    typeof v === "string" ? [v] : Object.values(v as Record<string, string>),
  );
  for (const t of targets) {
    if (t === "./package.json") continue; // always published
    assert.ok(published(t), `${t} is referenced by "exports" but not covered by "files"`);
  }
});

test("the express peer range matches what the adapters actually support", () => {
  // The route resolver and the manifest discoverer are both Express v4 only —
  // v5 restructured the router and changed path-to-regexp output. Claiming ^5
  // means npm installs cleanly and then silently reports concrete paths with
  // routes missing from the manifest.
  assert.equal(
    pkg.peerDependencies.express,
    "^4",
    "widen this only when express-routes.ts and manifest/express.ts actually support v5",
  );
});
