import { test } from "node:test";
import assert from "node:assert/strict";

import { isValidInstanceId, resolveInstanceId } from "../src/instance-id.js";

test("isValidInstanceId: accepts allowed charset", () => {
  assert.equal(isValidInstanceId("pod-7f4a"), true);
  assert.equal(isValidInstanceId("payments-api-prod-0"), true);
  assert.equal(isValidInstanceId("arn:aws:ecs:us-east-1:123/cluster/task"), true);
  assert.equal(isValidInstanceId("a"), true);
  assert.equal(isValidInstanceId("A_B.C-D:E/F"), true);
});

test("isValidInstanceId: rejects empty/whitespace/too-long/bad-charset", () => {
  assert.equal(isValidInstanceId(undefined), false);
  assert.equal(isValidInstanceId(null), false);
  assert.equal(isValidInstanceId(""), false);
  assert.equal(isValidInstanceId("   "), false);
  assert.equal(isValidInstanceId("has spaces"), false);
  assert.equal(isValidInstanceId("with#special"), false);
  assert.equal(isValidInstanceId("a".repeat(129)), false);
});

test("resolveInstanceId: explicit config wins over env", () => {
  const r = resolveInstanceId("from-config", {
    KS_INSTANCE_ID: "from-env",
    POD_NAME:       "from-pod",
  });
  assert.deepEqual(r, { value: "from-config", source: "config" });
});

test("resolveInstanceId: KS_INSTANCE_ID wins over POD_NAME when no config value", () => {
  const r = resolveInstanceId(undefined, {
    KS_INSTANCE_ID: "from-env",
    POD_NAME:       "from-pod",
  });
  assert.deepEqual(r, { value: "from-env", source: "env" });
});

test("resolveInstanceId: POD_NAME used when only it is set", () => {
  const r = resolveInstanceId(undefined, { POD_NAME: "from-pod" });
  assert.deepEqual(r, { value: "from-pod", source: "pod" });
});

test("resolveInstanceId: derives `${hostname}-${pid}-${startMs}` when nothing is set", () => {
  const r = resolveInstanceId(undefined, {});
  assert.equal(r.source, "derived");
  assert.match(r.value, /^[\w\-.:]+-\d+-\d+$/);
});

test("resolveInstanceId: invalid explicit value falls through to env/derived", () => {
  // bad charset in `instanceId` → fall to KS_INSTANCE_ID
  const r1 = resolveInstanceId("has spaces", { KS_INSTANCE_ID: "from-env" });
  assert.deepEqual(r1, { value: "from-env", source: "env" });

  // bad in KS_INSTANCE_ID too → fall to POD_NAME
  const r2 = resolveInstanceId("has spaces", { KS_INSTANCE_ID: "also bad!", POD_NAME: "pod" });
  assert.deepEqual(r2, { value: "pod", source: "pod" });

  // all bad → derived
  const r3 = resolveInstanceId("bad!", { KS_INSTANCE_ID: "bad@", POD_NAME: "bad#" });
  assert.equal(r3.source, "derived");
});

test("resolveInstanceId: trims surrounding whitespace on a valid value", () => {
  const r = resolveInstanceId("  pod-7f4a  ", {});
  assert.equal(r.value, "pod-7f4a");
  assert.equal(r.source, "config");
});
