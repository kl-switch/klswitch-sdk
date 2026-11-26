/**
 * Reached only when a CommonJS caller does `require("@klswitch/sdk-node")`.
 *
 * The package is ESM-only. Without this file Node reports
 * `ERR_PACKAGE_PATH_NOT_EXPORTED: No "exports" main defined`, which reads like a
 * broken package rather than a deliberate module format — so people file bugs
 * instead of changing one line.
 *
 * ESM-only is a considered choice, not an oversight. Shipping a second CJS build
 * would let a process load two copies of the SDK (the "dual package hazard"),
 * and here that means two WASM engines and two polling loops competing over the
 * same ruleset — a class of bug that is very hard to see and worse than the
 * inconvenience of dynamic import.
 */
throw new Error(
  '@klswitch/sdk-node is ESM-only and cannot be require()d.\n' +
    '\n' +
    'From CommonJS, use a dynamic import:\n' +
    '  const { KLSwitch } = await import("@klswitch/sdk-node");\n' +
    '\n' +
    'Or make the calling package ESM by adding "type": "module" to its package.json.\n' +
    '\n' +
    'Why: a dual ESM/CJS build lets one process load two copies of the SDK, which\n' +
    'would mean two WASM engines and two polling loops for the same ruleset.',
);
